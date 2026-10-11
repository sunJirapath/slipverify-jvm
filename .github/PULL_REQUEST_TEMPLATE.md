## What and why

<!-- One or two sentences. Link the issue if there is one. -->

## Checklist

- [ ] `./gradlew check` passes locally (format, detekt, tests, apiCheck, coverage gate, Java example)
- [ ] New or changed behavior has tests; a bug fix has a test in `RegressionTest` that failed before the fix
- [ ] Test vectors come from a public source and contain no real personal or account data
- [ ] Public API change? `./gradlew apiDump` was run and the `*/api/*.api` files are updated and the change is listed under `[Unreleased]` in `CHANGELOG.md`
- [ ] New or changed provider behaviour? Cite the provider doc page and mark unclear fields `TODO(verify)` in `docs/RESEARCH.md`
- [ ] No real slips, names, account numbers or API keys in tests, fixtures or logs
- [ ] No new runtime dependency (the Kotlin stdlib is the only one)
- [ ] Commits follow Conventional Commits; formatting-only changes are in their own commit
