# Mail-send progress: supported hooks and verification

The accepted live-progress and detailed-stage implementations are tracked by [#760](https://github.com/bbottema/simple-java-mail/issues/760)
and [#761](https://github.com/bbottema/simple-java-mail/issues/761). They are implemented for unreleased 10.0.0. This record preserves the useful
hook research and dated local evidence after retiring their completed implementation plans; it is not a hosted CI result or a new release.

## Source-grounded hook boundaries

The pinned [Angus 2.0.5 SMTPTransport](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java)
exposes protected greeting-read, EHLO/HELO, STARTTLS, envelope, DATA/BDAT and command hooks. Its private authentication implementation still enters
observable command paths. SJM recognizes only the AUTH prefix; it does not decode payloads, challenges or credentials.

[SocketFetcher](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/core/src/main/java/org/eclipse/angus/mail/util/SocketFetcher.java) combines address
resolution, connect, proxy and TLS work. Resolution can precede `Socket.connect`; factory return is not proof of a completed verified handshake.
Socket tracking therefore cannot honestly separate all those phases. SJM delegates protocol work unchanged and makes no extra network requests.

| Detail | Supported observation | Boundary |
| --- | --- | --- |
| DNS / TCP / proxy | Inclusive acquisition | Precise independent phases need supported upstream hooks; bridge startup is not remote proxy negotiation. |
| Greeting | First physical-setup response read | No historical replay for a later borrower. |
| EHLO / HELO | Existing protected methods | Categories only, without response text. |
| STARTTLS | Existing upgrade, handshake and verification | Implicit TLS remains in acquisition where no isolated hook exists. |
| Authentication | AUTH prefix on String and byte-array command paths | No payloads, mechanism transcript or private-method interception. |
| Sender / recipients | Actual MAIL FROM and RCPT command paths | No addresses or per-recipient audit history. |
| DATA permission | Actual DATA permission exchange | Not a BDAT stage. |
| Upload / final reply | Existing content-stream and final-read hooks | Input counts exclude framing, dot escaping and TLS; intermediate chunk replies are not final acceptance. |

| Sending path | Available detail |
| --- | --- |
| New managed Angus connection caused by this email | Supported setup categories, followed by current submission/upload work. |
| Reused managed connection | Current submission/upload only; no synthetic setup replay. |
| Background-created connection | No setup attributed to its later borrower. |
| Caller-owned ordinary Angus transport | Coarse progress; its transport is not replaced. |
| Third-party provider | Whatever optional setup/submission hooks it supports, with coarse fallback elsewhere. |
| CustomMailer | Opaque `SUBMITTING` callback. |
| Shared batch/open-connection setup | Outside individual-email progress; current per-email submission detail remains available. |

The existing pool callback already supplies physical transport identity. SJM's internal bridge forwards it without requiring an upstream release
for progress. Generation-scoped setup/submission bindings retire reporting authority on cancellation or completion and detach provider references
in cleanup. This is distinct from logical acquisition/recovery evidence. See the [concurrency guide](../concurrency/12-live-send-progress.md).

## Live-progress verification, 14 October 2026

| Check | Recorded result |
| --- | --- |
| Real JDK 11 library reactor, Javadocs and core-only/classpath/JPMS consumers | 2,211 tests passed; one existing Windows symbolic-link assumption skip. |
| JDK 21 non-live reactor, Spring/starter, CLI/daemon, Javadocs and publish-cli packaging | 2,407 tests passed; the existing opt-in daemon benchmark remained skipped, not run. |
| Progress-specific main-module tests | All 28 passed on both JDKs, alongside Angus stream and existing diagnostics/provider regressions. |
| SMTP conformance | 484 embedded cases, 24 Postfix/Exim scenarios and ten independent protected-content checks passed, without skipped scenarios. |
| Generated CLI metadata | Both caches regenerated; source and packaged hashes matched. Callback setters remain excluded. |
| Downloadable field guide | Compiled on JDK 11; all 12 offline checks passed. |
| Website | 84 checks, clean build and 4,114 internal links across 67 pages passed. |

The layers overlap and must not be added into a unique-test count. Observed/unobserved DATA and BDAT controls preserve bytes, attachment reads,
serialization and command counts. Independent real-server checks verify DKIM, S/MIME and OpenPGP output with progress enabled. Upload completion
remains separate from receipt acceptance and final delivery.

Initial highly parallel Windows checks exposed workstation-hostname resolution and fresh-JVM startup sensitivity. Fixtures now explicitly name
their local host; production timeouts and assertion deadlines were not relaxed. Successful reactors used the existing four-fork setting, and SMTP
conformance retained two forks. Module targets were cleaned without erasing concurrent #699 artifacts under the root target.

## Detailed-stage verification, 14 October 2026

The later #761 layer passed focused scope, SPI, physical-acquisition and managed-Angus tests. Its detailed SMTP fixture contains 21 delayed/failing,
direct/pooled, implicit-TLS, custom-factory, caller-owned, reused/background and shared-scope scenarios. Separate tests cover both AUTH command paths,
late generations, cancellation and optional-hook failures.

Real JDK 11 library verification passed with Javadocs, Spring/starter and provider consumers using four JVMs. An earlier eight-JVM run timed out in
existing ScopedMailSenderTest and SmtpConnectionProbeTest cases; both whole classes passed unchanged in a focused control, followed by the full
four-JVM run. No timeout or assertion was relaxed. A clean JDK 21 non-live reactor also passed with Javadocs, consumers and regenerated CLI metadata.

Website verification passed 84 checks, a clean build and 4,115 internal links across 67 pages. An earlier concurrent RelayDesk article/test mismatch
passed later without an article edit from this work. Conformance again passed all 518 results. Local evidence remains at
`target/smtp-conformance/20261014T153611Z-d89981cc/evidence/summary.json`; detailed build logs are outside the checkout at
`%TEMP%/sjm-761-verification/`. These are local dirty-tree results, not a claim of hosted verification.

## Retention corrections and sustained pressure, 15 October 2026

Final retention work detaches queued/discarded notifications and removes fully retired pool registrations after acknowledged disposal. The
[memory-pressure record](mail-send-memory-pressure-results.md) documents the million-send observed soak, attachment/fault/churn profiles,
deliberate-retention controls and remaining coverage limits. Its final integration uses published Clustered Object Pool 4.4.0, not an unreleased
dependency: 27 focused tests on real JDK 11/21, 321 wider lifetime regressions and all 13 published-dependency smoke profiles passed.

Those final gates are not a newly repeated whole reactor. The API/coding-guide passes and `mvn license:remove` completed after implementation
verification. No live-email demos, benchmarks or intentional SpotBugs verification formed part of this work.

### Selective-delivery documentation check

On 15 October, the selected website tree at `898b2da` was archived into a separate build directory, excluding concurrent Journal prose and artwork.
All 83 checks, the clean build and 4,096 internal links across 67 pages passed. These counts differ from the earlier dirty-tree runs above; they
describe this exact selected delivery rather than relabeling the earlier results. The archive's downloadable examples and fixtures compiled on
real JDK 11 and passed all 12 field-guide, 73 Polar Meridian and 63 RelayDesk offline checks, without sending mail.

Case-study edits in this delivery are mechanical API updates only: the result-observer rename, current recipient-builder entry points and internal
test-result assembly. Other article changes and the new application-wiring helper remain outside this delivery. No migration entry was added merely
for renaming the unreleased observer API.

## Remaining research and related decisions

Isolated DNS, TCP, remote proxy negotiation and implicit TLS need separately agreed supported hooks with privacy, attribution and generation
contracts. They remain under coarse acquisition; their research is not a prerequisite for the implemented stages. No reflection, debug-log scraping,
socket replacement or extra lookup/probe is an acceptable substitute.

[#699](https://github.com/bbottema/simple-java-mail/issues/699) owns separate PIPELINING/CHUNKING work. Categories must not imply a strict alternating
command/reply sequence, and BDAT intermediate replies must not become final acceptance. The pinned Angus 2.0.5 control recorded dot escaping on BDAT;
observed and unobserved streams matched, and progress counts input before that transformation. This evidence belongs to that separate investigation,
not a quiet change of provider behavior here. [#762](https://github.com/bbottema/simple-java-mail/issues/762) owns optional OpenTelemetry research;
lossy progress cannot be the authoritative source for span completion or send counts.

Architectural decisions remain in [ADR 0032](../adr/0032-live-mail-send-progress.md) and [ADR 0033](../adr/0033-detailed-mail-send-progress.md).
The [observer demo](../../modules/simple-java-mail/src/test/java/demo/MailSendObserverDemoApp.java),
[memory runner](../../tools/memory/README.md) and [concurrency guide](../concurrency/12-live-send-progress.md) provide maintained usage and procedures.
