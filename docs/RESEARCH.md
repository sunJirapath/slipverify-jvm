# slipverify-jvm — Step 0 research

Researched 2026-10-11 from the public docs linked below. Anything the docs did not state is marked `TODO(verify)`.
No provider field here is invented; fields come from the quoted doc pages.

## 1. promptparse-jvm

- Repo: <https://github.com/sunJirapath/promptparse-jvm>. Maven Central `io.github.sunjirapath:promptparse:0.1.0` **is published** (checked `maven-metadata.xml`; `latest = release = 0.1.0`). Repo `main` is at `0.1.1-SNAPSHOT`.
  → Depend on the Maven artifact. No `includeBuild` needed.
- Slip-related public API (from `core/api/*.api` and `SlipVerify.kt`):

| API | Notes |
|---|---|
| `SlipVerify.parse(payload, crcAutoFix = true): SlipVerifyInfo?` | Standard BOT slip. `null` unless tag-91 CRC valid, root tag `00` has `000001`, bank, ref. |
| `SlipVerify.parseTrueMoney(payload, crcAutoFix = true): TrueMoneySlipVerifyInfo?` | TrueMoney slip (lowercase CRC, no tag 51). |
| `SlipVerifyInfo(sendingBank, transRef)` | Only two fields. **No amount, no time, no names.** |
| `TrueMoneySlipVerifyInfo(eventType, transactionId, date)` | `date` is `DDMMYYYY` string, shape-checked only. |
| `SlipVerify.build / buildTrueMoney` | Used by our tests to make synthetic payloads. |
| `TrueMoney.parse` → `TrueMoneyInfo(mobile, amount, message)` | That is the TrueMoney *payment* QR, not a slip. Not used. |

- CRC auto-fix repairs 1–3 dropped leading hex zeros. **The result types do not say whether a fix happened.**
  To emit `CRC_AUTOFIXED` we call `parse(payload, false)` first; if that is `null` and `parse(payload, true)` is non-null, the CRC was fixed.
- Consequence: an offline parse yields only bank + reference (or TrueMoney event/id/date). Everything else (amount, parties, time) must come from a provider.
- Both parsers never throw.

## 2. Providers

### Comparison

