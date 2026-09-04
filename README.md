# 💬 Chat App Backend

A production-grade real-time chat application backend built with Spring Boot, WebSocket (STOMP), and Firebase Authentication. Supports real-time messaging, typing indicators, read receipts, file sharing via S3, and event-driven architecture with Apache Kafka.

---

## ✨ Features

- 🔌 **Real-time WebSocket Messaging** — STOMP protocol for instant message delivery
- 🔐 **Firebase Authentication** — Secure phone/email-based auth with JWT tokens
- 📩 **Typing Indicators** — Real-time "typing..." status for conversations
- ✅ **Read Receipts** — Track when messages are seen
- 🚫 **Block/Unblock Users** — Full user blocking support
- 📎 **File Sharing** — Upload and share files via AWS S3
- 📱 **QR Code Generation** — Generate QR codes for user profiles
- 📊 **Structured Logging** — Custom logger for audit trails
- 🗄️ **MySQL + Liquibase** — Database with version-controlled migrations
- 🔐 **Jasypt Encryption** — Encrypted sensitive configuration values
- 🐦 **Apache Kafka** — Event-driven messaging for scalable message processing
- 🏗️ **Spring Security** — Role-based access control

---

## 🏗️ Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                   Chat App Backend                               │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────┐    ┌────────────────┐    ┌──────────────┐   │
│  │   Client      │◄──▶│  WebSocket     │◄──▶│  Message     │   │
│  │   (Mobile/    │    │  STOMP Server  │    │  Service     │   │
│  │    Web)       │    └────────────────┘    └──────┬───────┘   │
│  └──────┬───────┘                                  │           │
│         │                                         │           │
│         ▼                                         ▼           │
│  ┌──────────────┐    ┌────────────────┐    ┌──────────────┐   │
│  │   REST API    │    │  Firebase Auth  │    │  Apache Kafka│   │
│  │   Controllers │    │  (JWT Tokens)  │    │  (Events)    │   │
│  └──────┬───────┘    └────────────────┘    └──────┬───────┘   │
│         │                                         │           │
│         ▼                                         ▼           │
│  ┌──────────────┐    ┌────────────────┐    ┌──────────────┐   │
│  │   MySQL       │    │  AWS S3         │    │  Liquibase   │   │
│  │   Database    │    │  (File Storage) │    │  (Migrations)│   │
│  └──────────────┘    └────────────────┘    └──────────────┘   │
└─────────────────────────────────────────────────────────────────┘
```

---

## 🚀 Quick Start

### Prerequisites

- Java 17+
- MySQL 8.0+
- Firebase project with Authentication enabled
- AWS S3 bucket (for file storage)
- Apache Kafka (optional, for event-driven messaging)

### Setup

```bash
# Clone the repo
git clone https://github.com/ayusharyan1309/chat-app-backend.git
cd chat-app-backend

# Configure database
# Edit src/main/resources/application.properties

# Run database migrations (Liquibase)
mvn spring-boot:run
```

### Configuration

```properties
# Database
spring.datasource.url=jdbc:mysql://localhost:3306/chat_db
spring.datasource.username=root
spring.datasource.password=your_password

# Firebase
firebase.config.path=firebase-service-account.json

