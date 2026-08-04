# Microservices Project Architecture (Phase 1)

This repository contains a Spring Boot microservice architecture learning project. Phase 1 implements four independent services running over a single PostgreSQL container managed by Docker Compose.

---

## 1. High-Level Architecture Diagram

```mermaid
graph TD
    Client["Client / HTTP Caller"]

    subgraph "Docker Network: backend"
        Sales["sales service<br/>:8083"]
        Catalog["catalog service<br/>:8081"]
        Inventory["inventory service<br/>:8082"]
        Payment["payment service<br/>:8084"]
        
        subgraph "PostgreSQL Container (:5432)"
            SchemaCatalog[("catalog schema<br/>catalog_user")]
            SchemaInventory[("inventory schema<br/>inventory_user")]
            SchemaSales[("sales schema<br/>sales_user")]
            SchemaPayment[("payment schema<br/>payment_user")]
        end
    end

    Client -->|POST /api/orders| Sales
    Client -->|GET /api/products| Catalog
    Client -->|GET /api/stock/{id}| Inventory
    Client -->|GET /api/payments/{id}| Payment

    Sales -->|1. GET /api/products/{id}| Catalog
    Sales -->|2. POST /api/stock/reserve| Inventory
    Sales -->|3. POST /api/payments| Payment
    Sales -->|4. POST /api/stock/confirm<br/>or /api/stock/release| Inventory

    Catalog --> SchemaCatalog
    Inventory --> SchemaInventory
    Sales --> SchemaSales
    Payment --> SchemaPayment
```

---

## 2. Service Boundaries & DB Roles

| Service | Port | Database Schema | Owned Entities / Responsibilities | Database User |
|---|---|---|---|---|
| **`catalog`** | `8081` | `catalog` | Categories, Products (SKU, Name, Description, Price) | `catalog_user` |
| **`inventory`** | `8082` | `inventory` | Stock items, Reservations, Releases, Confirmations, Stock movement audit log | `inventory_user` |
| **`sales`** | `8083` | `sales` | Orders, Order Items (denormalized product names and price snapshots) | `sales_user` |
| **`payment`** | `8084` | `payment` | Payment transactions, status tracking, payment refund/decline simulation | `payment_user` |

> **Security & Boundary Enforcement**: Database users are granted permissions **only** on their own schema inside `infra/postgres/init.sql`. Postgres refuses cross-schema SQL queries at the database level.

---

## 3. Phase 1 Synchronous Order Lifecycle

When placing an order via `POST /api/orders` on `sales`:

1. **Validation & Catalog Fetch**: `sales` makes a `GET` call to `catalog` (`:8081`) for each item to retrieve current authoritative prices and product names.
2. **Order Creation**: `sales` creates a local order record in `sales.orders` with status `PENDING` and calculates the total amount.
3. **Stock Reservation**: `sales` calls `POST /api/stock/reserve` on `inventory` (`:8082`).
   - If stock is insufficient, `inventory` returns `409 Conflict`, and `sales` updates order status to `REJECTED`.
4. **Payment Processing**: `sales` calls `POST /api/payments` on `payment` (`:8084`).
   - Amounts with cents ending in `.13` simulate payment decline (`402 Payment Required`).
   - On payment failure, `sales` sets status to `PAYMENT_FAILED` and executes a compensating transaction call `POST /api/stock/release` to release reserved stock.
5. **Confirmation**: On payment success, `sales` calls `POST /api/stock/confirm` on `inventory` and sets order status to `CONFIRMED`.

---

## 4. Necessary Commands Reference

### Building & Compiling

```bash
# Build all services from project root
for d in catalog inventory payment sales; do (cd "$d" && ./mvnw clean compile); done

# Package a specific service into a JAR
cd catalog && ./mvnw clean package -DskipTests
```

### Docker Compose Management

```bash
# Start all containers in background and build images
docker compose up -d --build

# Check status of containers
docker compose ps

# View logs for all services or a specific service
docker compose logs -f
docker compose logs -f sales

# Stop containers
docker compose stop

# Stop containers and remove volumes (clean database reset)
docker compose down -v
```

### Database Seeding

```bash
# Seed initial data (categories, products, stock items) into Postgres
docker compose exec -T postgres psql -U postgres -d microservices < infra/postgres/seed.sql
```

### Actuator Health Checks

```bash
# Verify health endpoints across all 4 services
curl -s http://localhost:8081/actuator/health
curl -s http://localhost:8082/actuator/health
curl -s http://localhost:8083/actuator/health
curl -s http://localhost:8084/actuator/health
```

### REST API Usage & Testing Commands

```bash
# 1. Fetch products list from Catalog Service
curl -s http://localhost:8081/api/products

# 2. Check stock level for Product 1 from Inventory Service
curl -s http://localhost:8082/api/stock/1

# 3. Place a successful order via Sales Service
curl -i -X POST http://localhost:8083/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": 100,
    "items": [{"productId": 1, "quantity": 2}],
    "paymentMethod": "CARD"
  }'

# 4. Check stock movements audit trail for Product 1
curl -s http://localhost:8082/api/stock/1/movements

# 5. Out-of-Stock Test (Returns HTTP 409 Conflict)
curl -i -X POST http://localhost:8083/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": 100,
    "items": [{"productId": 10, "quantity": 100}],
    "paymentMethod": "CARD"
  }'

# 6. Payment Decline & Compensating Release Test (Product with .13 price returns HTTP 402)
curl -i -X POST http://localhost:8083/api/orders \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": 100,
    "items": [{"productId": 10, "quantity": 1}],
    "paymentMethod": "CARD"
  }'
```
