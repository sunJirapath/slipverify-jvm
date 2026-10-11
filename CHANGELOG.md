# Changelog

All notable changes to this project are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project aims to follow
[Semantic Versioning](https://semver.org/) once it reaches 1.0.

## [Unreleased]

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
- `examples`: plain Java and Spring Boot demos.
- Build and CI: Gradle KTS + version catalog, explicit API, binary-compatibility validator, Kover (90% gate), detekt,
  Spotless, CI matrix on JDK 11/17/21/25, CodeQL, tag-triggered release workflow (not yet used).
