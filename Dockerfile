# syntax=docker/dockerfile:1.7
# One image: API + realtime gateway + bundled admin dashboard.

# ---- 1. Dashboard -------------------------------------------------------------------
FROM node:20-alpine AS dashboard
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- 2. Backend -----------------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /src
COPY pom.xml .
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:go-offline
COPY src ./src
COPY --from=dashboard /app/dist ./src/main/resources/static
RUN --mount=type=cache,target=/root/.m2 mvn -B -q -DskipTests package

# ---- 3. Runtime -----------------------------------------------------------------------
FROM eclipse-temurin:21-jre
RUN groupadd --system chat && useradd --system --gid chat --uid 10001 chat \
    && mkdir -p /app/data && chown -R chat:chat /app
WORKDIR /app
COPY --from=backend /src/target/chat-saas-*.jar app.jar
USER chat
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
VOLUME ["/app/data"]
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
