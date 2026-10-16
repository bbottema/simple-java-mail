# Simple Java Mail 10.0.0 competitive progress report

- Assessment date: 2026-10-16.
- Implementation refresh: 2026-10-15, adding accepted live progress under [#760](https://github.com/bbottema/simple-java-mail/issues/760), detailed connection/SMTP stages under [#761](https://github.com/bbottema/simple-java-mail/issues/761), retention corrections and a repeatable memory-pressure workbench. Safe retries under #756, connection failover under #755, selected-Mailer ownership corrections under #757 and earlier recipient-rejection/managed-transport updates remain part of the assessment.
- Optional OpenTelemetry [#762](https://github.com/bbottema/simple-java-mail/issues/762) is implemented and accepted after production-only and holistic review on 2026-10-16. This is development-branch work for 10.0.0, not a released capability.
- Competitor baseline: 2026-10-04. Symfony failover, Nodemailer pooling, MailKit SMTP capabilities and Vert.x PIPELINING documentation were rechecked on 2026-10-12 for this refresh; the wider comparison was not repeated.
- Original research: [The World-Class SMTP Framework](simple-java-mail-world-class-smtp-research.pdf), dated 2026-08-29.
- Implementation baseline: [`e0fd0ac6`](https://github.com/bbottema/simple-java-mail/commit/e0fd0ac62394a652d2d617ba08e6ed9311436b0a) on `codex/10.0.0`, containing accepted #762 telemetry runtime/configuration integration on top of reviewed #760/#761 progress, retention corrections and the memory workbench, following the earlier retries and failover delivery. The implementation and evidence records linked below identify the reviewed scope.
- Release status: development of 10.0.0 continues. The assessed capabilities are present on the development branch, not in a published release. This does not describe the capabilities of the published 9.x libraries.
- Method: repository and recorded verification review, including the #756, #760, #761 and #762 verification sequences. Competitor documentation was not re-researched for the 2026-10-16 telemetry refresh; those comparisons retain their recorded dates. No cross-library benchmark or new competitive black-box comparison was run.

This is the companion to the original PDF. Keep the two together: the PDF records the initial comparison and research agenda; this report explains what has changed, where Simple Java Mail now compares well, and where the original gaps remain. Its comparisons are assessments, not independently measured rankings.

The conclusion is that **Simple Java Mail 10.0.0 is now a credible contender for the strongest overall SMTP client experience across the surveyed ecosystems, but not yet the most complete SMTP protocol engine**. Users experience configuration, message handling, resource management and failure reporting together, not just the SMTP commands a transport supports.

Connection failover strengthens that position: applications can keep sending through a relay outage and return to a recovered primary without writing their own routing loop. Its connection-only boundary preserves the existing submission truth rather than gaining availability by automatically replaying possibly accepted mail. The related clustered-ownership fix makes destination-specific configuration dependable even when failover is disabled.

Safe mail-send retries address the next step: when the selected relay clearly refuses the whole email temporarily, applications can opt into bounded attempts and backoff without scheduling each retry themselves. Partial or uncertain acceptance still stops automatic replay. This builds on receipt evidence rather than treating every exception as permission to resend.

This is a progress report, not an implementation plan, a release-readiness declaration or authorization for further work. Completing one group of improvements does not settle the product ambition or determine the next priorities.

## Comparison scope

The original comparison covers client-side SMTP submission and message construction across .NET, Java, Node.js, PHP, Rust, Go and Python. Its principal benchmarks are MailKit/MimeKit, Nodemailer, Symfony Mailer and Vert.x Mail Client, with lettre, go-mail, Python email/smtplib and aiosmtplib, and PHPMailer completing the wider comparison.

Hosted delivery APIs, inbound POP3/IMAP, full mail-transfer agents and direct-to-MX delivery remain outside this scope. Durable application queues can be useful integrations, but they are not the same capability as a robust SMTP client.

## Progress since the original comparison

The PDF described a broad and approachable library with important operational and protocol gaps. Several of those findings have since been addressed. The implementation and public contracts are summarized in the [10.0.0 release history](../../RELEASE_HISTORY.md); the [ADR index](../adr/README.md) explains the architectural choices and boundaries.

| Area | Implemented progress | Tracking |
| --- | --- | --- |
| Submission results | Ordered recipient results, SMTP replies, enhanced status codes, explicit partial or unknown acceptance, and conservative retry guidance. Missing provider facts remain missing. | [#710](https://github.com/bbottema/simple-java-mail/issues/710), [#723](https://github.com/bbottema/simple-java-mail/issues/723) |
| Recipient rejection handling | Per-email choice to continue for accepted recipients or withhold content after a known rejection, using defaults/overrides and locks. Partial acceptance still fails the operation and retains its receipt. | [#754](https://github.com/bbottema/simple-java-mail/issues/754) |
| Execution and overload | Explicit `sync()`/`async()` views, optional bounded async admission, saturation diagnostics and graceful draining. | [#734](https://github.com/bbottema/simple-java-mail/issues/734), [#725](https://github.com/bbottema/simple-java-mail/issues/725) |
| Connection failover and recovery | Opt-in relay failover with or without pooling, configurable connection attempts and primary rechecks, coordinated recovery, nested routes and bounded connection history. No automatic replay after provider invocation. | [#755](https://github.com/bbottema/simple-java-mail/issues/755) |
| Safe mail-send retries | Opt-in finite attempts, delay/backoff/jitter, bounded replay memory and suspended work, unchanged message/envelope replay, and one final outcome with optional submission history. Retries stay on the selected destination and exclude partial/uncertain acceptance. | [#756](https://github.com/bbottema/simple-java-mail/issues/756) |
| Selected-Mailer ownership | Clustered sends use the selected member's Email templates, validation and message-security configuration, starting from the original Email. Duplicate live Session/Properties ownership is rejected; individual async preparation runs on executor execution. | [#757](https://github.com/bbottema/simple-java-mail/issues/757) |
| Cancellation and deadlines | Requests reach supported acquisition and transport paths, with fenced pooled leases and outcomes that retain uncertainty or already observed acceptance. | [#726](https://github.com/bbottema/simple-java-mail/issues/726) |
| Managed transport resources | The reviewed follow-up makes omitted custom-factory fallback fail closed, closes failed TLS handoffs, and shares write-timeout scheduling by owned Session and physical-connection lifetime. Existing custom-factory trust checks remain intact. | [#726](https://github.com/bbottema/simple-java-mail/issues/726), [#735](https://github.com/bbottema/simple-java-mail/issues/735), [#733](https://github.com/bbottema/simple-java-mail/issues/733) |
| Capability negotiation | Dedicated probes, per-message SMTPUTF8/8BITMIME decisions, verified-legacy compatibility opt-in, and SIZE checks against reliable advertised maxima. | [#733](https://github.com/bbottema/simple-java-mail/issues/733), [#742](https://github.com/bbottema/simple-java-mail/issues/742), [#748](https://github.com/bbottema/simple-java-mail/issues/748) |
| DSN and onward TLS | ENVID correlation, recipient/group NOTIFY preferences, automatic ORCPT from the actual envelope, and per-email REQUIRETLS with explicit unsupported behavior. | [#736](https://github.com/bbottema/simple-java-mail/issues/736), [#738](https://github.com/bbottema/simple-java-mail/issues/738), [#741](https://github.com/bbottema/simple-java-mail/issues/741) |
| Message integrity | Exact EML submission through the ordinary infrastructure, offline rehearsal, and independent checks of DKIM, S/MIME and OpenPGP content. | [#713](https://github.com/bbottema/simple-java-mail/issues/713), [#709](https://github.com/bbottema/simple-java-mail/issues/709), [#747](https://github.com/bbottema/simple-java-mail/issues/747) |
| Configuration and integration | Immutable snapshots, redacted value provenance, Spring/Boot integration, and factory-scoped configuration locks. | [#715](https://github.com/bbottema/simple-java-mail/issues/715), [#714](https://github.com/bbottema/simple-java-mail/issues/714), [#740](https://github.com/bbottema/simple-java-mail/issues/740) |
| Operating a mail workload | Measurements of actual sends, configurable message/recipient sending limits, and internal content-inspection/provider-discovery optimizations. | [#750](https://github.com/bbottema/simple-java-mail/issues/750), [#751](https://github.com/bbottema/simple-java-mail/issues/751), [#749](https://github.com/bbottema/simple-java-mail/issues/749) |
| Optional application telemetry | Typed adaptation of the application's SDK, ordinary Mailer/property configuration, request-linked logical/invocation spans, portable phase timings/failure location, useful duration histograms and sampling-independent metrics. Accepted for unreleased 10.0.0, with private-by-default endpoint reporting. | [#762](https://github.com/bbottema/simple-java-mail/issues/762) |

These capabilities work together. An application can configure and rehearse mail, submit it through managed resources, inspect what SMTP accepted, and decide what to do after a failure without treating every exception as proof that nothing was sent.

An advertised extension in a probe is not evidence that Simple Java Mail implements that sending mode. In particular, PIPELINING and CHUNKING must not be counted as delivered capabilities merely because a server advertises them.

## What the recent additions contribute

### Connection failover and primary recovery

An application can configure a failover Mailer and policy instead of wrapping every send in its own connection-recovery loop. Under the default one-attempt policy, eligible B can take over immediately if A cannot provide a usable connection. Later sends reconsider A under the configured interval/backoff; a working B is not throttled by A's recheck schedule. One operation owns a due recheck, and stale or cancelled observations cannot overwrite newer recovery information.

This works for ordinary synchronous and asynchronous sends with or without pooling. Nested relationships share the initiating operation's deadline, cancellation and completion. A lazy batch can move its untouched remainder back to a recovered primary between emails; an explicitly retained open-connection callback stays pinned and runs once. Busy pools, executor pressure and sending-limit waits retain their normal behavior rather than being mistaken for outages.

The failover boundary is deliberately narrower than general delivery retry. Once provider or CustomMailer invocation begins, failover never replays the email through another destination. A separately enabled retry policy can repeat a known-safe temporary refusal on the same selected destination, never partial acceptance, a lost final reply or cleanup failure. Authentication and certificate-verification failures each require a separate failover opt-in; these permissions never disable the next destination's security checks.

Operational logs and optional bounded connection history explain failed, skipped and acquired destinations without requiring a separate listener. Named configuration, scoped locks and selected-destination sending limits use the same route ownership. Mail-send retry/backoff follows the separate contract in [ADR 0030](../adr/0030-safe-bounded-mail-send-retries.md), implemented under [#756](https://github.com/bbottema/simple-java-mail/issues/756). It does not expand failover's connection-only safety boundary.

See [ADR 0029](../adr/0029-smtp-submission-failover.md), the [maintained architecture](../failover/architecture.md) and the [verification record](755-failover-state-refactor-verification.md). As checked on 2026-10-12, #755 is closed and Done on the 10.0.0 project board; Simple Java Mail 10.0.0 itself remains unreleased.

### Selected-Mailer configuration and ownership

The related #757 delivery fixes an existing clustered-send defect, not just a failover edge case. Invoking A while the pool selects B now applies B's ordinary defaults, overrides, validation and message-security configuration. Preparation starts from the original Email, so A's ordinary headers, sender or BCC do not contaminate B's submission. Applicable mandatory message locks still survive routing; a required archive recipient can coexist with B's own maintainer BCC.

This makes server-specific headers and other destination-specific behavior useful in practice, rather than merely configurable. The selected SMTP configuration also owns its credentials, Session and sending allowance, while the initiating Mailer retains execution, the observer and logical completion.

Two compatibility changes are recorded alongside the fix. Individual asynchronous preparation now runs on executor execution, so operational validation failures complete the returned handle exceptionally. A second live Mailer cannot claim the same Session or Properties and replace another owner's configuration; reuse by the same Mailer remains supported, and reassignment is possible after successful completed shutdown. These are documented behavioral changes, not additional protocol capabilities.

As checked on 2026-10-12, [#757](https://github.com/bbottema/simple-java-mail/issues/757) is closed and Done. Its regressions and ownership corrections are linked from the [failover documentation directory](../failover/README.md).

### Locked configuration

An application adopting centrally supplied configuration can customize ordinary settings without accidentally displacing selected fixed requirements. Locks reuse the existing property names and types, apply through the factory and its Mailers, and reject incompatible operations rather than silently ignoring a requirement or rewriting exact content.

This addresses configuration drift, not hostile application code. A deliberately independent configuration or transport remains possible. See [ADR 0025](../adr/0025-factory-scoped-locked-configuration.md) and the [verification record](740-locked-configuration-verification.md).

### Measurements of actual sends

The existing observer now explains preparation, scheduling, deliberate rate waiting, connection acquisition, MIME preparation, submission and cleanup. Measured zero differs from work not measured. A produced acceptance receipt remains available to the observer if later cleanup fails.

This answers questions such as "Was this slow because it waited for a worker, a sending limit, a connection or the submission itself?" without another listener or a probe before every send. It does not pretend to separate DNS, TLS, authentication or each SMTP command. See [ADR 0026](../adr/0026-actual-send-diagnostics.md).

### Live progress and supported SMTP stages

Live progress answers a different question: "What is my email doing right now?" An application can register a progress observer alongside its
final-result observer, show waiting, preparation and submission work, and display available upload counts. One observation ID joins the updates
and final outcome across failover and retries without changing Message-ID or SMTP traffic. The dispatcher bounds pending reporting and does not
make sending wait for a slow dashboard. Upload completion still does not establish SMTP acceptance or final delivery.

This addresses the user-facing transfer-progress gap identified against MailKit, while joining it to SJM's existing rate limits, failover, retries
and result model. It does not close the protocol-efficiency gap or establish feature parity: managed Angus supplies detailed upload evidence;
other providers can remain coarse. The #761 extension adds safe categories for greeting, capability exchange, STARTTLS, authentication, sender/recipients
and DATA permission without debug transcripts. It attributes setup only to the email that caused it; reused/background/shared connections do not replay
historical work. This improves live explanations around an already integrated sending workflow, not protocol throughput or final-delivery guarantees.
Isolated DNS/TCP/proxy/implicit-TLS observations remain separate research. Packaged telemetry is the accepted #762 implementation below.
No competitor capabilities were re-researched for this local implementation update. See [ADR 0032](../adr/0032-live-mail-send-progress.md),
[ADR 0033](../adr/0033-detailed-mail-send-progress.md), and the [hook research and verification record](mail-send-progress-verification.md).

### Optional OpenTelemetry integration

Applications can register their existing OpenTelemetry instance instead of rebuilding trace and metric adapters around callbacks, or enable
the optional module through factory properties using the application's global instance. Logical-send spans retain request parentage through
async work, failover and retries, while invocation children preserve failed intermediate attempts and
accepted submissions followed by cleanup failure. Counts and portable timings are recorded at authoritative lifecycle boundaries, not inferred
from lossy progress or an observer executor that may reject a callback.

The logical span now explains where that send spent its time, using the same eight portable measurements as result diagnostics, with the
primary failure's location when known. Duration histogram advice preserves sub-second distinctions and longer sending/retry waits; the
application still owns aggregation, collection/export intervals and sampling. Progress, final outcomes and telemetry remain independent
facilities with one observation ID for optional correlation, not three competing lifecycle owners.

This closes the comparison track's packaged application-observability gap: applications can connect SMTP work to existing operational tooling
without translating lossy callbacks or rebuilding retry/failover correlation. Its contribution is integrated operation/attempt semantics,
convenient factory configuration and explicit SDK ownership, not a claim to more SMTP extensions or uniquely available tracing. Endpoints are opt-in,
metric labels remain bounded, no message identities/content are exported, and SMTP bytes are unchanged. Agent coexistence follows public
OpenTelemetry guidance and is tested separately; lower-level spans from other instrumentation are not universally suppressed.

See [ADR 0034](../adr/0034-optional-opentelemetry-integration.md) and its
[research and verification record](762-opentelemetry-integration-verification.md). No competitor capability was re-researched for this update.

### Message and recipient sending limits

Users can express their service's restrictions as counts over periods instead of coordinating sleeps across workers, Mailers and batches. Named groups share allowance within a factory, and optional spreading avoids bursts. Ordinary waits happen before borrowing a connection; dedicated batch/open-connection scopes retain their intentional shared connection.

These count local attempted provider submissions. They do not establish account-wide quota compliance across processes, persistent history, server-observed arrival timing or final delivery. See [ADR 0027](../adr/0027-factory-scoped-sending-limits.md) and the [verification record](sending-limits-verification.md).

### Per-email recipient rejection handling

Applications can now choose what a recipient rejection means for that email without finding a provider-specific property. If Alice and Bob are
accepted at RCPT TO while Carol is rejected, `withSendingToAcceptedRecipients(true)` permits content submission to Alice and Bob;
`false` withholds content for everyone. Both behaviors retain failed completion when a recipient is rejected. A `PARTIALLY_ACCEPTED` receipt
means the server accepted the content for some recipients, not merely that their RCPT replies were positive.

This joins the input choice to the existing result model: an application can permit useful partial progress and still inspect who was accepted
before deciding whether to retry. Defaults, overrides and locks use the same Email setting; exact/protected content is not rewritten.
An unset choice preserves existing behavior. Requiring all recipients is not atomic delivery and cannot prevent later bounces.

Provider support remains explicit: a requested choice is honored or rejected before submission, and conflicting advanced Angus settings are
reported rather than silently weakening it. This closes an API-convenience gap, not a protocol-breadth gap. See
[ADR 0028](../adr/0028-per-email-recipient-rejection-handling.md) and the [verification record](754-recipient-rejection-verification.md).

### Managed transport resource ownership

The next characterization pass found two resource-management gaps and an unsafe default, rather than another missing public feature.
The local correction closes an already-connected socket when a custom SSL factory fails before handing its wrapper back to Angus. It also
shares one lazy write-timeout worker across an owned Session's physical connections, releasing it only after the last connection is disposed.
A healthy pool return or raw socket abort is not complete transport disposal. Failed setup, reconnects, private probes and selected clustered
Sessions use the same ownership boundary. Application-supplied schedulers remain application-owned.

Custom factory selection, explicit fallback choices and TLS decisions remain intact. Omitted fallback now fails closed instead of silently
switching factories. Caller-owned Sessions and alternate providers retain their existing ownership; custom factories do not acquire new
cancellation capabilities merely because their failure cleanup is protected. Class-based SSL factories use public entry points and respect
module exports.

This strengthens the operational experience without adding another timeout abstraction or claiming broader SMTP support. See the
[characterization and follow-up report](SMTP_TRANSPORT_OWNERSHIP_CHARACTERIZATION.md) and
[implementation/verification record](726-managed-angus-resource-verification.md). These corrections are reviewed development-branch work,
not a published release or part of the earlier hosted conformance run.

Together, these additions remove useful application plumbing while retaining a clear account of what the library can and cannot control.

## Position against the principal competitors

The competitor capabilities below are documented facts. The comparison with Simple Java Mail is a qualitative assessment of the implemented branch, not a throughput result or a universal ordering.

| Benchmark | Documented strength | Assessment of Simple Java Mail 10.0.0 |
| --- | --- | --- |
| MailKit and MimeKit for .NET | Broad modern ESMTP, sync/async operations, cancellation, transfer progress, authentication controls and substantial MIME/cryptographic support. | MailKit remains the protocol-breadth benchmark. SJM's comparative strength is the integrated application machinery around sending: managed pools, admission, shared limits, configuration governance, relay recovery and interpreted submission results. Failover does not close the extension or transfer-progress gaps. |
| Nodemailer for Node.js | Managed SMTP pools, capacity notifications, rate limiting and DSN options. | SJM competes seriously on operational convenience, combining message/recipient limits and factory-scoped sharing with cancellation/deadlines, configured relay recovery and typed uncertainty/retry guidance. This is an assessment of the integrated workflow, not proof of better performance or uniquely available individual features. |
| Symfony Mailer for PHP | Framework integration, Messenger-backed asynchronous jobs, transport failover, events and testing facilities. | SJM now supplies both relay failover and bounded local retries for safely refused temporary submissions. That removes more application orchestration, but is not equivalent to Messenger-backed durable workflows. Persistent jobs, recipient-subset retries and broader business recovery remain application-owned. |
| Vert.x Mail Client for Java | Native asynchronous execution, shared connection pools, dynamic credentials and negotiated PIPELINING. | Vert.x retains a different execution architecture and a protocol-efficiency advantage. SJM offers broader message-management and outcome-reporting facilities, but worker-dispatched blocking SMTP is not event-loop-native I/O. |

Sources: [MailKit capabilities](https://mimekit.net/docs/html/T_MailKit_Net_Smtp_SmtpCapabilities.htm), [MailKit client API](https://mimekit.net/docs/html/T_MailKit_Net_Smtp_SmtpClient.htm), [MimeKit](https://github.com/jstedfast/MimeKit), [Nodemailer pooling](https://nodemailer.com/smtp/pooled), [Nodemailer DSN](https://nodemailer.com/message/dsn), [Symfony Mailer](https://symfony.com/doc/current/mailer.html), and [Vert.x Mail Client](https://vertx.io/docs/vertx-mail-client/java/), consulted for the 2026-10-04 baseline. The SMTP capability, pooling, Symfony high-availability and Vert.x PIPELINING sections were rechecked on 2026-10-12; the other baseline findings retain their original date.

The competitive gain is operational resilience joined to honest outcomes, not protocol throughput. Symfony's [high-availability documentation](https://symfony.com/doc/current/mailer.html#high-availability) describes trying the next transport for the same delivery after failure. SJM instead continues only after classified connection-acquisition failures and stops automatic routing at provider invocation. That is a deliberate scope difference, not evidence that another implementation necessarily duplicates mail or that SJM offers universally superior recovery.

The wider field should not become a collection of weak comparison targets. For example, [lettre](https://docs.rs/lettre/latest/lettre/transport/smtp/struct.SmtpTransport.html) provides straightforward secure transport constructors, [go-mail](https://github.com/wneessen/go-mail) has considerable authentication and composition breadth, [aiosmtplib](https://aiosmtplib.readthedocs.io/en/stable/reference.html) exposes asynchronous SMTP and structured refusal errors, and [PHPMailer](https://github.com/PHPMailer/PHPMailer) supports common composition, SMTPUTF8 and signing needs. Core MIME, TLS, asynchronous sending and signing are not unique to SJM.

## Strongest areas of differentiation

The strongest candidate for leadership is explaining what happened and what an application can safely do afterward. SJM distinguishes a failed library operation from SMTP rejection, partial acceptance and uncertain acceptance; preserves recipient evidence; and supplies conservative retry guidance. This is more directly useful than requiring callers to assemble those conclusions from provider exception chains or server prose. It does not mean other libraries cannot be extended to provide similar behavior.

For comparison, [MailKit's standard send method](https://mimekit.net/docs/html/M_MailKit_Net_Smtp_SmtpClient_Send_1.htm) returns the final server response text and reports failures through exceptions. SJM makes interpreted submission facts a normal return value and a shared input to observation and application retry decisions.

Message integrity is another strong position. Exact EML, rehearsal and cryptographic protection use the ordinary sending infrastructure without casually rebuilding authoritative content. Cryptographic breadth alone is not unique: MimeKit also supports DKIM, S/MIME and OpenPGP. The useful distinction is preserving content while still providing managed execution and useful submission facts.

Configuration provenance, local locks, sending limits and actual-send diagnostics strengthen the experience after initial setup. They help applications answer which value won, why a customization was rejected, why a send waited, and whether cleanup failure occurred after SMTP acceptance. The integrated workflow is the differentiator, not a claim that no competitor has any individual feature.

Recipient-rejection handling now connects that operational experience to application intent. The application chooses whether partial progress is
allowed; receipts still explain what happened and prevent a successful subset from being mistaken for a successful whole operation.

Failover extends the same approach to outages. Routing, primary recovery, cancellation, selected message policy, sending limits and connection history work within one logical operation. The selected-member fix matters here: a failover relay can use its own required header, signing configuration or maintainer BCC without losing applicable mandatory message locks. The differentiator is coherent ownership and useful explanations around recovery, not failover alone.

## Remaining differences and limits

1. **Protocol breadth and efficiency remain incomplete.** Negotiated PIPELINING and CHUNKING are unresolved under [#699](https://github.com/bbottema/simple-java-mail/issues/699), and BINARYMIME must not be presented as delivered. MailKit remains ahead on this axis. No gain from these extensions is included in this assessment.
2. **Diagnostics do not yet explain every protocol step.** Portable measurements and the accepted #760/#761 progress work cover live upload and supported SMTP categories, not a complete command timeline or per-phase authentication/TLS durations. DNS/TCP/proxy/implicit-TLS detail still needs upstream hooks. Accepted OpenTelemetry integration under #762 packages existing portable timings and authoritative lifecycle facts; it does not invent these missing measurements. [MailKit telemetry](https://github.com/jstedfast/MailKit/blob/master/Telemetry.md) and [protocol logging](https://mimekit.net/docs/html/T_MailKit_ProtocolLogger.htm) remain relevant comparisons; credential redaction alone does not make a transcript content-free.
3. **Available safeguards are not universal defaults.** Bounded async queues and total deadlines are opt-in. The default SMTP strategy remains opportunistic TLS, including when credentials are supplied; mandatory TLS requires an explicit strategy or applicable lock. These deliberate compatibility choices prevent claiming universally strongest security or overload defaults. See [TransportStrategy](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/config/TransportStrategy.java) and [AsyncQueueConfig](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/config/AsyncQueueConfig.java).
4. **Providers determine the available facts and controls.** Public outcomes are provider-neutral, but the bundled managed Angus integration supplies the richest protocol observations and physical-abort support. Caller-owned Sessions, custom socket factories, CustomMailer and third-party adapters retain explicit boundaries. Missing information is not filled with invented facts.
5. **Evidence does not establish universal superiority.** Interoperability and fault-injection results support the tested configurations. They do not establish that SJM is faster than competitors, compatible with every server, free from all scheduling races, or guaranteed to preserve downstream delivery. Cancellation is not SMTP rollback, and a total deadline cannot impose a hard completion bound on arbitrary non-cooperative application code.
6. **Local retries are not durable or general delivery recovery.** Provider invocation ends automatic routing. Opaque setup failures and unmet message/security requirements also stop routing; failover is neither capacity spillover nor a durable job queue. The separately configured retry policy under [#756](https://github.com/bbottema/simple-java-mail/issues/756) handles safely refused temporary whole-envelope submissions, not partial/uncertain acceptance, recipient-subset retries or durable delivery jobs.

## Safe mail-send retries and their evidence

The implementation and production-only review were accepted on 2026-10-14. The verification sequence includes Java 11 library and modern-JDK reactor runs, all three Spring compatibility combinations, CLI metadata/process checks, and 518 local SMTP conformance scenarios including 24 Postfix/Exim cases with ten separate independent protected-content checks. The accompanying API-boundary cleanup passed a clean modern-JDK reactor with 2,370 tests, zero failures/errors and one existing skip. Website checks, clean build, internal links and rendered state diagrams passed in their recorded lanes. The final focused review added 167 passing Java 11 tests and another website check, not a fresh full-reactor or hosted CI run. The [retry implementation record](../plans/756-safe-mail-send-retries.md#execution-evidence) and [API-boundary record](../plans/756-api-boundary-cleanup.md#verification-evidence) retain the sequence and limitations. This is accepted development-branch work, not a published SJM release.

This closes another application-orchestration gap: deliberate attempts, backoff and jitter without application sleeps or rebuilding MIME. The selected Mailer owns the schedule, while the initiator bounds waiting and frozen content. Ordinary async waits release the send worker and connection, and an observed terminal outcome contains bounded submission history. Missing evidence, partial acceptance, uncertainty and failed cleanup prohibit whole-message replay.

That strengthens SJM's integrated resilience position beyond connection failover and receipt advice: users can handle a known-safe temporary refusal without building a scheduler for each send. It does not establish unique market leadership, better throughput or parity with persistent queues. Competitor behavior was not re-researched for this implementation update; the existing comparison remains the baseline.

SMTP acceptance remains different from final delivery. Message-ID, ENVID and retry guidance do not provide exactly-once submission or a server deduplication guarantee.

## Evidence available for future assessments

The [SMTP conformance verification report](smtp-conformance-verification.md) records the hosted 2026-09-29 run: 481 embedded fault/concurrency cases, 24 Postfix/Exim interoperability cases and ten independent protected-content checks, with eight separate runner self-tests. The report links sanitized hosted evidence and identifies the tested commit and runtime. It is not evidence that every later branch addition ran in that same hosted job.

The [conformance runner guide](../../tools/smtp-conformance/README.md) provides the maintained scenarios and repeatable procedure. The locked-configuration and sending-limit verification reports retain their separate implementation evidence.

The [recipient-rejection verification record](754-recipient-rejection-verification.md) records separate local Java 11/modern-JDK verification,
Spring/CLI integration, exact/protected-content checks and pooled concurrency coverage. Its final focused lane passed 176 tests across nine suites,
including concurrent true/false/unset attempts with mixed rejections. These later additions are not claimed as part of the earlier hosted run.

Failover has its own evidence sequence: the [initial verification](755-connection-failover-verification.md), [public-boundary regression baseline](755-failover-blackbox-baseline.md) and [recovery-state/ownership verification record](755-failover-state-refactor-verification.md). They cover direct, pooled and clustered routes, no-replay boundaries, retained scopes, selected templates, cancellation/cleanup and concurrent recovery observations. The black-box baseline preserves the pre-fix defect; it is not itself a final green result.

The later record includes the released Generic Object Pool 2.6.0, Clustered Object Pool 4.3.0 and SMTP Connection Pool 4.4.0 cascade, followed by real-JDK-11 integration and classpath/JPMS checks against those published dependencies. Supporting-library publication does not release SJM 10.0.0. Modern-JDK evidence combines an initial reactor run and documented correction/completion runs, rather than claiming one newly repeated uninterrupted green reactor. Earlier Postfix/Exim and Spring-matrix results retain their own dates and tested boundaries; this report refresh reran none of them.

The [performance audit](smtp-performance/initial-audit-results.md), [inspection optimization](smtp-performance/inspection-optimization-results.md) and [provider-discovery follow-up](smtp-performance/provider-discovery-results.md) retain matched local measurements. They support internal improvements, not cross-library throughput leadership. The accepted decision retained content checks and SIZE handling without a speculative performance opt-out API.

## Carrying the research forward

The [progress verification](mail-send-progress-verification.md) and [memory-pressure results](mail-send-memory-pressure-results.md) add separate,
dated evidence for live reporting and retention. The observed pooled-success soak completed one million measured sends without accumulating
message/operation objects; deliberate-retention controls demonstrated that the workbench can expose growth. That strengthens confidence in this
integration, not a claim of leak-freedom across every path or competitive throughput leadership.

The [OpenTelemetry verification record](762-opentelemetry-integration-verification.md) adds SDK/loopback, real Java-agent, configuration,
privacy and module-boundary evidence. It distinguishes the original full reactors/conformance run from later targeted revision checks.
The final holistic lane passed 101 focused tests; delivery verification then passed 118 Java 21 tests and 114 real-Java-11 tests after
correcting a stale reflective test helper. Packaged consumers, Javadocs, all 84 website checks and 4,240 internal links passed. This is
local evidence, not a new hosted run, competitive benchmark or heap-profile proof.

Use the PDF as the historical baseline and this report as the dated progress assessment. The PDF's original priorities and proposed API shapes are not all current decisions; consult the accepted ADRs, source, release history and live GitHub tracking before treating a research proposal as approved or complete.

For a future refresh:

- Record the new assessment date, branch/commit and released-versus-unreleased status.
- Recheck implementation and evidence rather than equating a closed issue or accepted ADR with every requested behavior being delivered.
- Refresh primary competitor documentation, especially negative findings and protocol support. Record source versions or dates where available.
- Keep implemented capabilities, comparative judgments, reproducible results and remaining research separate. Do not turn absent information into an unsupported feature or an established deficiency.
- Decide subsequent product priorities separately. The report informs that decision; it does not prescribe it.

The conclusion at this baseline is that 10.0.0 has moved beyond a particularly comprehensive Java mail library into serious contention on cross-language SMTP usability and operability. Connection failover closes another useful application-plumbing gap, while selected-Mailer ownership makes recovery compatible with destination-specific behavior and mandatory message requirements. Live progress connects current waiting/upload/SMTP stages with the same logical send's final result; optional OpenTelemetry carries those authoritative send/attempt boundaries and portable timings into the application's existing traces and aggregate metrics. MailKit still has the stronger protocol engine at the recorded comparison baseline. SJM's increasingly distinctive strength is giving applications useful configuration, control, recovery and trustworthy explanations around SMTP submission, now with packaged operational integration instead of callback glue. PIPELINING/CHUNKING remains separate work. That is meaningful progress toward the best SMTP client experience, not a declaration that the work is finished.
