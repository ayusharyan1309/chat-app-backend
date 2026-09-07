# Chat SaaS Platform

A multi-tenant SaaS chat platform built with Spring Boot, supporting configurable databases, features, and branding per tenant.

## Features

- **Multi-Tenant Architecture**: Complete data isolation between tenants
- **Configurable Database**: Support for MySQL, PostgreSQL, H2, and MongoDB
- **Feature Flags**: Enable/disable features per tenant at runtime
- **Subscription Management**: Built-in billing and plan management
- **White-Labeling**: Custom branding and theming per tenant
- **Scalable**: Designed for horizontal scaling with Kubernetes

## Quick Start

### Prerequisites

- Java 17+
- Maven 3.8+
- MySQL 8.0+ (or Docker)

### Running with Docker

```bash
# Start the platform
docker-compose up -d

# Access the application
# API: http://localhost:8080
# Swagger: http://localhost:8080/swagger-ui.html
# H2 Console (dev): http://localhost:8080/h2-console
```

### Running Locally

```bash
# Clone the repository
git clone https://github.com/your-repo/chat-saas.git
cd chat-saas

# Run with dev profile
mvn spring-boot:run -Dspring-boot.run.profiles=dev

# Or build and run
mvn clean package
java -jar target/chat-saas-1.0.0-SNAPSHOT.jar --spring.profiles.active=dev
```

## API Documentation

Once running, access the Swagger UI:
- **URL**: http://localhost:8080/swagger-ui.html
- **API Docs**: http://localhost:8080/v3/api-docs

## Tenant Management

### Create a New Tenant

```bash
curl -X POST http://localhost:8080/api/admin/tenants \
  -H "Content-Type: application/json" \
  -d '{
    "tenantName": "Acme Corp",
    "displayName": "Acme Corporation",
    "contactEmail": "admin@acme.com",
    "databaseType": "POSTGRESQL",
    "planType": "PRO"
  }'
```

### Enable Features for Tenant

```bash
curl -X PUT http://localhost:8080/api/admin/tenants/1/features \
  -H "Content-Type: application/json" \
  -d '{
    "features": ["core-chat", "video-calls", "ai-translation"]
  }'
```

### Update Tenant Database

```bash
curl -X PUT http://localhost:8080/api/admin/tenants/1/database \
  -H "Content-Type: application/json" \
  -d '{
    "dbHost": "db.acme.com",
    "dbPort": 5432,
    "dbName": "acme_chat",
    "dbUsername": "admin",
    "dbPassword": "secret"
  }'
```

## Tenant-Aware API Usage

All API calls require the `X-Tenant-ID` header:

```bash
# Send a message
curl -X POST http://localhost:8080/api/chat/messages \
  -H "Content-Type: application/json" \
  -H "X-Tenant-ID: acme-corp" \
  -H "Authorization: Bearer <token>" \
  -d '{
    "recipientEmail": "user@acme.com",
    "message": "Hello!"
  }'
```

## Configuration

### Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `DB_MASTER_HOST` | Master database host | localhost |
| `DB_MASTER_PORT` | Master database port | 3306 |
| `DB_MASTER_NAME` | Master database name | chat_saas_master |
| `DB_MASTER_USERNAME` | Master DB username | root |
| `DB_MASTER_PASSWORD` | Master DB password | - |
| `REDIS_HOST` | Redis host | localhost |
| `REDIS_PORT` | Redis port | 6379 |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka servers | localhost:9092 |

### Subscription Plans

| Plan | Price | Users | Storage | Messages/Day |
|------|-------|-------|---------|--------------|
| Free | $0 | 5 | 1GB | 100 |
| Basic | $9.99/mo | 50 | 10GB | 5,000 |
| Pro | $29.99/mo | 200 | 50GB | 50,000 |
| Enterprise | Custom | 1,000+ | 200GB+ | 100,000+ |

## Architecture

See [ARCHITECTURE.md](ARCHITECTURE.md) for detailed architecture documentation.

### Project Structure

```
src/main/java/com/ayush/chat/
├── admin/           # Admin API endpoints
├── chat/            # Core chat functionality
├── config/          # Configuration classes
├── kafka/           # Kafka integration
├── model/           # JPA entities
├── repository/      # Data repositories
├── saas/            # SaaS-specific code
│   ├── config/      # Dynamic database config
│   ├── feature/     # Feature flag system
│   ├── subscription/# Billing & plans
│   └── tenant/      # Multi-tenant support
├── security/        # Authentication
├── service/         # Business logic
└── websocket/       # WebSocket handlers
```

## Development

### Adding a New Feature Module

1. Add enum to `FeatureModule.java`:
```java
NEW_MODULE("new-module", "Description", false);
```

2. Annotate your service:
```java
@RequiresFeature(FeatureModule.NEW_MODULE)
public void newFeature() { ... }
```

3. Enable via API:
```bash
PUT /api/admin/tenants/{id}/features
{"features": ["core-chat", "new-module"]}
```

### Running Tests

```bash
# Unit tests
mvn test

# Integration tests
mvn verify -P integration
```

## Deployment

### Docker

```bash
docker build -t chat-saas .
docker run -p 8080:8080 chat-saas
```

### Kubernetes

```bash
kubectl apply -f k8s/
```

### AWS/Azure/GCP

See deployment guides in `/docs/deployment/`

## License

MIT License - see [LICENSE](LICENSE) for details
