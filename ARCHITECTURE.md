# SaaS Chat Platform - Architecture Documentation

## Overview

This document describes the multi-tenant SaaS architecture for the Chat Platform, designed to be sold to any client with customizable database, features, and branding.

## Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                              SaaS PLATFORM                                  │
├─────────────────────────────────────────────────────────────────────────────┤
│                                                                             │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐  ┌──────────────┐   │
│  │   Tenant A   │  │   Tenant B   │  │   Tenant C   │  │   Tenant N   │   │
│  │  (MySQL)     │  │  (PostgreSQL)│  │    (H2)      │  │  (MongoDB)   │   │
│  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘  └──────┬───────┘   │
│         │                 │                 │                 │            │
│  ┌──────▼─────────────────▼─────────────────▼─────────────────▼───────┐   │
│  │                    Multi-Tenant Router                              │   │
│  │              (TenantContext + Interceptor)                          │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│         │                                                                  │
│  ┌──────▼──────────────────────────────────────────────────────────────┐   │
│  │                    Feature Flag Engine                               │   │
│  │          (Runtime feature toggles per tenant)                       │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│         │                                                                  │
│  ┌──────▼──────────────────────────────────────────────────────────────┐   │
│  │                    Core Services                                     │   │
│  │  ┌────────────┐  ┌────────────┐  ┌────────────┐  ┌────────────┐   │   │
│  │  │    Chat    │  │    User    │  │    Auth    │  │   Admin    │   │   │
│  │  └────────────┘  └────────────┘  └────────────┘  └────────────┘   │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│         │                                                                  │
│  ┌──────▼──────────────────────────────────────────────────────────────┐   │
│  │                    Message Broker (Kafka/Redis)                     │   │
│  └─────────────────────────────────────────────────────────────────────┘   │
│                                                                             │
└─────────────────────────────────────────────────────────────────────────────┘
```

## Key Components

### 1. Multi-Tenant Architecture

#### Tenant Resolution
- **Header-based**: `X-Tenant-ID` or `X-Tenant-Domain`
- **Domain-based**: Automatic resolution from custom domain
- **JWT-based**: Tenant ID embedded in authentication token

#### Database Isolation
Each tenant can have its own database instance:
- **MySQL** - Default for production
- **PostgreSQL** - Alternative for advanced features
- **H2** - For development and small tenants
- **MongoDB** - For document-based storage needs

### 2. Feature Modules

The platform supports 30+ feature modules that can be enabled/disabled per tenant:

#### Core Modules (Included by Default)
- `core-chat` - Basic chat functionality
- `user-management` - User authentication & management
- `kafka-integration` - Message broker

#### Communication Modules
- `voice-calls` - Voice call support
- `video-calls` - Video call support
- `screen-sharing` - Screen sharing
- `file-sharing` - File sharing
- `media-sharing` - Image/Video sharing

#### Group Features
- `group-chat` - Group chat
- `group-calls` - Conference calls
- `channels` - Channel-based communication

#### Integration Modules
- `redis-caching` - Redis caching layer
- `elasticsearch` - Full-text search
- `sso` - Single Sign-On
- `ldap` - LDAP authentication
- `oauth2` - OAuth2 integration

#### Analytics Modules
- `analytics` - Dashboard analytics
- `message-analytics` - Message insights
- `user-analytics` - User activity

#### Customization Modules
- `custom-theming` - Custom branding
- `white-labeling` - White-label solution
- `custom-emojis` - Custom emoji support

#### AI Modules
- `ai-translation` - AI-powered translation
- `ai-summary` - Message summary
- `ai-moderation` - Content moderation

### 3. Subscription Plans

| Plan | Price | Users | Storage | Messages/Day | Features |
|------|-------|-------|---------|--------------|----------|
| Free | $0 | 5 | 500MB | 100 | Basic chat |
| Basic | $9/mo | 50 | 5GB | 5,000 | Core + Groups |
| Pro | $29/mo | 200 | 25GB | 50,000 | All features |
| Enterprise | Custom | Unlimited | Unlimited | Unlimited | Custom |

### 4. Tenant Configuration

Each tenant can customize:

```yaml
# Branding
display_name: "My Company"
logo_url: "https://example.com/logo.png"
primary_color: "#3498db"
secondary_color: "#2ecc71"

# Database
database_type: POSTGRESQL
db_host: "db.example.com"
db_port: 5432
db_name: "mycompany_chat"

# Limits
max_users: 500
max_storage_gb: 100
max_messages_per_day: 100000
```

## API Endpoints

### Tenant Management (Super Admin)

```
POST   /api/admin/tenants              - Create tenant
GET    /api/admin/tenants              - List tenants
GET    /api/admin/tenants/{id}         - Get tenant details
PUT    /api/admin/tenants/{id}         - Update tenant
DELETE /api/admin/tenants/{id}         - Delete tenant

