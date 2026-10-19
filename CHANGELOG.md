# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/) once it reaches 1.0.

## [Unreleased]

### Added

- Optional `ReservationQuotaGuard` / `QuotaReservation` API to associate each provider call with its own quota refund.

### Fixed

- Preserve Thai combining vowels and tone marks when comparing receiver names, so distinct names cannot collapse.
- Settle built-in quota receipts only against their original minute and billing period, including out-of-order calls.
  `SlipVerifier` refunds free calls accurately. Direct legacy `ConfiguredQuotaGuard.tryReserve` / provider-only
  `settle` calls now conservatively retain attempts, including free answers; use `reserve` and settle its receipt
  for refunds. Existing custom `QuotaGuard` implementations remain source and binary compatible.
- Back off Micrometer defaults when the application supplies its own `SlipMetrics` bean.
- Enforce HTTP request deadlines through response-body completion and cancel stalled subscriptions on JDK 11+.
- Reject malformed comma grouping in provider amount strings instead of silently changing the monetary value.
- Read EasySlip proxy-only receiving identifiers, while preferring bank accounts when both are returned. The
  existing untyped receiver suffix rule also applies to proxy identifiers; bank and proxy suffixes are not interchangeable.
- Reject impossible billing dates instead of silently clamping them to a different cutoff day.

## [0.1.2] - 2026-10-13

### Changed

- **Maven group renamed** from `io.github.sunjirapath` to `io.github.sunjirapath.slipverify`; artifact ids are unchanged. Java/Kotlin package names are unchanged. 0.1.1 remains on Maven Central under the old group.

## [0.1.1] - 2026-10-13

### Added

- `slipverify.<provider>.bill-cutoff` (day of month or next billing date) and `limit.max-per-cycle` /
  `limit.max-per-minute` per provider, enforced by the new `ConfiguredQuotaGuard`, `BillingCycle` and `ProviderLimit`.
- `slipverify.slipok.url` accepts the full endpoint including the branch id.
- `slipverify.bill-zone` (default `Asia/Bangkok`).

### Changed

- **Breaking for the starter:** `slipverify-spring-boot-starter` now targets Spring Boot 4.1 (was 3.5). Spring Boot 3
  applications should use `slipverify-core` and a provider module directly.

### Fixed

- Reject nonpositive provider-confirmed amounts before duplicate marking.
- Isolate metrics callback failures from verification and provider fallback.
- Fail startup for unknown duplicate-store settings instead of falling back to memory.
- Shut down owned Redis clients on connection failure and when connection close fails.
- Verify starter behavior with optional provider, Redis, Micrometer and web integrations absent.
- Pin the Gradle distribution checksum and enable native SHA-256 dependency verification.
- Update README compatibility/security badges and clarify unpublished artifact status.

- Require provider-confirmed transfer identity to match the submitted QR before accepting or marking a slip;
  missing references fail closed, and mismatches return `TRANSACTION_MISMATCH`.
- Bound image scans to four workers and eight queued jobs per JVM (`IMAGE_PROCESSING_BUSY` on saturation), remove
  cancelled queued work, and cap all image variants before allocation.
- Cancel provider response downloads above 2,000,000 bytes, including chunked responses.
- Throttle large in-memory duplicate-store cleanup to once per minute while preserving atomic expiry checks.
- Keep Spring starter tests on Java 17+ in the Java 11 CI matrix entry.
- Reject provider timestamps more than two minutes in the future (`TIME_IN_FUTURE`), independently of `maxAge`.
- Synchronize English/Thai release and support documentation; document atomic order credit and durable transfer
  uniqueness in `docs/PAYMENT_ACCEPTANCE.md`.

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
