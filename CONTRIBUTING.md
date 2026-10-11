# Contributing

Thanks for helping. This library handles personal data (Thai transfer slips), so changes should stay small, well
tested and privacy-conscious.

## Setup

You need a JDK 21 to run Gradle (any JDK 17+ works for compiling; the build downloads a JDK 17 toolchain if it is
missing). Then:

```bash
git clone https://github.com/sunJirapath/slipverify-jvm
cd slipverify-jvm
./gradlew check
```

`./gradlew check` is the single entry point, locally and in CI. It runs, per module:

| Task | What it does |
|---|---|
| `spotlessCheck` | ktlint (Kotlin, Gradle KTS) and google-java-format (Java examples) |
| `detekt` | static analysis, config in `config/detekt/detekt.yml` |
| `test` | JUnit 5, jqwik property tests, WireMock provider tests |
| `apiCheck` | fails if the public API differs from the module's `api/*.api` dump |
| `koverVerify` | fails if line coverage of a module drops under 90% (`store-redis` 60%: `connect()` needs a real Redis) |

Useful extras:

```bash
./gradlew spotlessApply                 # fix formatting
./gradlew apiDump                       # accept an intentional public API change (review the diff!)
./gradlew check -PtestJdk=11            # run the tests on another JDK (11, 17, 21, 25)
./gradlew :store-redis:test -PincludeLive   # tests tagged "live" (needs REDIS_URL, provider keys)
```

Rules that CI enforces: formatting, zero detekt findings, zero compiler warnings, no public API change without an
updated `.api` file, `core` depends on `promptparse` only.

## Test data: no real slips, ever

- Build slips with `SlipVerify.build(...)` / `buildTrueMoney(...)` and render them with `SlipImages` (Java2D + ZXing).
- Provider fixtures use the **field names from the provider's documentation** and invented values. Cite the doc
  page in a comment. Never paste a real response: names, account numbers and references are personal data.
- If a provider field is unclear, do not guess: mark it `TODO(verify)` in code and in `docs/RESEARCH.md`.
- Live tests (`@Tag("live")`) read keys from the environment and are excluded unless `-PincludeLive` is set.

## Adding a provider

1. Read the provider's **current official docs**; record auth, endpoints, errors, limits and ToS in `docs/RESEARCH.md`.
2. Create `provider-<name>` implementing `SlipProvider`, using `provider-common` (`ProviderHttp`, `Json`).
3. Map every documented error to a `ProviderOutcome`: timeouts and 5xx → `unavailable`, other 4xx → `rejected`,
   "slip not found" → `notFound`. Never trust a 200 without an amount.
4. Never log or print API keys, request bodies or response bodies. `toString()` must mask.
5. WireMock tests for: success, not found, mismatch/duplicate (if the API reports them), 4xx, 5xx, timeout,
   malformed JSON.
6. Add the module to `settings.gradle.kts` and `libraryModules` in the root `build.gradle.kts`.

## Commit style

[Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `docs:`, `test:`, `build:`, `ci:`,
`refactor:`. Add `!` for a breaking public API change. One logical change per commit and pull request.

## Reporting a bug

Use the bug report template. **Do not attach real slips or QR payloads**; anonymise or generate them.
For security problems see [SECURITY.md](SECURITY.md) and do not open a public issue.

## Releasing (maintainers)

1. Set `VERSION_NAME` in `gradle.properties` to the release version (no `-SNAPSHOT`).
2. Add a `## [x.y.z] - date` section to `CHANGELOG.md` (the GitHub Release text is taken from it).
3. Merge to `main`, then push the tag `vX.Y.Z`. The `Release` workflow verifies the tag, runs `check`, waits for
   approval of the `release` environment, publishes every module to Maven Central and creates the GitHub Release.
4. Set `VERSION_NAME` to the next `-SNAPSHOT`.

## License

By contributing you agree that your contribution is licensed under the Apache License 2.0.
