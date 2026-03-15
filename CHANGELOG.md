# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.1.0] - Unreleased

### Added

- **l402-core**: Macaroon V2 binary serialization/deserialization, byte-level compatible with Go `go-macaroon`
- **l402-core**: HMAC-SHA256 crypto chain with key derivation (`HMAC-SHA256(key="macaroons-key-generator", data=rootKey)`)
- **l402-core**: Macaroon identifier layout: `[version:2B BE][payment_hash:32B][token_id:32B]` (66 bytes)
- **l402-core**: First-party caveat support with built-in verifiers (service, capabilities, expiry)
- **l402-core**: Capabilities caveat verifier for fine-grained access control
- **l402-core**: Monotonic restriction validation for caveats
- **l402-core**: Constant-time equality checks for all secret comparisons (XOR accumulation)
- **l402-core**: `SensitiveBytes` wrapper for root key zeroization on close
- **l402-core**: `RootKeyStore` implementations: file-based (with caching/eviction) and in-memory
- **l402-core**: `InMemoryCredentialStore` with configurable max size and eviction policies
- **l402-core**: L402 protocol flow: 402 challenge issuance and credential validation with preimage verification
- **l402-core**: Multi-token `Authorization` header parsing
- **l402-core**: LSAT backward compatibility in header parsing
- **l402-lightning-lnd**: LND gRPC Lightning backend with invoice creation and settlement verification
- **l402-lightning-lnbits**: LNbits REST Lightning backend with invoice creation and settlement verification
- **l402-spring-autoconfigure**: Spring Boot auto-configuration for all L402 components
- **l402-spring-autoconfigure**: `@L402Protected` annotation for declarative endpoint protection
- **l402-spring-autoconfigure**: Servlet filter for L402 challenge/validation lifecycle
- **l402-spring-autoconfigure**: Pluggable `L402PricingStrategy` for dynamic per-request pricing
- **l402-spring-autoconfigure**: Credential caching with Caffeine-backed store and dynamic TTL from `valid_until` caveats
- **l402-spring-autoconfigure**: `CachingLightningBackendWrapper` for health check result caching
- **l402-spring-autoconfigure**: `TokenBucketRateLimiter` for rate-limiting challenge issuance
- **l402-spring-autoconfigure**: Micrometer metrics integration (challenge count, validation count, latency)
- **l402-spring-autoconfigure**: Spring Boot Actuator health indicator (`L402LightningHealthIndicator`)
- **l402-spring-autoconfigure**: Test mode with auto-settle invoices (blocked in `prod` profiles)
- **l402-spring-autoconfigure**: IDE autocomplete via `additional-spring-configuration-metadata.json`
- **l402-spring-security**: `L402AuthenticationProvider` for Spring Security integration
- **l402-spring-security**: `L402AuthenticationFilter` for servlet-based authentication
- **l402-spring-security**: `L402AuthenticationToken` for the Spring Security authentication model
- **l402-spring-boot-starter**: Dependency aggregator module for single-dependency adoption
- **l402-example-app**: Reference application demonstrating dynamic pricing and protected endpoints
- Docker support: `Dockerfile` and `docker-compose.yml` for containerized deployment
- CI/CD: GitHub Actions workflows for CI, release (Sonatype staging), and snapshot publishing
- CI/CD: Dependabot configuration for automated dependency updates
- CI/CD: CodeQL analysis workflow for security scanning
- Configuration properties under `l402.*` prefix with sensible defaults

### Security

- Fail-closed design: Lightning backend unreachable returns 503, never 200
- All secret comparisons use constant-time XOR accumulation (never `Arrays.equals`)
- Root keys wrapped in `SensitiveBytes` with explicit zeroization
- Macaroon values never logged in full; only token IDs appear in logs
- `FileBasedRootKeyStore` returns defensive copies of root keys

### Fixed

- Unknown caveat handling: skip unknown caveats per specification instead of rejecting
- `WWW-Authenticate` header format corrected to `L402 version="0", token=`
- `MacaroonSerializer` validation for field types and lengths

[unreleased]: https://github.com/greenharborlabs/spring-boot-starter-l402/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/greenharborlabs/spring-boot-starter-l402/releases/tag/v0.1.0
