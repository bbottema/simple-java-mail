# Simple Java Mail 10.0.0 competitive progress report

- Assessment date: 2026-10-04.
- Implementation refresh: 2026-10-06, retaining recipient-rejection handling under #754 and adding the managed-transport resource corrections below. Competitor findings retain their 2026-10-04 assessment; this update did not repeat competitor research.
- Original research: [The World-Class SMTP Framework](simple-java-mail-world-class-smtp-research.pdf), dated 2026-08-29.
- Implementation baseline: `codex/10.0.0` at [`d17884ad`](https://github.com/bbottema/simple-java-mail/commit/d17884adc9dca23d92beadd8a2c8ccf8b5cd5ea5).
- Release status: development of 10.0.0 continues. The assessed capabilities are present on the development branch, not in a published release. This does not describe the capabilities of the published 9.x libraries.
- Method: repository and evidence review, supplemented by current official competitor documentation. No new cross-library benchmark or black-box comparison was run for this report.

This is the companion to the original PDF. Keep the two together: the PDF records the initial comparison and research agenda; this report explains what has changed, where Simple Java Mail now compares well, and where the original gaps remain. Its comparisons are assessments, not independently measured rankings.

The conclusion is that **Simple Java Mail 10.0.0 is now a credible contender for the strongest overall SMTP client experience across the surveyed ecosystems, but not yet the most complete SMTP protocol engine**. Users experience configuration, message handling, resource management and failure reporting together, not just the SMTP commands a transport supports.

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
| Cancellation and deadlines | Requests reach supported acquisition and transport paths, with fenced pooled leases and outcomes that retain uncertainty or already observed acceptance. | [#726](https://github.com/bbottema/simple-java-mail/issues/726) |
| Managed transport resources | The reviewed follow-up makes omitted custom-factory fallback fail closed, closes failed TLS handoffs, and shares write-timeout scheduling by owned Session and physical-connection lifetime. Existing custom-factory trust checks remain intact. | [#726](https://github.com/bbottema/simple-java-mail/issues/726), [#735](https://github.com/bbottema/simple-java-mail/issues/735), [#733](https://github.com/bbottema/simple-java-mail/issues/733) |
| Capability negotiation | Dedicated probes, per-message SMTPUTF8/8BITMIME decisions, verified-legacy compatibility opt-in, and SIZE checks against reliable advertised maxima. | [#733](https://github.com/bbottema/simple-java-mail/issues/733), [#742](https://github.com/bbottema/simple-java-mail/issues/742), [#748](https://github.com/bbottema/simple-java-mail/issues/748) |
| DSN and onward TLS | ENVID correlation, recipient/group NOTIFY preferences, automatic ORCPT from the actual envelope, and per-email REQUIRETLS with explicit unsupported behavior. | [#736](https://github.com/bbottema/simple-java-mail/issues/736), [#738](https://github.com/bbottema/simple-java-mail/issues/738), [#741](https://github.com/bbottema/simple-java-mail/issues/741) |
| Message integrity | Exact EML submission through the ordinary infrastructure, offline rehearsal, and independent checks of DKIM, S/MIME and OpenPGP content. | [#713](https://github.com/bbottema/simple-java-mail/issues/713), [#709](https://github.com/bbottema/simple-java-mail/issues/709), [#747](https://github.com/bbottema/simple-java-mail/issues/747) |
| Configuration and integration | Immutable snapshots, redacted value provenance, Spring/Boot integration, and factory-scoped configuration locks. | [#715](https://github.com/bbottema/simple-java-mail/issues/715), [#714](https://github.com/bbottema/simple-java-mail/issues/714), [#740](https://github.com/bbottema/simple-java-mail/issues/740) |
| Operating a mail workload | Measurements of actual sends, configurable message/recipient sending limits, and internal content-inspection/provider-discovery optimizations. | [#750](https://github.com/bbottema/simple-java-mail/issues/750), [#751](https://github.com/bbottema/simple-java-mail/issues/751), [#749](https://github.com/bbottema/simple-java-mail/issues/749) |

These capabilities work together. An application can configure and rehearse mail, submit it through managed resources, inspect what SMTP accepted, and decide what to do after a failure without treating every exception as proof that nothing was sent.

An advertised extension in a probe is not evidence that Simple Java Mail implements that sending mode. In particular, PIPELINING and CHUNKING must not be counted as delivered capabilities merely because a server advertises them.

## What the recent additions contribute

### Locked configuration

An application adopting centrally supplied configuration can customize ordinary settings without accidentally displacing selected fixed requirements. Locks reuse the existing property names and types, apply through the factory and its Mailers, and reject incompatible operations rather than silently ignoring a requirement or rewriting exact content.

This addresses configuration drift, not hostile application code. A deliberately independent configuration or transport remains possible. See [ADR 0025](../adr/0025-factory-scoped-locked-configuration.md) and the [verification record](740-locked-configuration-verification.md).

### Measurements of actual sends

The existing observer now explains preparation, scheduling, deliberate rate waiting, connection acquisition, MIME preparation, submission and cleanup. Measured zero differs from work not measured. A produced acceptance receipt remains available to the observer if later cleanup fails.

This answers questions such as "Was this slow because it waited for a worker, a sending limit, a connection or the submission itself?" without another listener or a probe before every send. It does not pretend to separate DNS, TLS, authentication or each SMTP command. See [ADR 0026](../adr/0026-actual-send-diagnostics.md).

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
| MailKit and MimeKit for .NET | Broad modern ESMTP, sync/async operations, cancellation, transfer progress, authentication controls and substantial MIME/cryptographic support. | MailKit remains the protocol-breadth benchmark. SJM's comparative strength is the integrated application machinery around sending: managed pools, admission, shared limits, configuration governance and interpreted submission results. |
| Nodemailer for Node.js | Managed SMTP pools, capacity notifications, rate limiting and DSN options. | SJM now competes seriously on operational convenience, adding message and recipient limits, explicit factory-scoped sharing, cancellation/deadline semantics and typed uncertainty/retry guidance. |
| Symfony Mailer for PHP | Framework integration, Messenger-backed asynchronous jobs, transport failover, events and testing facilities. | Symfony remains stronger at ready-made framework workflows. SJM has substantial Java integration and configuration support, while deliberately leaving durable jobs and retry orchestration to applications. |
| Vert.x Mail Client for Java | Native asynchronous execution, shared connection pools, dynamic credentials and negotiated PIPELINING. | Vert.x retains a different execution architecture and a protocol-efficiency advantage. SJM offers broader message-management and outcome-reporting facilities, but worker-dispatched blocking SMTP is not event-loop-native I/O. |

Sources: [MailKit capabilities](https://mimekit.net/docs/html/T_MailKit_Net_Smtp_SmtpCapabilities.htm), [MailKit client API](https://mimekit.net/docs/html/T_MailKit_Net_Smtp_SmtpClient.htm), [MimeKit](https://github.com/jstedfast/MimeKit), [Nodemailer pooling](https://nodemailer.com/smtp/pooled), [Nodemailer DSN](https://nodemailer.com/message/dsn), [Symfony Mailer](https://symfony.com/doc/current/mailer.html), and [Vert.x Mail Client](https://vertx.io/docs/vertx-mail-client/java/), consulted 2026-10-04.

The wider field should not become a collection of weak comparison targets. For example, [lettre](https://docs.rs/lettre/latest/lettre/transport/smtp/struct.SmtpTransport.html) provides straightforward secure transport constructors, [go-mail](https://github.com/wneessen/go-mail) has considerable authentication and composition breadth, [aiosmtplib](https://aiosmtplib.readthedocs.io/en/stable/reference.html) exposes asynchronous SMTP and structured refusal errors, and [PHPMailer](https://github.com/PHPMailer/PHPMailer) supports common composition, SMTPUTF8 and signing needs. Core MIME, TLS, asynchronous sending and signing are not unique to SJM.

## Strongest areas of differentiation

The strongest candidate for leadership is explaining what happened and what an application can safely do afterward. SJM distinguishes a failed library operation from SMTP rejection, partial acceptance and uncertain acceptance; preserves recipient evidence; and supplies conservative retry guidance. This is more directly useful than requiring callers to assemble those conclusions from provider exception chains or server prose. It does not mean other libraries cannot be extended to provide similar behavior.

For comparison, [MailKit's standard send method](https://mimekit.net/docs/html/M_MailKit_Net_Smtp_SmtpClient_Send_1.htm) returns the final server response text and reports failures through exceptions. SJM makes interpreted submission facts a normal return value and a shared input to observation and application retry decisions.

Message integrity is another strong position. Exact EML, rehearsal and cryptographic protection use the ordinary sending infrastructure without casually rebuilding authoritative content. Cryptographic breadth alone is not unique: MimeKit also supports DKIM, S/MIME and OpenPGP. The useful distinction is preserving content while still providing managed execution and useful submission facts.

Configuration provenance, local locks, sending limits and actual-send diagnostics strengthen the experience after initial setup. They help applications answer which value won, why a customization was rejected, why a send waited, and whether cleanup failure occurred after SMTP acceptance. The integrated workflow is the differentiator, not a claim that no competitor has any individual feature.

Recipient-rejection handling now connects that operational experience to application intent. The application chooses whether partial progress is
allowed; receipts still explain what happened and prevent a successful subset from being mistaken for a successful whole operation.

## Remaining differences and limits

1. **Protocol breadth and efficiency remain incomplete.** Negotiated PIPELINING and CHUNKING are unresolved under [#699](https://github.com/bbottema/simple-java-mail/issues/699), and BINARYMIME must not be presented as delivered. MailKit remains ahead on this axis. No gain from these extensions is included in this assessment.
2. **Diagnostics do not yet explain every protocol step.** Portable send measurements are useful, but they are not an actual-send command timeline, upload-progress API or detailed authentication/TLS timing report. [MailKit telemetry](https://github.com/jstedfast/MailKit/blob/master/Telemetry.md) and [protocol logging](https://mimekit.net/docs/html/T_MailKit_ProtocolLogger.htm) remain relevant comparisons; credential redaction alone does not make a transcript content-free.
3. **Available safeguards are not universal defaults.** Bounded async queues and total deadlines are opt-in. The default SMTP strategy remains opportunistic TLS, including when credentials are supplied; mandatory TLS requires an explicit strategy or applicable lock. These deliberate compatibility choices prevent claiming universally strongest security or overload defaults. See [TransportStrategy](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/config/TransportStrategy.java) and [AsyncQueueConfig](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/config/AsyncQueueConfig.java).
4. **Providers determine the available facts and controls.** Public outcomes are provider-neutral, but the bundled managed Angus integration supplies the richest protocol observations and physical-abort support. Caller-owned Sessions, custom socket factories, CustomMailer and third-party adapters retain explicit boundaries. Missing information is not filled with invented facts.
5. **Evidence does not establish universal superiority.** Interoperability and fault-injection results support the tested configurations. They do not establish that SJM is faster than competitors, compatible with every server, free from all scheduling races, or guaranteed to preserve downstream delivery. Cancellation is not SMTP rollback, and a total deadline cannot impose a hard completion bound on arbitrary non-cooperative application code.

SMTP acceptance remains different from final delivery. Message-ID, ENVID and retry guidance do not provide exactly-once submission or a server deduplication guarantee.

## Evidence available for future assessments

The [SMTP conformance verification report](smtp-conformance-verification.md) records the hosted 2026-09-29 run: 481 embedded fault/concurrency cases, 24 Postfix/Exim interoperability cases and ten independent protected-content checks, with eight separate runner self-tests. The report links sanitized hosted evidence and identifies the tested commit and runtime. It is not evidence that every later branch addition ran in that same hosted job.

The [conformance runner guide](../../tools/smtp-conformance/README.md) provides the maintained scenarios and repeatable procedure. The locked-configuration and sending-limit verification reports retain their separate implementation evidence.

The [recipient-rejection verification record](754-recipient-rejection-verification.md) records separate local Java 11/modern-JDK verification,
Spring/CLI integration, exact/protected-content checks and pooled concurrency coverage. Its final focused lane passed 176 tests across nine suites,
including concurrent true/false/unset attempts with mixed rejections. These later additions are not claimed as part of the earlier hosted run.

The [performance audit](smtp-performance/initial-audit-results.md), [inspection optimization](smtp-performance/inspection-optimization-results.md) and [provider-discovery follow-up](smtp-performance/provider-discovery-results.md) retain matched local measurements. They support internal improvements, not cross-library throughput leadership. The accepted decision retained content checks and SIZE handling without a speculative performance opt-out API.

## Carrying the research forward

Use the PDF as the historical baseline and this report as the dated progress assessment. The PDF's original priorities and proposed API shapes are not all current decisions; consult the accepted ADRs, source, release history and live GitHub tracking before treating a research proposal as approved or complete.

For a future refresh:

- Record the new assessment date, branch/commit and released-versus-unreleased status.
- Recheck implementation and evidence rather than equating a closed issue or accepted ADR with every requested behavior being delivered.
- Refresh primary competitor documentation, especially negative findings and protocol support. Record source versions or dates where available.
- Keep implemented capabilities, comparative judgments, reproducible results and remaining research separate. Do not turn absent information into an unsupported feature or an established deficiency.
- Decide subsequent product priorities separately. The report informs that decision; it does not prescribe it.

The conclusion at this baseline is that 10.0.0 has moved beyond a particularly comprehensive Java mail library into serious contention on cross-language SMTP usability and operability. MailKit still has the stronger protocol engine; SJM's increasingly distinctive strength is giving applications useful configuration, control and trustworthy explanations around SMTP submission. That is meaningful progress toward the best SMTP client experience, not a declaration that the work is finished.
