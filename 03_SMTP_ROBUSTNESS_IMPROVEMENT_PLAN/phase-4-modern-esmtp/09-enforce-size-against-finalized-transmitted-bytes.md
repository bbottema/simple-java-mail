# Step 9: Enforce SIZE against finalized transmitted bytes

- Status: Implemented, verified and accepted on 28 September 2026; selective commit/push and issue closure authorized
- Depends on: Step 4 capability reporting and the existing finalized-content/rehearsal path
- GitHub child issue: [#748 — Validate SMTP SIZE using finalized transmitted content](https://github.com/bbottema/simple-java-mail/issues/748)
- Labels: `enhancement`; never also `major feature`
- Milestone: 10.0.0
- Release sensitivity: Automatic server-limit preflight is a behavior change; the separate application maximum-size guard remains unchanged
- Primary modules: `core-module`, `simple-java-mail`, `angus-mail-provider-module`, exact EML and security modules

## Goal

Compute the size relevant to SMTP submission from the same finalized content that will be sent, compare it with the server's advertised SIZE limit, and keep this distinct from Simple Java Mail's existing local maximum-email-size guard.

## Accepted design (26 September 2026)

1. Automatically check managed Angus submissions after its optional 8-bit conversion, before MAIL FROM. No builder switch, property or enum.
2. Count RFC 1870 octets including canonical CRLF and any required final CRLF, excluding transparency dots and the DATA terminator. Reuse Angus's CRLF stream and a long counter.
3. Add nullable `Long getMessageSize()` and `Long getServerMaximumMessageSize()` to receipts and adapter results. Retain available facts on failures and through immutable enrichment/serialization; keep existing constructors. No separate declared-SIZE field or new public result wrapper.
4. Reject above a reliable positive limit from the selected connection; equality is allowed. Missing SIZE permits sending. Parameterless, zero, malformed, overflowing or contradictory maxima are unknown, not zero-byte limits.
5. Automatically declare SIZE when advertised; retain one valid explicit advanced declaration. Reject malformed/duplicate declarations with a helpful reference to the supplying setting. Preserve unrelated parameters.
6. Share EHLO line recognition and SIZE interpretation, not bounded diagnostic snapshots. Inspect the complete selected connection's reply for operational SIZE facts, replace post-TLS facts and clear stale state on reconnect, failed EHLO, HELO and close. Never probe as a send prerequisite.
7. Keep measurement attempt-local under the existing transport monitor; clear active references in finally and stop before submission on cancellation. A partial count is never a complete size.
8. Local oversize failures retain both facts, report unsubmitted recipients without SMTP replies or ENVID/REQUIRETLS use, and preserve healthy pooled connections. Existing retry guidance remains caller-policy-required.
9. Keep rehearsal's encoded EML size and the application `withMaximumEmailSize(...)` guard unchanged. Neither predicts provider conversion or a different selected server.
10. Do not replace caller-owned transports. CustomMailer, logging-only and unsupported provider paths do not invent facts; adapters may supply reliable measurements through the result SPI.

Avoid another whole-message buffer or disk spool merely to count. Exact/protected representations remain authoritative. Ordinary MIME may be serialized for counting and then sending; caller data sources must remain stable and repeatable during an attempt.

Research: Angus 2.0.5 neither adds SIZE nor checks the server maximum. Its optional conversion changed a characterization message from 294 to 268 serialized bytes before DATA. DATA framing then added CRLF and transparency bytes. This is why measurement belongs after conversion, with RFC counting rather than raw buffer length.

## Tests first

### Review correction: separate SIZE facts from diagnostic budgets (28 September 2026)

The original probe parser's 256-extension and 2048-character cutoffs were reporting heuristics, not protocol or provider requirements. Reusing that snapshot for sending could hide a usable SIZE advertisement because an unrelated extension exceeded a reporting limit. Operational parsing now scans the complete provider-owned reply and retains only immutable SIZE support/maximum facts, including all conflicting duplicates. The numeric interpretation is shared with the public capability snapshot and runs before display formatting.

Diagnostic collection instead budgets 64 KiB of escaped UTF-8 capability text per snapshot, including names, duplicate values and the map/list formatting emitted by `SmtpCapabilities.toString()`. Count incrementally before retaining entries; do not copy or render a whole oversized reply. Remove per-line limits and individual capability-parameter truncation. On overflow, discard the entire affected snapshot and add one warning; setup success and operational SIZE parsing remain independent. The same accounting checks third-party snapshots at the report boundary. Repeated malformed-line warnings are aggregated. Typed SIZE facts and safe immutable collections survive serialization, including older snapshots without the typed field.

The budget is a retained-output policy, not a new SMTP restriction:

- [RFC 5321 section 4.5.3.1.5](https://www.rfc-editor.org/rfc/rfc5321.html#section-4.5.3.1.5) specifies 512 octets per reply line, not a fixed EHLO extension count. Do not enforce that line size over Angus's existing tolerance.
- On 28 September 2026, the [IANA registry](https://www.iana.org/assignments/smtp/) contained 35 keywords. Giving each a full 512-octet line plus a greeting would use 18 KiB. This reference is not an allowlist or a maximum possible server reply.
- Published examples measured with CRLF framing were 240 bytes/13 lines for [Exchange](https://learn.microsoft.com/en-us/exchange/mail-flow/test-smtp-telnet), 113 bytes/5 lines for [Exim 4.66](https://www.exim.org/exim-html-4.66/doc/html/spec_html/ch-smtp_authentication.html), and 131 bytes/5 lines for [Postfix with its legacy AUTH= advertisement](https://www.postfix.org/SASL_README.html). These are documented examples, not a deployment survey.
- 64 KiB leaves substantial room for private extensions and unusual lines, while two snapshots retain at most 128 KiB of capability text. Object overhead, other report fields and Angus's already-read socket buffers are not included in that bound.

Compatibility checks explicitly cover HELO fallback, disabled EHLO, absent SIZE, unusable maxima, long lines and hundreds of short extensions. A diagnostic overflow cannot reject a send or omit its SIZE declaration. The accepted feature still rejects messages exceeding a reliable advertised maximum; incorrect server advertisements cannot be promised identical behavior. Existing legacy-content permission and TLS/security requirements are unchanged.

Additional regressions cover exact/one-byte-over budget boundaries, escaping and multibyte accounting, SIZE beyond overflow, late conflicting duplicates, long zero-prefixed numbers, post-STARTTLS replacement, healthy lease reuse, concurrent connection isolation, third-party reports and serialized snapshots. Malformed SIZE duplicates containing control characters invalidate an earlier maximum instead of disappearing as unrecognized lines; a later valid duplicate cannot restore that maximum. No new public API, configuration setting, migration note, connection preflight or automatic retry is introduced by this correction.

### SIZE feature verification

1. Compare measured sizes with an independent byte count for composed, exact EML, DKIM, S/MIME, and OpenPGP messages.
2. Cover CRLF normalization, lines beginning with a dot, a line containing only a dot, multibyte UTF-8, attachments, and large streaming content.
3. Capture the SIZE parameter Angus actually sends and compare it with the documented count.
4. Cover advertised limits below, equal to, and above the message size.
5. Prove over-limit failure occurs before MAIL FROM, RCPT TO or DATA and sends no message bytes; then reuse the healthy connection.
6. Cover SIZE advertised before STARTTLS but omitted or changed afterward.
7. Cover no advertised maximum and no SIZE capability.
8. Verify pooling and repeated sends do not reuse a previous message's size.
9. Use bounded large-stream tests to prevent an accidental whole-message buffer, without benchmarks.
10. Verify the existing local maximum-size exception remains distinguishable from a server capability mismatch or server 552 rejection.

## Documentation and release work

- Explain local maximum size, advertised SMTP SIZE, and a server's later rejection as three separate controls.
- Show how rehearsal reports finalized size before a network connection.
- Show how the capability probe adds the server maximum.
- Explain that an SMTP server may still reject a message after accepting a SIZE parameter.
- Add receipt/observer success and failure examples with illustrative output, and explicit provider boundaries.
- Update issue #748, release notes, migration notes for released-behavior changes, affected architecture documents and the shared infographic review.
- Run focused tests, Java 11 verification, the modern-JDK non-live reactor, classpath/JPMS consumers and applicable Spring/CLI checks; run website checks, clean build and internal links.
- Apply the coding guide and remove generated license headers. The implementation review is accepted: commit and push only the #748 root and website changes in semantic groups without sign-off, then close #748 and update #722. Preserve unrelated research, conformance planning and Journal work. No live-email tests or SpotBugs. Synthetic performance testing is tracked separately in #749.

## Acceptance criteria

- [x] The [shared architecture overview](../../docs/concurrency/inside-a-mail-send.md#phase-completion-check) has a recorded updated-or-unchanged review, including finalized-content measurement and preflight.
- [x] One finalized representation supplies the measured content.
- [x] The count follows verified RFC and provider semantics.
- [x] Exact and cryptographically protected content is not rebuilt for measurement.
- [x] Advertised limits are checked using post-TLS capabilities.
- [x] Failure categories distinguish local policy, capability preflight, and SMTP rejection.
- [x] A 16 MiB incrementally generated stream is counted without adding a whole-message buffer; the counter uses long arithmetic.
- [x] Maintainer production-code review and acceptance; selective semantic commits, push and issue closure authorized.

## Verification record — 26 September 2026

- Java 21 full non-live reactor, including Javadocs, CLI generation/process/daemon tests and classpath/JPMS consumers: passed. 1,606 tests reported, zero failures/errors; the opt-in benchmark was skipped.
- Actual Java 11 library verification: passed. 1,433 tests reported, zero failures/errors; one existing test was skipped.
- Latest Java 21 focused pass after fixture adjustments: all 123 cases passed, including SIZE, receipt/result compatibility, protected content and socket aborts.
- Spring compatibility matrix: Boot 2.7.18 / Spring 5.3.39 / Java 11; Boot 3.0.13 / Spring 6.0.14 / Java 17; Boot 3.5.16 / Spring 6.2.19 / Java 21: all passed.
- Website checks (25 helper tests, TypeScript and template checks), clean build and internal-link verification: passed; 3,072 local links across 61 pages. Changed root documentation also passed its local-file link check.
- API workflow and coding-guide audit completed. The shared infographic remains accurate and its two copies match. `mvn license:remove` completed; no generated Java license headers remain.

The full runs exposed two unrelated timing assumptions in existing loopback fixtures. The shared content-negotiation fixture now disables idle expiry while the caller prepares the next message. The abort fixture supplies explicit local hostname settings so DNS cannot consume its protocol checkpoint timeout. Production pool defaults and abort-time assertions are unchanged.

No live-email tests, benchmarks or SpotBugs were run. Changes remain uncommitted on the existing root and website branches; unrelated research, documentation and Journal work is preserved. This step is ready for review, not yet accepted or closed.

## Verification record — 28 September 2026 (diagnostic separation)

- Focused parser, budget, probe, SIZE, lifecycle and receipt coverage: 211 cases passed in the final Java 21 run.
- Java 21 clean non-live reactor (`mvn clean verify -Ppublish-cli -DexcludeLiveServerTests=true -Dlicense.skip=true`): passed; 1,645 tests reported, zero failures/errors, with the opt-in CLI benchmark skipped. Includes Javadocs, generated CLI help, process/daemon tests, Spring/starter tests and classpath/JPMS consumers.
- Actual Java 11 clean library verification (`mvn -pl '!modules/cli-module,!modules/jacoco-aggregator-module' clean verify -DexcludeLiveServerTests=true -Dlicense.skip=true`): passed; 1,472 tests reported, zero failures/errors and one existing `MiscUtilTest` case skipped. Includes Spring/starter and classpath/JPMS consumers.
- Website checks (28 helper tests, TypeScript and template checks), clean build and internal-link verification: passed; 3,072 local links across 61 pages. The three updated architecture/plan documents also passed 15 local-file link checks and the new plan-anchor check.
- Coding-guide review and 160-column check completed for the corrected production classes. `mvn license:remove -Ppublish-cli` passed; no generated Java license headers remain. Scoped whitespace checks passed.

No live-email tests, benchmarks or SpotBugs were run. Public signatures and the accepted receipt/result review batch are unchanged by this correction. Resume production review at the capability-parsing batch below; all changes remain uncommitted, with unrelated staged research and website/Journal work preserved.

### Review follow-up — JDK UTF-8 accounting

Replaced the hand-written UTF-8 byte-width and surrogate rules with the JDK `CharsetEncoder`. Rendering and accounting share one escaping routine; accounting encodes small chunks, retains any input the encoder still needs, and stops at budget overflow without rendering the complete value. Chunk sizes are workspace choices, not extra report limits.

After this refactor, all 46 focused core cases passed on actual Java 11, and all 229 focused parser, budget, probe, SIZE, lifecycle and receipt cases passed on Java 21. Added coverage includes split surrogate pairs, malformed-character replacement, tiny budgets, unchanged escaping/truncation and bounded reads from oversized input. The full-reactor and website results above precede this internal-only follow-up. Coding-guide and whitespace checks passed, and `mvn license:remove -Ppublish-cli` completed again.

## Production review order

1. Receipt and adapter-result size facts, immutable copying/serialization, and the existing receipt-construction handoff.
2. Complete operational SIZE parsing, bounded probe diagnostics and connection-scoped fact lifetime.
3. Streaming byte measurement, advanced SIZE declarations, and the managed pre-MAIL hook.
4. Failure/cleanup ownership and documentation. Tests can be reviewed separately.

Review complete on 28 September 2026: all production batches are accepted, including measurement, pre-MAIL enforcement, explicit declarations, per-attempt cleanup and result propagation. Tests were excluded from the walkthrough as requested. The dated uncommitted/review-pending checkpoints above describe earlier states, not the current acceptance status.

## Agreed follow-up: synthetic performance investigation

The production diff review is complete. Deliver #748 first, then carry out the synthetic performance investigation under [#749](https://github.com/bbottema/simple-java-mail/issues/749), a separate Todo child of #722 on the 10.0.0 board. This follow-up supersedes the earlier benchmark exclusion; it does not authorize an opt-out or opt-in API yet.

- Use repeatable local SMTP fixtures, warm-up and repeated runs. Keep connection reuse, payloads, provider settings and concurrency comparable across baselines and experimental variants. Preserve the current uncommitted implementation and unrelated work; no live mail or external delivery.
- Attribute CPU time and elapsed delays separately: content inspection/encoding, SIZE serialization, configured local-size validation, optional message protection, queue/pool waits and SMTP I/O. Record allocation/GC effects and separate fixture overhead from client work where possible.
- Cover batches of a few thousand small messages, attachment-heavy messages, and exact/protected content. Compare sequential shared-connection sends with bounded pooled concurrency, and distinguish in-memory from file-backed content. Include servers with and without SIZE support.
- Report measured milliseconds per message, latency distributions and batch throughput, alongside the workload and environment. Isolate SIZE cost from prior encoding-negotiation work rather than attributing their combined cost to one feature. These are synthetic results, not production throughput promises.
- Look for redundant passes that can be safely combined or reused before introducing caller-facing switches. Any reuse must respect the final transmitted representation, including provider conversion, exact/protected content and the selected connection.
- If meaningful costs remain, quantify the savings and lost guarantees for each candidate opt-out before deciding whether an API is warranted and how fine-grained it should be. Do not assume one broad performance switch, silently disable security requirements, or equate disabling preflight inspection with disabling normal MIME encoding. Any agreed API must follow the API expansion workflow and document the trade-offs.

No performance measurements have been run yet, and no public opt-out design has been accepted.

## Stop condition

If the provider's transmitted representation can differ from the bytes Simple Java Mail can measure, stop and close that provider seam first. Do not expose an “exact SMTP size” value that is only an estimate.
