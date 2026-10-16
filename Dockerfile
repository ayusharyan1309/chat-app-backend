# syntax=docker/dockerfile:1.7
# One image: API + realtime gateway + bundled operator console.

# ---- 1. Console -----------------------------------------------------------------------
FROM node:22-alpine AS console
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN --mount=type=cache,target=/root/.npm npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- 2. Backend -----------------------------------------------------------------------
FROM eclipse-temurin:21-jdk-noble AS backend
WORKDIR /src
# Wrapper and POM first, so dependencies are cached until the POM changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q dependency:go-offline
COPY src ./src
COPY --from=console /app/dist ./src/main/resources/static
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -q -DskipTests package \
    && java -Djarmode=tools -jar target/chat-saas-*.jar extract --layers --launcher --destination /layers

# ---- 3. Runtime -----------------------------------------------------------------------
FROM eclipse-temurin:21-jre-noble
RUN groupadd --system chat && useradd --system --gid chat --uid 10001 chat \
    && mkdir -p /app/data && chown -R chat:chat /app
WORKDIR /app
# Least to most frequently changing, so a code change only replaces the last layer.
COPY --from=backend /layers/dependencies/ ./
COPY --from=backend /layers/spring-boot-loader/ ./
COPY --from=backend /layers/snapshot-dependencies/ ./
COPY --from=backend /layers/application/ ./
USER chat
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
VOLUME ["/app/data"]
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
