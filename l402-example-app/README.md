# l402-example-app

A reference Spring Boot application demonstrating how to use `spring-boot-starter-l402` to protect API endpoints with Lightning payments.

This application runs in **test mode** by default, so no real Lightning node is required.

---

## Running the Example

Prerequisites: JDK 25.

From the project root:

```bash
./gradlew :l402-example-app:bootRun
```

The application starts on `http://localhost:8080`.

---

## Endpoints

| Method | Path | Protected | Price | Description |
|--------|------|-----------|-------|-------------|
| GET | `/api/v1/health` | No | -- | Health check. Always accessible. |
| GET | `/api/v1/data` | Yes | 10 sats | Returns premium content. Fixed price. |
| POST | `/api/v1/analyze` | Yes | 50+ sats | Content analysis. Dynamic pricing based on request body size. |

---

## Trying the L402 Flow

### 1. Unprotected endpoint

```bash
curl http://localhost:8080/api/v1/health
```

```json
{"status": "ok"}
```

### 2. Protected endpoint without credentials

```bash
curl -i http://localhost:8080/api/v1/data
```

Returns HTTP 402 with a `WWW-Authenticate` header containing a macaroon and Lightning invoice:

```
HTTP/1.1 402 Payment Required
WWW-Authenticate: L402 macaroon="...", invoice="lnbc..."
Content-Type: application/json

{"code": 402, "message": "Payment required", "price_sats": 10, ...}
```

### 3. Protected endpoint with credentials

In test mode, the `TestModeLightningBackend` treats all invoices as settled. Extract the macaroon from the 402 response and present it with a dummy preimage:

```bash
curl -H "Authorization: L402 <macaroon>:<preimage_hex>" \
     http://localhost:8080/api/v1/data
```

On success:

```json
{"data": "premium content", "timestamp": "2026-03-10T12:00:00Z"}
```

### 4. Dynamic pricing

The `/api/v1/analyze` endpoint uses `AnalysisPricingStrategy`, which charges the base price (50 sats) for requests up to 1 KB and adds 1 sat per 100 bytes beyond that.

```bash
curl -i -X POST \
     -H "Content-Type: application/json" \
     -d '{"content": "analyze this text"}' \
     http://localhost:8080/api/v1/analyze
```

---

## Configuration

The example uses the following configuration (`src/main/resources/application.yml`):

```yaml
spring:
  application:
    name: l402-example-app

server:
  port: 8080

l402:
  enabled: true
  test-mode: true
  service-name: example-api
```

To connect to a real Lightning backend, disable test mode and configure one of the backends. See the [root README](../README.md#lightning-backend-setup) for details.

---

## Project Structure

```
l402-example-app/
  src/main/java/com/greenharborlabs/l402/example/
    ExampleApplication.java         Main class
    ExampleController.java          REST endpoints with @L402Protected
    AnalysisPricingStrategy.java    Dynamic pricing implementation
  src/main/resources/
    application.yml                 Configuration
  src/test/java/
    ExampleAppIntegrationTest.java  Integration tests
```
