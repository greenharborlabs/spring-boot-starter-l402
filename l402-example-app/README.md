# l402-example-app

A reference Spring Boot application demonstrating how to use `spring-boot-starter-l402` to protect API endpoints with Lightning payments using the [L402 protocol](https://docs.lightning.engineering/the-lightning-network/l402).

This application runs in **test mode** by default, so no real Lightning node is required.

---

## Prerequisites

- JDK 25

---

## How L402 Works (Quick Overview)

L402 is an HTTP-native payment protocol. When a client requests a protected resource without paying:

1. The server responds with **HTTP 402 Payment Required** and a `WWW-Authenticate` header containing a **macaroon** (an authorization token) and a Lightning **invoice**.
2. The client pays the invoice via the Lightning Network and receives a **preimage** (proof of payment).
3. The client re-requests the resource, presenting the macaroon and preimage together in the `Authorization` header.
4. The server verifies the macaroon signature and checks that `SHA-256(preimage) == paymentHash` embedded in the macaroon. If both check out, access is granted.

---

## How Test Mode Works

When `l402.test-mode=true`, the starter replaces the real Lightning backend (LND, LNbits, etc.) with a `TestModeLightningBackend`. This backend:

- **Creates invoices with random payment hashes.** When the server issues a 402 challenge, the invoice contains a randomly generated 32-byte payment hash and a fake bolt11 string (e.g., `lntb10test7a3b...`). No real Lightning node is contacted.
- **Reports all invoices as settled.** When the validator checks whether an invoice has been paid, the test backend always answers "yes, it's settled" and returns a random preimage.
- **Always reports healthy.** The health check always passes, so the server never returns 503.

To enable end-to-end testing with curl, the test backend includes the preimage in the 402 JSON response as a `test_preimage` field. In a real deployment this field is never present -- the preimage is only obtained by paying the Lightning invoice. See [Step 3](#step-3-complete-the-flow-with-the-test-preimage) for a walkthrough.

### Safety guard

`TestModeLightningBackend` refuses to start if any active Spring profile is `production` or `prod`, throwing an `IllegalStateException` to prevent accidental use in production.

---

## Running the Application

From the **project root** (not from inside `l402-example-app/`):

```bash
./gradlew :l402-example-app:bootRun
```

The `gradlew` wrapper lives in the project root. The `:l402-example-app:` prefix tells Gradle which submodule to run.

The application starts on `http://localhost:8080`.

---

## Endpoints

| Method | Path              | Protected | Price    | Description                              |
|--------|-------------------|-----------|----------|------------------------------------------|
| GET    | `/api/v1/health`  | No        | --       | Health check. Always accessible.         |
| GET    | `/api/v1/data`    | Yes       | 10 sats  | Returns premium content. Fixed price.    |
| POST   | `/api/v1/analyze` | Yes       | 50+ sats | Content analysis. Dynamic pricing.       |

Endpoints are protected by annotating the controller method with `@L402Protected`:

```java
@L402Protected(priceSats = 10)
@GetMapping("/data")
public DataResponse data() { ... }
```

The `L402SecurityFilter` automatically discovers all `@L402Protected` methods at startup and enforces payment for matching requests.

---

## Trying the L402 Flow

### Step 1: Hit the unprotected health endpoint

This confirms the application is running. No authentication is needed.

```bash
curl http://localhost:8080/api/v1/health
```

Expected response (HTTP 200):

```json
{"status":"ok"}
```

### Step 2: Request a protected endpoint without credentials

This triggers the L402 challenge. The server creates a Lightning invoice and a macaroon, then returns them in the `WWW-Authenticate` header.

```bash
curl -i http://localhost:8080/api/v1/data
```

Expected response (HTTP 402):

```
HTTP/1.1 402
WWW-Authenticate: L402 macaroon="AgJ...base64...", invoice="lntb10test7a3b..."
Content-Type: application/json

{"code": 402, "message": "Payment required", "price_sats": 10, "description": "", "invoice": "lntb10test7a3b...", "test_preimage": "a1b2c3...64-hex-chars..."}
```

Notice the `test_preimage` field -- this only appears in test mode. In a real deployment, you would obtain the preimage by paying the Lightning invoice with a wallet.

What happened behind the scenes:

1. The filter matched `GET /api/v1/data` against the endpoint registry and found the `@L402Protected(priceSats = 10)` configuration.
2. It checked the Lightning backend health (test backend always returns healthy).
3. No `Authorization` header was present, so it generated a new root key and token ID.
4. It called `TestModeLightningBackend.createInvoice(10, "")`, which generated a random 32-byte preimage, computed `paymentHash = SHA-256(preimage)`, and returned both.
5. It minted a macaroon containing the payment hash and token ID in its identifier, signed with the root key, and added caveats: `services=example-api:0` and `example-api_valid_until=<expiry-epoch>`.
6. It returned 402 with the base64-encoded macaroon, the bolt11 invoice string, and the test preimage.

### Step 3: Complete the flow with the test preimage

Extract the `macaroon` value from the `WWW-Authenticate` header and the `test_preimage` from the JSON body, then present them together in the `Authorization` header.

The `Authorization` header format is:

```
L402 <base64-encoded-macaroon>:<preimage-as-64-hex-chars>
```

Here's a concrete example using the values from a 402 response:

```bash
# Save the 402 response to parse the macaroon and preimage
RESPONSE=$(curl -s -i http://localhost:8080/api/v1/data)

# Extract the macaroon from the WWW-Authenticate header (between macaroon=" and ")
MACAROON=$(echo "$RESPONSE" | grep -o 'macaroon="[^"]*"' | sed 's/macaroon="//;s/"//')

# Extract the test_preimage from the JSON body
PREIMAGE=$(echo "$RESPONSE" | grep -o '"test_preimage": "[^"]*"' | sed 's/"test_preimage": "//;s/"//')

# Present the credential to access the protected resource
curl -i -H "Authorization: L402 ${MACAROON}:${PREIMAGE}" \
     http://localhost:8080/api/v1/data
```

Or if you already have the values, you can run it directly:

```bash
curl -i -H "Authorization: L402 AgJCAABb976nP3z4iWZflFsXM/tDqgv9UKj0hrMC...:a05d63f6f7993a83e464229215f408a6b79e957679c2bafd67f3d6aa59237467" \
     http://localhost:8080/api/v1/data
```

Example response:

```
HTTP/1.1 200
X-L402-Token-Id: 21b5a136d139351ed4fde56d5583c48fcb9ae33fdb89874d009e83b01f5d86ab
X-L402-Credential-Expires: 2026-03-14T02:46:38.298632Z
Content-Type: application/json

{"data":"premium content","timestamp":"2026-03-14T01:46:38.299234Z"}
```

What the server verified:

1. **Macaroon signature** -- The HMAC-SHA256 chain is intact, proving the macaroon was minted by this server and hasn't been tampered with.
2. **Caveats** -- The `services` caveat matches `example-api` and the `valid_until` timestamp hasn't expired.
3. **Preimage** -- `SHA-256(preimage) == paymentHash` embedded in the macaroon identifier, proving the invoice was paid.

The response headers tell you:

- `X-L402-Token-Id` -- Identifies this credential (useful for debugging).
- `X-L402-Credential-Expires` -- When the credential expires and a new payment will be needed.

Subsequent requests with the same valid credential are served from cache without re-running the full verification.

### Step 4: Dynamic pricing

The `/api/v1/analyze` endpoint uses `AnalysisPricingStrategy`, which charges the base price (50 sats) for request bodies up to 1 KB and adds 1 sat per 100 bytes beyond that.

```bash
curl -i -X POST \
     -H "Content-Type: application/json" \
     -d '{"content": "analyze this text"}' \
     http://localhost:8080/api/v1/analyze
```

Expected response (HTTP 402):

```json
{"code": 402, "message": "Payment required", "price_sats": 50, "description": "", "invoice": "lntb50test...", "test_preimage": "..."}
```

The price is 50 sats because the request body is under 1 KB. A larger payload would increase the price. You can complete this flow the same way as Step 3 -- extract the macaroon and preimage, then present them.

### Step 5: Compare with a real deployment

| Step | Test Mode | Real Deployment |
|------|-----------|-----------------|
| Get challenge | `curl -i /api/v1/data` | Same |
| Obtain preimage | Read `test_preimage` from 402 response | Pay the `invoice` with a Lightning wallet (e.g., Zeus, Phoenix, `lncli payinvoice`) |
| Present credential | `Authorization: L402 <mac>:<preimage>` | Same |
| Validation | Full cryptographic verification | Same |

The only difference is *how you get the preimage*. Everything else -- macaroon minting, signature verification, caveat checking, SHA-256 preimage matching, credential caching -- is identical.

---

## Running the Tests

The integration tests exercise the **complete L402 flow** including credential verification. They mint their own macaroons with known preimage/paymentHash pairs to test the full verification path programmatically.

From the project root:

```bash
./gradlew :l402-example-app:test
```

### What the tests cover

1. **Health endpoint** -- Confirms `GET /api/v1/health` returns 200 without authentication.

2. **402 challenge** -- Confirms `GET /api/v1/data` without credentials returns 402 with a `WWW-Authenticate` header containing `macaroon=` and `invoice=` fields, and a JSON body with `code: 402` and `price_sats: 10`.

3. **Full credential flow** -- The test:
   - Generates a random 32-byte preimage.
   - Computes `paymentHash = SHA-256(preimage)`.
   - Generates a root key from the application's own `RootKeyStore` (using `root-key-store=memory` so the test shares the same store as the filter).
   - Mints a macaroon with an identifier containing the payment hash and token ID, signed with the root key.
   - Builds the `Authorization: L402 <macaroon>:<preimage>` header.
   - Sends the request and asserts HTTP 200, the presence of `X-L402-Token-Id` and `X-L402-Credential-Expires` headers, and the expected JSON body.

   This exercises the full verification path: macaroon signature check, preimage-to-paymentHash SHA-256 match, and credential caching.

4. **Dynamic pricing** -- Confirms `POST /api/v1/analyze` with a small body returns 402 with `price_sats >= 50`.

---

## Running with Docker

From the project root:

```bash
docker compose up --build
```

This builds the example app using a multi-stage Dockerfile (JDK 25 build, JRE 25 runtime) and starts it on port 8080 with test mode enabled and an in-memory root key store.

To stop:

```bash
docker compose down
```

---

## Configuration

The example uses this configuration (`src/main/resources/application.yml`):

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

| Property            | Value         | Effect                                                       |
|---------------------|---------------|--------------------------------------------------------------|
| `l402.enabled`      | `true`        | Activates the L402 filter.                                   |
| `l402.test-mode`    | `true`        | Uses `TestModeLightningBackend` instead of a real node.      |
| `l402.service-name` | `example-api` | Appears in macaroon caveats (e.g., `services=example-api:0`).|

To connect to a real Lightning backend, disable test mode and configure one of the supported backends. See the [root README](../README.md) for details.

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
    ExampleAppIntegrationTest.java  Full L402 flow integration tests
  Dockerfile                        Multi-stage Docker build
```
