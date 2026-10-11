# Security policy

## Reporting a vulnerability

Please **do not open a public issue** for security problems.

Report privately through GitHub Security Advisories:
**Security → Advisories → [Report a vulnerability](https://github.com/sunJirapath/slipverify-jvm/security/advisories/new)**
on this repository. Include the input that triggers it, the affected version and the impact you see.
**Never attach a real slip image, QR payload, account number or API key.**

You should get an acknowledgement within a few days. Fixes are developed in a private advisory fork, released, and
then disclosed with credit to the reporter unless you prefer otherwise.

## What counts

This library takes untrusted images and strings from the internet and calls paid third-party APIs, so the
interesting classes of bug are:

- a crafted image or base64 string that exhausts memory or CPU, or bypasses the size, dimension or time limits
  (decompression bombs, oversized input, hangs);
- a result of `VALID` without a provider confirmation, or a business check (amount, receiver, age, duplicate)
  that can be bypassed;
- an API key, image, QR payload, name or account number appearing in logs, exceptions, `toString()` or an HTTP
  response;
- a duplicate check that can be raced or that accepts a slip it could not check (the verifier must fail closed);
- the example REST controller leaking internals in error responses.

## Supported versions

Only the latest released version receives fixes. The library is pre-1.0.

## Scope notes

- An offline QR parse proves nothing about a payment. Only a provider's confirmation does, and only for the
  provider's documented guarantees.
- Slip data is personal data (PDPA). The library never persists images and masks names and account numbers, but your
  application is responsible for its own logging, retention and access control.
- The example controller ships **without authentication or rate limiting**. Add both before exposing it.
