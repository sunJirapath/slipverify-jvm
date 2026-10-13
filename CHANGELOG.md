# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/) once it reaches 1.0.

## [Unreleased]

## [0.1.0] - 2026-10-13

First release.

### Added

- `core`: `SlipVerifier` (builder, Java-friendly), `SlipRequest`, `SlipVerificationResult`, `ReasonCode`,
  `ReceiverRule` (tolerant of masked accounts and partial Thai/English names), `SlipProvider`, `QrExtractor`,
  `DuplicateStore` and an in-memory implementation. Offline parsing through promptparse-jvm with `CRC_AUTOFIXED`
  reporting. An offline parse never yields `VALID`. Duplicate store failures fail closed (`DUPLICATE_STORE_ERROR`).
- `image-zxing`: base64 (raw, data URL, whitespace, URL-safe) → PNG/JPEG → slip QR. Limits on encoded size, pixel
  dimensions (read from the header) and time; multi-QR handling that prefers the slip QR over promo QRs; retries with
  downscale, upscale, contrast, crops and rotations. HEIC and WebP are refused with `UNSUPPORTED_IMAGE_FORMAT`.
- `provider-common`, `provider-easyslip` (bank slips by payload, TrueMoney by image), `provider-slipok` (bank slips
  by payload).
- `store-redis`: `SET NX PX` duplicate store on Lettuce.
- `spring-boot-starter`: auto-configuration, `slipverify.*` properties, provider fallback chain with ordering,
  Micrometer metrics, opt-in example REST controller with a request-size limit and generic error bodies.
- Spending and duplicate controls for callers that pay per lookup (found while adopting the library in a POS):
  - `KnownSlips` (`SlipVerifier.Builder.knownSlips`): the caller's own records are consulted after the offline parse
    and **before any provider**, so a re-scanned slip is refused (`INVALID` + `DUPLICATE`) without a billed call. A
    failing lookup fails closed (`ERROR` + `DUPLICATE_STORE_ERROR`).
  - `QuotaGuard` (`SlipVerifier.Builder.quotaGuard`): `tryReserve` before each provider call and `settle` afterwards
    with the provider's reported `Charge` (`YES` / `NO` / `UNKNOWN`). A refused reservation skips that provider
    (`QUOTA_CAPPED`, status `ERROR` when nobody could be asked); a guard that throws counts as "cap reached".
  - `ProviderOutcome.charge`. `provider-slipok` reports it from the SlipOK guide v1.13 quota rules: confirmed slips
    and amount/receiver mismatches are `YES`; not-found, bank outage, pending, auth, package and input errors,
    repeats (with `log`) and refused connections are `NO`; timeouts, 5xx and unknown codes are `UNKNOWN`.
  - `ProviderHttp`/`HttpResult.notSent` marks requests that provably never left (connection refused, connect timeout).
  - The Spring Boot starter picks up `KnownSlips` and `QuotaGuard` beans.
  - `SlipOkProvider.quota()` reads the package quota (`remaining`, `overQuota`, `endDate`) so a cap can follow the
    provider's own billing period; it never throws and returns `null` on any failure.
- `examples`: plain Java and Spring Boot demos.
- Build and CI: Gradle KTS + version catalog, explicit API, binary-compatibility validator, Kover (90% gate), detekt,
  Spotless, CI matrix on JDK 11/17/21/25, CodeQL, tag-triggered release workflow.

### Fixed

- `gradlew` and the release scripts are executable in git (CI failed with exit code 126 on Linux).