POST   /api/admin/tenants/{id}/suspend   - Suspend tenant
POST   /api/admin/tenants/{id}/activate  - Activate tenant

PUT    /api/admin/tenants/{id}/features  - Update features
PUT    /api/admin/tenants/{id}/database  - Update database config

GET    /api/admin/tenants/stats        - Get statistics
GET    /api/admin/tenants/database-types - List DB types
GET    /api/admin/tenants/features     - List features
```

### Tenant-Aware Chat API

```
Headers required: X-Tenant-ID: <tenant_identifier>

POST   /api/chat/messages              - Send message
GET    /api/chat/conversations         - List conversations
GET    /api/chat/conversations/{id}    - Get conversation
GET    /api/chat/messages/{convId}     - Get messages

WebSocket: ws://host/ws?tenant=<tenant_id>
```

## Database Schema

### Master Database (Admin)

```sql
-- Tenant registry
saas_tenants
saas_tenant_settings
saas_tenant_features

-- Subscription management
saas_subscription_plans
saas_tenant_subscriptions
```

### Tenant Database (Per-tenant)

```sql
-- Existing tables with tenant_id column
user
roles
conversations
messages
```

## Configuration

### application.yml (Master)

```yaml
spring:
  application:
    name: chat-saas
  
  # Master database
  datasource:
    master:
      url: jdbc:mysql://localhost:3306/chat_saas_master
      username: root
      password: ${DB_MASTER_PASSWORD}
  
  jpa:
    hibernate:
      dialect: org.hibernate.dialect.MySQLDialect
    
# Multi-tenancy
saas:
  multi-tenant:
    enabled: true
    tenant-resolver: header  # header, domain, jwt
    default-plan: free
    
# Feature flags
features:
  enabled: true
  cache-ttl: 300  # 5 minutes

# Subscription
subscription:
  trial-days: 14
  require-payment: false
```

### Environment Variables

```bash
# Master Database
DB_MASTER_HOST=localhost
DB_MASTER_PORT=3306
DB_MASTER_NAME=chat_saas_master
DB_MASTER_USERNAME=root
DB_MASTER_PASSWORD=secret

# Encryption
JASYPT_ENCRYPTOR_PASSWORD=your-secret-key

# Kafka (Optional)
KAFKA_BOOTSTRAP_SERVERS=localhost:9092

# Redis (Optional)
REDIS_HOST=localhost
REDIS_PORT=6379
```

## Deployment

### Docker Compose

```yaml
version: '3.8'
services:
  chat-saas:
    build: .
    ports:
      - "8080:8080"
    environment:
      - DB_MASTER_HOST=mysql-master
      - DB_MASTER_PASSWORD=${DB_PASSWORD}
    depends_on:
      - mysql-master
      - kafka

  mysql-master:
    image: mysql:8.0
    environment:
      - MYSQL_ROOT_PASSWORD=${DB_PASSWORD}
      - MYSQL_DATABASE=chat_saas_master

  kafka:
    image: confluentinc/cp-kafka
    # ...
```

## Development

### Adding a New Feature Module

1. Add enum value to `FeatureModule.java`:
```java
NEW_FEATURE("new-feature", "Description", false);
```

2. Annotate your service method:
```java
@RequiresFeature(FeatureModule.NEW_FEATURE)
public void newFeatureMethod() {
    // ...
}
```

3. Enable for tenant via API:
```bash
PUT /api/admin/tenants/{id}/features
{
  "features": ["core-chat", "new-feature"]
}
```

### Adding New Database Support

1. Add enum value to `DatabaseType.java`:
```java
NEWDB("newdb", "com.newdb.Driver", "jdbc:newdb://%s:%d/%s");
```

2. Add JDBC driver to `pom.xml`

3. Update `DynamicDataSourceConfig` for connection handling

## Security

- Firebase Authentication (existing)
- Per-tenant encryption keys
- Role-based access (SUPER_ADMIN, TENANT_ADMIN, USER)
- API rate limiting per tenant
- Data isolation between tenants

## Monitoring

- Actuator endpoints per tenant
- Prometheus metrics
- Custom dashboards for:
  - Tenant usage
  - Feature adoption
  - Revenue tracking

## Roadmap

### Phase 1 (Current)
- [x] Multi-tenant architecture
- [x] Feature flags
- [x] Subscription management
- [x] Admin API

### Phase 2
- [ ] Self-service tenant onboarding
- [ ] Payment integration (Stripe)
- [ ] Email notifications
- [ ] Audit logging

### Phase 3
- [ ] AI features
- [ ] Advanced analytics
- [ ] Mobile SDK
- [ ] White-label deployment
