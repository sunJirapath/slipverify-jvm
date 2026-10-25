# Fix PR 12 HTTP cancellation fixture

Date: 2026-10-25 UTC

## Outcome

Fixed the intermittent JDK 25 HttpDeadlineTest failure by accepting peer socket reset as well as EOF after cancellation. Socket timeouts still fail. Updated the PR branch with current main; production cancellation, deadlines, and all acceptance assertions remain unchanged.

## Verification

Gradle check koverXmlReport with strict dependency verification passed for -PtestJdk=25 and -PtestJdk=11 (Spring minimum JDK 17); 194 tests in the reports, zero failures/errors/skips. Formatting, detekt, API validation, coverage gates and Java/Spring examples passed. Live provider calls not run; fresh remote CI pending.

## Decision

No durable decision

## Next

Verify fresh PR 12 CI on the pushed commit, then merge as requested.