# AWS S3
aws.access-key-id=your_access_key
aws.secret-access-key=your_secret_key
aws.s3.bucket=your_bucket_name
```

---

## 📦 Project Structure

```
chat-app-backend/
├── src/main/java/com/ayush/chat/
│   ├── ChatApplication.java          # Entry point
│   ├── config/                       # Spring configuration classes
│   ├── constant/                     # Application constants
│   ├── controller/chat/
│   │   └── ChatController.java       # REST + WebSocket endpoints
│   ├── dto/                          # Data Transfer Objects
│   │   └── request/chat/             # Chat request DTOs
│   ├── exception/                    # Custom exception handling
│   ├── kafka/kafka/                  # Kafka producers/consumers
│   ├── model/
│   │   ├── User.java                 # User entity
│   │   ├── Role.java                 # User roles
│   │   ├── chat/                     # Chat-related entities
│   │   └── logger/                   # Log entities
│   ├── repository/                   # JPA repositories
│   ├── security/
│   │   └── ChatPrincipal.java        # Security principal
│   ├── service/
│   │   ├── chat/                     # Chat business logic
│   │   ├── LoggerService.java        # Structured logging
│   │   ├── STExecutorService.java    # Async executor
│   │   ├── UserService.java          # User service interface
│   │   └── UserServiceImpl.java      # User service implementation
│   ├── util/
│   │   └── ThreadMemory.java         # Thread-local storage
│   └── websocket/
│       ├── ChatSubscriptionEventListener.java
│       └── ChatSubscriptionRegistry.java
├── src/main/resources/
│   ├── application.properties
│   └── db/changelog/                 # Liquibase migrations
├── pom.xml
└── mvnw / mvnw.cmd
```

---

## 📊 API Endpoints

### WebSocket Topics

| Endpoint | Description |
|----------|-------------|
| `/chat.send` | Send a new message |
| `/chat.typing` | Typing indicator |
| `/chat/read-receipt` | Read receipt |

### REST Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/chat/unread-messages` | Get unread messages count |
| `POST` | `/chat/mark-messages-read` | Mark messages as read |
| `GET` | `/chat/conversation/with/{userId}` | Get conversation with user |
| `POST` | `/chat/conversation/{id}/accept` | Accept conversation request |
| `GET` | `/chat/conversation/{id}/messages` | Get paginated messages |
| `POST` | `/chat/conversation/{id}/block` | Block user |
| `POST` | `/chat/conversation/{id}/unblock` | Unblock user |
| `GET` | `/chat/friends-chats` | Get all friend chats |

---

## 🔐 Authentication

Uses **Firebase Authentication** with JWT tokens:

```java
// WebSocket (STOMP with SockJS)
@MessageMapping("/chat.send")
public void sendMessage(@Payload ChatMessageDto dto, ChatPrincipal principal) {
    // principal is auto-resolved from Firebase token
}

// REST API (Bearer token)
@GetMapping("/chat/unread-messages")
public ResponseEntity<?> getUnreadMessages(@RequestHeader("Authorization") String authHeader) {
    // Token verified via Firebase Admin SDK
}
```

---

## 🛠️ Tech Stack

| Technology | Purpose |
|------------|---------|
| Spring Boot 3.5 | Core framework |
| Spring WebSocket | Real-time messaging (STOMP) |
| Spring Security | Authentication & authorization |
| Firebase Auth | Phone/email-based user auth |
| MySQL | Primary database |
| Liquibase | Database migrations |
| Apache Kafka | Event-driven messaging |
| AWS S3 | File/image storage |
| Jasypt | Encrypted configuration |
| Lombok | Boilerplate reduction |
| ZXing | QR code generation |
| BCrypt | Password hashing |

---

## 📋 Database Schema

Managed by **Liquibase** — automatic migrations on startup:

```sql
-- Users table
CREATE TABLE users (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    email VARCHAR(255) UNIQUE NOT NULL,
    mobile VARCHAR(20),
    name VARCHAR(100),
    profile_image VARCHAR(500),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Conversations
CREATE TABLE conversations (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    type ENUM('DIRECT', 'GROUP'),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Messages
CREATE TABLE messages (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    conversation_id BIGINT,
    sender_id BIGINT,
    content TEXT,
    type ENUM('TEXT', 'IMAGE', 'FILE'),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (conversation_id) REFERENCES conversations(id)
);
```

---

## 🚀 Deployment

### Docker

```dockerfile
FROM eclipse-temurin:17-jre-jammy
COPY target/chat-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

### Environment Variables

| Variable | Description |
|----------|-------------|
| `SPRING_DATASOURCE_URL` | MySQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | Database username |
| `SPRING_DATASOURCE_PASSWORD` | Database password |
| `FIREBASE_CONFIG_PATH` | Path to Firebase service account JSON |
| `AWS_ACCESS_KEY_ID` | AWS access key |
| `AWS_SECRET_ACCESS_KEY` | AWS secret key |
| `AWS_S3_BUCKET` | S3 bucket name |

---

## 📄 License

MIT
