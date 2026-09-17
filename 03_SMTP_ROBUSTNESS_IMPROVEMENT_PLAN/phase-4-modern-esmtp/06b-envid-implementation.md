# Step 6 implementation: DSN transaction identifiers (ENVID)

- Approved scope: ENVID only, 15 September 2026; [#736](https://github.com/bbottema/simple-java-mail/issues/736), enhancement, milestone 10.0.0. Implementation and final review accepted on 17 September 2026; selective commit and delivery authorized. Earlier uncommitted checkpoints below are historical.
- Parent: [Complete the DSN envelope model](06-complete-dsn-envelope-model.md), under [#722](https://github.com/bbottema/simple-java-mail/issues/722).
- Prior evidence: [Angus characterization](06a-dsn-provider-characterization.md).
- ORCPT and recipient-specific NOTIFY were deferred from this approved slice. Their separately approved local implementation is recorded in the [homogeneous configuration slices](06c-homogeneous-email-configuration.md), without changing #736's ENVID-only scope.

## API and behavior

`DeliveryStatusNotification` gains a nullable, unencoded `envelopeId`, its builder gains `envelopeId(...)`, and `toBuilder()` retains all options.
Both composed and exact Email builders expose `fixingEnvelopeId(...)`; the exact builder's CLI name is `fixingExactEnvelopeId` to distinguish its construction path.
Partial DSN updates retain the fixed identifier. Replacing or clearing the whole DSN value replaces or clears it as well. On either builder, a null identifier clears only that field and resumes automatic behavior.

Without a fixed identifier, the bundled Angus adapter generates a fresh UUID for each send attempt on a DSN-capable connection. Reusing or concurrently sending the same Email produces separate identifiers. Generated values stay on the send result, never on the immutable Email or shared Session/transport.
A fixed identifier is caller-owned: no Message-ID substitution, trimming, or replacement. Copying or resending an Email preserves it, so callers choose a new value when they need to distinguish retries.
Accept printable ASCII only, reject an empty value, and limit the complete ENVID parameter to 100 characters, including `ENVID=` (94 xtext characters for the value). Space, `+` and `=` each occupy three xtext characters. This includes the parameter prefix as required by RFC 3461 sections 4.4 and 5.4.
The immutable model validates the value before SMTP resources are acquired. Formatting stays inside the Angus adapter; no raw command-text API is added.

Fixing ENVID requires DSN on the actual connected transport, after STARTTLS/authentication where applicable. Missing or malformed DSN advertisement fails before MAIL FROM; a fixed identifier is never silently dropped.
Automatic ENVID is best-effort: without usable DSN support, send normally without it. Existing NOTIFY/RET requests remain best-effort as well. No configurable requirement enum is introduced.

The Angus adapter uses its supported per-message MAIL extension hook. It preserves unrelated raw MAIL extension parameters. A raw ENVID suppresses automatic generation; combining one with a typed fixed identifier fails before submission. Existing raw configuration remains caller-owned and is not validated or reported as an effective typed identifier.
Per-message state remains on a fresh facade; the Session and pooled transport are not mutated with an identifier. Existing submission and recipient reply handling stay with Angus.

`MailTransportAdapter.supportsDeliveryEnvelope(...)` prevents an older third-party adapter from silently ignoring ENVID. Its backwards-compatible default retains the pre-existing envelope behavior but declines an identifier.
An adapter opting in must enforce actual server support before submission. Older adapters and the generic provider fallback retain ordinary sends without automatic ENVID. A CustomMailer receives a fixed identifier through Email and owns mapping/enforcement on its transport; Simple Java Mail cannot generate a capability-checked identifier for that caller-owned transport. Logging-only and rehearsal do not establish remote capability support.

`MailSubmissionReceipt.getEnvelopeId()` exposes the effective unencoded identifier, including failed SMTP attempts that used it. No identifier is reported for capability/preparation failure, unsupported automatic DSN, logging-only, CustomMailer, or an adapter that does not report one. Presence does not imply SMTP acceptance, final delivery, or a future DSN. The observer receives the same receipt, with no additional callback API.
The adapter reports it through `MailTransportResult.withEnvelopeId(...)`; result enrichment and receipt serialization preserve it. Existing receipt constructors and serialized receipts retain a null identifier.

## Integration boundaries

- Existing DSN copying and whole-value Email governance carry the field; tests cover defaults, overrides and suppression.
- No global identifier property or Spring metadata entry: a fixed application-wide value is not a useful per-send correlation default. Applications using Spring set it on each Email through the same Java API.
- Exact/protected MIME bytes are unchanged. Generic MIME conversion does not turn ENVID into a header.
- Java/CLI sends, sync/async, pools, simple batches and open connections use the same adapter path.
- The automatic facade retains existing Angus per-message options. Ordinary MIME reads/updates delegate to the message being serialized, preserving the provider's opt-in 8-bit conversion; the existing exact/protected-content guards still prevent that traversal. This does not add the broader SMTPUTF8/8BITMIME policy from step 8.
- Only the receipt and adapter-result identifier accessors are added to the existing result API. Observer, cancellation, deadline, pool ownership and connection-probe APIs are unchanged.

## Verification and delivery

- [x] Model validation, equality/serialization, builder preservation and clear/replacement behavior.
- [x] Copy/governance, configuration snapshot and CustomMailer/logging-only paths.
- [x] Wire encoding, combinations with NOTIFY/RET, missing/malformed DSN, raw-extension collisions and same-connection checks.
- [x] Simultaneous pooled sends, success after failure, distinct consecutive identifiers, plain sends after ENVID, observer/receipt ordering.
- [x] Exact/protected MIME, duplicate recipients, simple-batch and open-connection regressions.
- [x] CLI generated metadata/help and composed/exact paths; classpath/JPMS consumers and third-party adapter boundaries.
- [x] Focused Java 11 tests, normal non-live library and modern-JDK verification, including the existing Spring/starter tests.
- [x] Coding-guide audit, license removal and architecture overview review. No new send owner, lock or resource-lifetime boundary; the approved image remains accurate.
- [x] Website checks and clean build; internal links scanned, with only unrelated Journal failures remaining (see below).
- [x] Maintainer acceptance and selective commit/delivery.

### Earlier explicit-only verification — 15 September 2026

- Clean Java 11 non-live library verification: 1,067 tests, no failures/errors, one existing skip (`tmp/phase4-envid-verify-java11.log`).
- Final focused Java 11 verification after tightening the RFC length boundary: 76 tests, no failures/errors/skips, plus packaged classpath/JPMS consumers (`tmp/phase4-envid-final-java11.log`). A final empty-identifier message clarification was checked again in the 21-test model suite (`tmp/phase4-envid-final-model-java11.log`).
- Clean Java 21 full non-live reactor, including CLI, generated metadata, Javadocs, Spring/starter and provider-neutral consumers: 1,204 tests, no failures/errors, one existing skip (`tmp/phase4-envid-clean-verify-java21.log`).
- A stale CLI Javadoc cache exposed an existing concurrent-population race when the new API invalidated its fingerprint. The new cold-cache regression reproduced `ConcurrentModificationException`; a concurrent map fixes it without changing serialized metadata ordering. The cold-cache and daemon-concurrency regressions pass in the final clean reactor. The API expansion workflow now calls out metadata regeneration before help assertions.
- `mvn license:remove` passed, followed by a source scan confirming no generated license headers remain (`tmp/phase4-envid-license-remove.log`). Scoped whitespace checks passed.
- Website `check` and clean `build` passed. Internal-link verification reports 12 broken links, all originating in the unrelated Journal pages `the-libraries-behind-simple-java-mail` and `your-mail-server-works-for-a-troll-farm-now`; no ENVID documentation link failed. Journal files were left untouched (`tmp/phase4-envid-site-*-final.log`).
- New transport tests use loopback servers only. Authenticated STARTTLS is covered on the wire; SMTP/SMTPS protocol-specific extension selection is covered at the adapter boundary. No live-email demos, SpotBugs or benchmarks ran.

Leave root and website changes uncommitted for review. Preserve unrelated staged research and website/Journal work. Do not close #736 or mark Phase 4 complete at implementation handoff.

### Automatic/fixed revision verification

- Agreed after the design pause: automatic UUIDs, the `fixingEnvelopeId(...)` override, strict fixed-ID capability checks, and receipt access. Earlier explicit-only evidence above is not a substitute for verifying this revision.
- Final focused Java 11 model/adapter/receipt/SMTP regression suites passed: 147 tests, including plain/multipart 8-bit conversion, raw identifiers, custom/older providers, pooled isolation and ambiguous acceptance (`tmp/phase4-envid-auto-focused-final-java11.log`).
- Clean Java 11 non-live library verification passed earlier in the automatic/fixed revision: 1,088 tests, no failures/errors, one existing skip, including Spring/starter and classpath/JPMS consumers (`tmp/phase4-envid-auto-verify-java11.log`). The subsequent MIME delegation correction is covered by the final focused Java 11 suites above and the final modern-JDK reactor below.
- A wire regression reproduced automatic ENVID suppressing existing opt-in Angus 8-bit conversion. Delegating ordinary MIME access fixed it; the focused adapter and SMTP suites passed again (`tmp/phase4-envid-auto-eightbit-red.log`, `tmp/phase4-envid-auto-eightbit-green.log`).
- Coding-guide audit completed across the 14 touched production Java classes and new/changed tests. Selection, validation, provider preparation and outcome construction remain separate; no strategy framework, new resource owner or configuration-resolving value object was introduced.
- Website check and clean build passed. The final internal-link scan still finds only the same 12 unrelated Journal links; ENVID guidance and the added 10.0.0 migration note have no failing links (`tmp/phase4-envid-auto-site-check-final.log`, `tmp/phase4-envid-auto-site-links-final.log`).
- Final clean Java 21 non-live reactor passed after the MIME correction: 1,236 tests, no failures/errors, one existing CLI skip. This includes regenerated CLI metadata, CLI daemon/process tests, Javadocs, Spring/starter and packaged classpath/JPMS consumers (`tmp/phase4-envid-auto-final-verify-java21.log`).
- `mvn license:remove` passed. Production sources and every changed Java source are free of generated license headers; unrelated pre-existing headers in four unchanged test classes were left alone. Scoped whitespace checks passed (`tmp/phase4-envid-auto-license-remove.log`).
- #736's scope and usage now reflect automatic UUIDs, fixed overrides, receipt access and provider/raw-configuration boundaries. It remains Open / In Progress with its existing enhancement classification and 10.0.0 milestone. No commits or pushes were made; unrelated staged research and Journal work remain outside this change.