| | EasySlip | SlipOK | RDCW Slip Verify |
|---|---|---|---|
| Docs | [document.easyslip.com](https://document.easyslip.com/en/v2/) | [slipok.com/api-documentation](https://slipok.com/api-documentation/) (doc v1.8, 2024-07-30 — may be stale) | [slip.rdcw.co.th/docs](https://slip.rdcw.co.th/docs) (page itself says parts may be inaccurate) |
| Base URL | `https://api.easyslip.com/v2` | `https://api.slipok.com/api/line/apikey/{BRANCH_ID}` | `https://suba.rdcw.co.th/v2/inquiry` (Notion mirror still shows `/v1/inquiry`) |
| Auth | `Authorization: Bearer <key>`; optional IP whitelist | header `x-authorization: <key>` + branch id in path | HTTP Basic `clientId:clientSecret`; IP whitelist (error 1003) |
| Verify by payload | `POST /verify/bank` JSON `{payload, remark?, matchAccount?, matchAmount?, checkDuplicate?}` (payload 1–128 chars) | `POST` JSON `{data, log?, amount?}` | `POST` JSON `{payload}` |
| Verify by image | `POST /verify/bank` multipart (sub-page `/verify/bank/image`) `TODO(verify)` field names | `POST` multipart `files` (JPG/JPEG/PNG/JFIF/WEBP) | multipart field `file`, or raw body `image/jpeg` / `image/png` |
| Verify by base64 | `/verify/bank/base64` `TODO(verify)` body | — | — |
| Verify by URL | `/verify/bank/url` `TODO(verify)` body | JSON `{url}` (Drive / signed S3 links may fail) | — |
| TrueMoney | `POST /verify/truewallet` — **image / base64 / url only, no payload** (multipart `image`, same optional flags) | not mentioned `TODO(verify)` | not mentioned `TODO(verify)` |
| Success schema | documented (see below) | documented (see below) | **not documented** (only error shape) |
| Error shape | `{success:false, error:{code,message}}`, string codes | `{code, message}` numeric codes, HTTP status not documented | `{code, message}`, **always HTTP 400** |
| Rate limit | HTTP 429 `RATE_LIMIT_EXCEEDED` with `Retry-After`, `X-RateLimit-*`; per-plan limit `TODO(verify)` | not stated | not stated; usage quota → error 1007 |
| Pricing | per-plan monthly quota; prices `TODO(verify)` (not on fetched pages) | package/quota based; third-party README says 100 free/month `TODO(verify)` | prepaid packages valid 30 days, "charged only for real slips"; prices on `/pricing` not shown |
| Duplicate detection | `checkDuplicate:true` → `data.isDuplicate`. v1 returned 400 `duplicate_slip`; v2 behaviour on duplicate is **not documented** | `log:true` enables it → error **1012** ("สลิปซ้ำ … เมื่อ {timestamp}"); duplicates not billed | none documented |
| Amount match | `matchAmount` → `amountInOrder / amountInSlip / isAmountMatched` | `amount` → error 1013 on mismatch | none |
| Receiver match | `matchAccount` against accounts registered in the portal → `matchedAccount` | `log:true` checks receiver against account linked to the branch → error 1014 | none |
| Sandbox | none documented `TODO(verify)` | none documented | none documented |
| ToS on SDKs/redistribution | not fetched `TODO(verify)` (no ToS link found) | no ToS reachable (footer link is `#`) | `/tos`: nothing on SDKs. §5.1 no renting/selling the service to unrelated parties; §5.3 no reverse engineering/copying the *system*; prohibited uses: gambling, tobacco/drugs, weapons, obscene media, crypto trading |

### EasySlip details
- v2 endpoints: `GET /info`, `GET /health`, `POST /verify/bank` (+ `/async`, `/batch`, `GET /verify/bank/jobs/:jobId`), `POST /verify/truewallet`, `GET /banks`, bank-account CRUD. v1 (`/verify`, `/me`) is legacy.
- Bank success: `success, message, data{isDuplicate, rawSlip{payload, transRef, date, countryCode, fee, amount{amount, local{amount,currency}}, sender{bank{id,name,short}, account{name{th,en}, bank{type,account}}}, receiver{bank{…}, account{name{th}, bank{type,account}}}}}`; type defs also list `remark, matchedAccount, amountInOrder, amountInSlip, isAmountMatched` under `data`.
- TrueWallet success: `data{isDuplicate, amountInSlip, rawSlip{transactionId, date, amount, sender{name}, receiver{name, phone}}}`.
- Error codes (HTTP): `MISSING_API_KEY` 401, `INVALID_API_KEY` 401, `BRANCH_INACTIVE` 403, `SERVICE_BANNED` 403, `SERVICE_DELETED` 403, `IP_NOT_ALLOWED` 403, `QUOTA_EXCEEDED` 403, `USER_BANNED` 403, `VALIDATION_ERROR` 400, `URL_PROTOCOL_NOT_ALLOWED` / `URL_INVALID_IP_RANGE` / `IMAGE_URL_UNREACHABLE` / `IMAGE_SIZE_TOO_LARGE` (4 MB) / `INVALID_IMAGE_TYPE` / `INVALID_IMAGE_FORMAT` / `INVALID_BANK_CODE` / `INVALID_EXTRA_VERIFY` / `INVALID_CALLBACK_URL` 400, `SLIP_NOT_FOUND` 404, `SLIP_PENDING` 404 (Bangkok Bank slip < 5 min old), `BANK_ACCOUNT_NOT_FOUND` / `NOT_FOUND` / `JOB_NOT_FOUND` 404, `BANK_ACCOUNT_DUPLICATE` 409, `RATE_LIMIT_EXCEEDED` 429, `API_SERVER_ERROR` / `INTERNAL_SERVER_ERROR` 500.
- Banks: "18+ Thai banks" (marketing page); exact list `TODO(verify)` via `GET /banks`.
- Note: EasySlip v2 `SLIP_NOT_FOUND` is 404 and `SLIP_PENDING` is 404. Our "no retry on 4xx" rule applies, but `SLIP_PENDING` deserves its own reason (see §4).

### SlipOK details
- Success: `success, data{success, message, language, receivingBank, sendingBank, transRef, transDate, transTime, transTimestamp, sender{displayName, name, proxy{type,value}, account{type,value}}, receiver{…same…}, amount, paidLocalAmount, paidLocalCurrency, countryCode, transFeeAmount, ref1, ref2, ref3, toMerchantId}`. Doc notes names "might not be complete — need partial match" and proxy/account values are always masked.
- Errors: 1000 missing data · 1001 branch not found · 1002 bad Authorization header · 1003 package expired · 1004 quota exceeded · 1005 not an image · 1006 invalid image · 1007 no QR · 1008 QR not a payment-verification QR · 1009 bank outage, retry in 15 min (not billed) · 1010 bank requires waiting N min · 1011 QR expired / no such transaction · 1012 duplicate · 1013 amount mismatch · 1014 receiver mismatch. HTTP status per code `TODO(verify)`.
- Quota: `GET …/{BRANCH}/quota` with same header.
- Banks (18): 002 BBL, 004 KBANK, 006 KTB, 011 TTB, 014 SCB, 025 BAY, 069 KKP, 022 CIMBT, 067 TISCO, 024 UOBT, 071 TCD, 073 LHFG, 070 ICBCT, 098 SME, 034 BAAC, 035 EXIM, 030 GSB, 033 GHB.

### RDCW details
- Error codes (all HTTP 400): 1000 missing headers · 1001–1002 invalid auth header · 1003 IP not whitelisted · 1004–1006 invalid payload · 1007 usage exceeded · 1008 subscription expired · 2001/2005 internal · 2002–2004 bank API error · 2006 bank returned no data.
- **Success response schema is not published.** The module cannot be written against docs; it needs a real response from an account (or RDCW support). Until then: `TODO(verify)`, and I would not guess the shape.
- Because every error is HTTP 400, "do not retry on 4xx" cannot work for RDCW; classification must use `code` (2002–2004 = bank-side/transient, 1xxx = client).

### Other providers
- **Slip2GO**: live service (BigSeller integration page), no public API docs found. **check-slip.com**: mentioned by a third-party README only. **Thunder** (`help.thunder.in.th`) authors the EasySlip WooCommerce plugin, not a separate API. None meet "official docs readable" → **no extra module recommended** now. Revisit Slip2GO if you have an account and docs.

## 3. Slip-type support matrix

✓ documented · ✗ not supported · ? not documented (`TODO(verify)`)

| Slip type | promptparse-jvm 0.1.0 | EasySlip | SlipOK | RDCW |
|---|---|---|---|---|
| Standard BOT slip (mini QR, API type `000001`) | ✓ `SlipVerify.parse` | ✓ payload / image / base64 / url | ✓ data / files / url | ✓ payload / file |
| TrueMoney wallet slip | ✓ `SlipVerify.parseTrueMoney` (offline) | ✓ **image/base64/url only** — the TrueMoney mini-QR payload (`eventType/transactionId/date`) is **not** accepted by any documented endpoint | ? | ? |

Important consequence for the pipeline: for TrueMoney, EasySlip wants the *image*, not the parsed payload. So the `SlipProvider` abstraction must receive both the payload **and** the (transient, never persisted) image bytes, and a provider declares which inputs it accepts per slip type.

## 4. Proposal

### Module layout
```
slipverify-jvm/
  core/                  zero deps except promptparse; Java 11
  image-zxing/           ZXing core + javase
  provider-easyslip/  provider-slipok/  provider-rdcw/
  store-redis/           Lettuce (see Q4)
  spring-boot-starter/   Boot 4.x, Micrometer optional
  examples/java, examples/spring-boot
  gradle/libs.versions.toml, config/detekt, .github/workflows (ci, codeql, release)
```
Artifacts: `io.github.sunjirapath:slipverify-core` etc. Starter named `slipverify-spring-boot-starter`.

### Public API (Java-friendly; all `public` explicit, `@JvmStatic`/`@JvmOverloads`)
```kotlin
interface SlipVerifier { fun verify(request: SlipRequest): SlipVerificationResult
  companion object { @JvmStatic fun builder(): SlipVerifierBuilder } }
class SlipRequest @JvmOverloads constructor(
  imageBase64: String? = null, payload: String? = null,
  expectedAmount: BigDecimal? = null, expectedReceiver: ReceiverRule? = null, maxAge: Duration? = null)
interface QrExtractor { fun extract(imageBase64: String): ExtractionResult }   // implemented by image-zxing
interface SlipProvider {
  val name: String
  fun supports(type: SlipType): Boolean
  fun verify(query: ProviderQuery): ProviderOutcome     // ProviderQuery: type, payload, image bytes?, expected amount
}
interface DuplicateStore { fun markIfAbsent(key: String, ttl: Duration): Boolean }   // InMemoryDuplicateStore in core
enum SlipType { BOT_STANDARD, TRUEMONEY }   enum VerificationStatus { VALID, INVALID, UNVERIFIED, ERROR }
class SlipVerificationResult(status, slipType, sendingBank, transRef, amount, transferTime,
  maskedSender, maskedReceiver, providerName, reasons: List<ReasonCode>)   // masked toString()
```
`core` has no image code, so base64 input needs a `QrExtractor` on the builder (`image-zxing`); `payload` input works with `core` alone. Collections are `java.util.List`, time is `java.time`, no Kotlin `Result`/`Pair`/lambdas-only in signatures.

### Reason codes
Requested: `NO_QR_FOUND, NOT_A_SLIP_QR, CRC_INVALID, CRC_AUTOFIXED(info), PROVIDER_NOT_FOUND, AMOUNT_MISMATCH, RECEIVER_MISMATCH, TOO_OLD, DUPLICATE, PROVIDER_ERROR, PROVIDER_UNSUPPORTED_SLIP_TYPE, UNSUPPORTED_IMAGE_FORMAT, IMAGE_TOO_LARGE`.
Proposed additions: `INVALID_BASE64`, `EMPTY_INPUT`, `IMAGE_DIMENSIONS_TOO_LARGE`, `IMAGE_DECODE_TIMEOUT`, `PROVIDER_PENDING` (bank slip too fresh: EasySlip `SLIP_PENDING`, SlipOK 1010), `PROVIDER_AUTH_ERROR`, `PROVIDER_QUOTA_EXCEEDED`, `PROVIDER_RATE_LIMITED`, `PROVIDER_TIMEOUT`, `NO_PROVIDER_CONFIGURED` (info; explains the `UNVERIFIED` cap), `TIME_UNAVAILABLE` (info; `maxAge` set but provider gave no time).
Status mapping: input/QR problems → `INVALID` or `ERROR`; no provider → `UNVERIFIED`; provider "not found" → `INVALID`; provider/network failure after the chain → `ERROR`; mismatch/too old/duplicate → `INVALID`; `VALID` only after provider success with all requested checks passing.

## 5. Open questions (need your decision)

1. **JSON library.** Options: Gson (~280 KB, zero deps, maintained, tree API) vs `minimal-json` (~30 KB, zero deps, but unmaintained since 2017). Recommendation: **Gson** in each provider module (tree parsing only, so no reflection/data-class pitfalls). `core` stays JSON-free.
2. **WebP.** SlipOK accepts WebP, but ImageIO needs a plugin (e.g. `webp-imageio`, native libs). Recommendation: **PNG/JPEG only**, WebP → `UNSUPPORTED_IMAGE_FORMAT`.
3. **RDCW.** Success schema is unpublished. Options: (a) build the module with a tolerant parser plus `TODO(verify)` and a fixture you supply, (b) defer it until you have an account response. Recommendation: **(b)**, and ship EasySlip and SlipOK first.
4. **Redis client.** Lettuce (no heavy transitive deps, thread-safe) vs Jedis. Recommendation: **Lettuce**.
5. **Spring Boot baseline.** Recommend **3.x (Java 17 runtime)**; `core` and providers stay Java 11. Starter then needs JDK 17 — OK?
6. **First provider to review the abstraction:** EasySlip (best documented, has the TrueMoney image-only wrinkle). OK?
7. **Sandbox/live tests** need real keys; none of the three documents a sandbox.
8. **Matching mode.** EasySlip/SlipOK can match amount/receiver server-side, but this requires portal-registered accounts and varies per vendor. Proposal: always do the **amount/receiver checks locally** on the returned data (uniform across providers), and pass `matchAmount`/`amount` through only as an optional hint.
9. **Repo visibility.** I'm creating `sunJirapath/slipverify-jvm` as **public** (Apache-2.0), matching promptparse-jvm.

## Sources
- promptparse-jvm: <https://github.com/sunJirapath/promptparse-jvm>, <https://repo1.maven.org/maven2/io/github/sunjirapath/promptparse/maven-metadata.xml>
- EasySlip: <https://document.easyslip.com/en/v2/>, <https://document.easyslip.com/en/v2/verify/bank/payload>, <https://document.easyslip.com/en/v2/verify/truewallet/image>, <https://document.easyslip.com/en/reference/error-codes>, <https://github.com/EASYSLIP-CO-LTD/n8n-nodes-easyslip>
- SlipOK: <https://slipok.com/api-documentation/>, <https://slipok.com/api-documentation/check-slip/>, <https://slipok.com/api-documentation/error-status-code/>, <https://slipok.com/api-documentation/bank-code/>
- RDCW: <https://slip.rdcw.co.th/docs>, <https://slip.rdcw.co.th/pricing>, <https://slip.rdcw.co.th/tos>, <https://historical-wind-0ac.notion.site/RDCW-Slip-Verify-API-1274811e984380a7a884ff5fc07ba8ac>
