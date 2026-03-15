# Release Checklist

Step-by-step process for publishing a new release of `spring-boot-starter-l402`.

## Prerequisites

- GPG key configured for signing artifacts
- Sonatype OSSRH credentials in `~/.gradle/gradle.properties` or environment variables
- Write access to the GitHub repository
- Access to an LND node and LNbits instance for integration testing

## Release Steps

### 1. Run the full test suite

```bash
./gradlew clean build
```

All modules must pass. Do not proceed if any test fails.

### 2. Run the integration test playbook (LND + LNbits)

Verify end-to-end behavior against real Lightning backends:

- Start the Docker Compose environment (`docker-compose up`)
- Run the example app against LND backend and confirm 402 challenge/payment/access flow
- Run the example app against LNbits backend and confirm 402 challenge/payment/access flow
- Verify health check endpoint reports healthy status for both backends
- Tear down the environment

### 3. Update CHANGELOG.md

- Move items from `[Unreleased]` into a new version section: `[X.Y.Z] - YYYY-MM-DD`
- Add a comparison link at the bottom of the file
- Review entries for accuracy and completeness

### 4. Update gradle.properties version

Remove the `-SNAPSHOT` suffix:

```properties
# Before
version=0.1.0-SNAPSHOT

# After
version=0.1.0
```

### 5. Commit and tag

```bash
git add gradle.properties CHANGELOG.md
git commit -m "Release v0.1.0"
git tag -a v0.1.0 -m "Release v0.1.0"
```

### 6. Push the tag to trigger the release workflow

```bash
git push origin main
git push origin v0.1.0
```

The `release.yml` GitHub Actions workflow will:
- Build all modules
- Run the test suite
- Publish artifacts to Sonatype OSSRH staging
- Close and release the staging repository to Maven Central

### 7. Verify artifacts on Maven Central

- Check [Maven Central](https://central.sonatype.com/) for the published artifacts
- Confirm all modules are present:
  - `com.greenharborlabs:l402-core`
  - `com.greenharborlabs:l402-lightning-lnd`
  - `com.greenharborlabs:l402-lightning-lnbits`
  - `com.greenharborlabs:l402-spring-autoconfigure`
  - `com.greenharborlabs:l402-spring-security`
  - `com.greenharborlabs:l402-spring-boot-starter`
- Verify POM metadata, signatures, and javadoc/sources JARs are attached
- Note: Maven Central indexing can take 30 minutes to several hours

### 8. Bump to the next SNAPSHOT version

```properties
# gradle.properties
version=0.2.0-SNAPSHOT
```

```bash
git add gradle.properties
git commit -m "Prepare next development iteration (0.2.0-SNAPSHOT)"
git push origin main
```

### 9. Announce the release

- Create a GitHub Release from the tag with release notes (copy from CHANGELOG.md)
- Post to relevant channels (project README badge will update automatically from Maven Central)

## Rollback

If a problem is discovered after publishing:

1. **Before Maven Central sync**: Drop the Sonatype staging repository via the [Sonatype UI](https://s01.oss.sonatype.org/)
2. **After Maven Central sync**: Maven Central artifacts are immutable. Publish a patch release (e.g., `0.1.1`) with the fix.
