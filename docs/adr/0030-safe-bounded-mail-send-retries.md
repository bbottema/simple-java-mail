# ADR 0030: Safe, bounded mail-send retries

- Status: **Accepted**.
- Decision date: 2026-10-12.
- Target: 10.0.0.
- Implementation: **Implemented for 10.0.0**.
- Tracking: [#756](https://github.com/bbottema/simple-java-mail/issues/756), covering the ADR research and implementation.

The [retry concurrency guide](../concurrency/11-mail-send-retries.md) describes the current API, resource limits and lifecycle.
The completed [implementation record](https://github.com/bbottema/simple-java-mail/blob/45c8182b6f52429424ae08a28490bd708e4a4881/docs/plans/756-safe-mail-send-retries.md)
preserves historical verification evidence.

## Context

An SMTP service can temporarily refuse an email that it has not accepted. Applications currently have to interpret the submission
receipt, retain their job and message, schedule another attempt, and stop when another attempt would be unsafe. Optional built-in
retrying would remove that repeated orchestration. It must not turn every exception into permission to send the email again.

[Connection failover](0029-smtp-submission-failover.md) answers a different question: which approved destination can provide a usable
connection? Its current implementation does not replay an email after provider invocation. This decision adds a separately configured
submission-retry contract; it neither expands #755 retrospectively nor reopens that completed issue.

The distinction matters even when one server handles all the mail. A mailbox is the recipient's mail destination, not the SMTP relay.
A relay can temporarily refuse Alice while continuing to accept mail for Bob. SMTP has separate recipient replies, and enhanced
status codes distinguish mailbox problems from mail-system problems. A refusal therefore does not establish that every message
through that relay should be delayed. See [RFC 5321, section 3.3](https://www.rfc-editor.org/rfc/rfc5321.html#section-3.3)
and [RFC 3463, section 3.3](https://www.rfc-editor.org/rfc/rfc3463.html#section-3.3).

Once a relay accepts responsibility for a message, onward-delivery retries belong to that server. A lost final reply can leave the
client uncertain whether acceptance happened. Neither case is permission for an automatic resend by Simple Java Mail.
See [RFC 5321, section 4.2.5](https://www.rfc-editor.org/rfc/rfc5321.html#section-4.2.5).

## Decision drivers

- Make temporary submission failures easier to handle through the existing synchronous and asynchronous send APIs.
- Require evidence of safe replay, rather than inferring non-acceptance from an exception or missing receipt.
- Preserve the selected Mailer's content, envelope, security, locks and sending-limit ownership.
- Keep one logical send, one public completion and one final observer outcome, while making intermediate attempts explainable.
- Release ordinary asynchronous send workers during backoff without hiding an unbounded backlog elsewhere.
- Keep cancellation, deadlines, cleanup and graceful draining effective across every retry transition.
- Leave durable delivery jobs and wider retry decisions with applications that need them.

## Decision

### Retry only a safe, temporarily refused full envelope

Automatically retry only when structured provider facts establish both safe replay of the entire unchanged submission envelope
and a temporary submission failure. `SAFE_TO_RETRY_ALL` is necessary guidance, not sufficient eligibility by itself. A receipt can
describe known unsent recipients without proving that another unchanged attempt would remedy the failure.

Stop on permanent rejection, partial acceptance, uncertain acceptance, insufficient evidence, local configuration errors,
cancellation or deadline expiry. `SAFE_TO_RETRY_UNACCEPTED` does not authorize whole-envelope replay. Recipient-subset retries are
not part of this initial feature. Do not interpret arbitrary response prose as a portable retry classification or delay.

An unsuccessful operation is not necessarily an unsuccessful SMTP submission. In particular, acceptance followed by failed
cleanup must never trigger another submission. A missing receipt or a thrown exception is not proof that the message was unsent.
Preserve the acceptance and retry guidance in [ADR 0011](0011-transport-neutral-submission-outcomes.md).

Do not automatically reinvoke an opaque `CustomMailer` callback. Logging-only operations perform no SMTP retry. Third-party
providers need sufficient structured evidence and repeatable-content support; when either is absent, fail normally rather than guess.

### Keep the selected destination and prepared content

Initial connection acquisition still honors configured connection failover. Once a destination starts submission, retries stay
with that selected Mailer. A retry may obtain a fresh connection there, but must not restart cluster selection or traverse another
failover route. Switching relays can change templates, recipients and security; it is not an unchanged replay.

Preserve the selected Mailer's effective configuration, all applicable message locks and the selected sending allowance. Do not
reapply another candidate's templates or relax a requirement to make the retry succeed.

Before the first submission, prepare a private repeatable message snapshot for the retry-enabled operation. Reuse its MIME content,
signatures, exact bytes, Message-ID, envelope requirements and advanced provider options. Do not reread changing attachment sources,
regenerate MIME boundaries or repeat signing merely because a submission is retried. The envelope retains all recipient occurrences.

Generated ENVID remains transaction-specific; an explicitly fixed identifier remains fixed. Neither ENVID nor Message-ID promises
server deduplication. These meanings follow [ADR 0022](0022-dsn-identifiers-belong-to-send-attempts.md).

Replay storage is private to the logical operation, with bounded ownership and cleanup on every terminal path. Storing repeatable
content has memory, storage, latency and sensitive-data costs. The implementation plan must settle storage thresholds and limits
before coding; this ADR does not approve unbounded retention or a durable spool. With retrying disabled, preserve the ordinary path
and its preparation costs.

### Keep one logical operation and a bounded waiting collection

One public send remains one logical operation, with one completion and one final `MailSendResultObserver` callback. Intermediate
submission failures are not terminal outcomes. Record attempts, delays and exhaustion in bounded history and useful logging;
do not introduce per-attempt callbacks or publish the Email, content or transport through observation.

During an ordinary asynchronous retry delay, release the send worker. Keep suspended retries in an explicitly bounded collection
owned by the initiating Mailer. The existing executor queue does not bound work that has left that executor to wait for a retry.
Do not substitute an unbounded timer queue for blocked workers. If waiting capacity is unavailable, finish with the genuine send
failure and explain why no further retry was scheduled. Do not replace SMTP facts with a fictional rejection or retry success.

Ordinary retry waits hold no borrowed connection or started proxy resource. Complete failed-attempt cleanup before scheduling
another attempt. Keep the same admitted operation active while it waits, so graceful shutdown drains it rather than abandoning it.
Cancellation removes pending continuations and prevents later submission; stale timer/resumption work must become inert.

Retry delays consume the existing overall send deadline and never renew it. Charge each provider invocation to the actual selected
sending allowance. An unused reservation is still released before invocation, following the existing accounting contract.

Extend the existing private operation/attempt state machines to distinguish a recoverable intermediate failure from terminal
failure. Do not recursively call a public send method, readmit the operation, create another observer outcome, or freeze the logical
operation's failure at the first retryable refusal. Final failure and secondary cleanup failures retain their established ordering
and genuine receipt/exception identity.

Synchronous sends wait on the caller's thread. Intentional simple-batch and open-connection scopes retain their connection ownership
and execution thread during retry waiting; document the server idle-timeout risk. Preserve one lazy batch iterator and stop at its
first terminal failure. Keep the destination pinned and invoke an application open-connection callback exactly once.

Do not automatically introduce relay-wide cooldown after a refused message. A busy mailbox must not pause unrelated recipients.
A server-wide refusal can still create a backlog of pending retries: releasing workers moves waiting work, it does not remove it.
The waiting bound, finite attempts, deadlines and selected sending limits make that cost explicit.

### Configure a Mailer policy without another send family

The actual selected Mailer owns the retry schedule, because attempts/backoff can be tuned to that SMTP service. A selected Mailer
without an enabled schedule does not inherit the initiating Mailer's schedule. The initiating Mailer keeps execution, completion,
observer, overall deadline, pending-retry capacity and replay-memory limits. The accepted implementation uses bounded in-memory
replay only; no temporary files are introduced.

Keep schedule and resource limits together in `MailSendRetryPolicy`, configured through one `withMailSendRetryPolicy(...)` call.
This is one user-facing configuration concept, not separate Mailer setters for retry-only resources. Runtime ownership still differs:
the selected Mailer supplies schedule fields from its policy; the initiating Mailer supplies resource fields from its own policy.
Retry configuration follows [ADR 0003](0003-immutable-configuration-snapshots.md): explicit choices take precedence over captured
configuration properties, which take precedence over built-in fallbacks. Preserve the distinction between unset choices and explicit
resetting or disabling; supplying a new policy does not turn omitted fields into explicit fallbacks. Values do not read configuration
sources themselves. Applicable [configuration locks](0025-factory-scoped-locked-configuration.md) remain binding and conflicting choices
are rejected. Retry configuration has no separate precedence exception.

A retry schedule must be complete after configuration resolution; do not invent attempts or an initial delay when neither Java nor
properties supply them. Resource configuration alone does not enable retries when no schedule is configured, but can bound work delegated
to another retry-enabled destination. Built-in fallbacks remain centralized under ADR 0003. Numerical limits, usage examples and detailed
reset/clear behavior belong in the public API and [retry guide](../concurrency/11-mail-send-retries.md).

Use an immutable, supplied-data Mailer policy, resolved by builders. Expose resolved settings through `OperationalConfig`, not the
live waiting collection or counters. Retrying is disabled unless configured. No new Email setting, send-method family, eligibility
enum or caller-supplied exception predicate is selected.

Provide finite attempts, configurable delay, fixed or capped-increasing backoff, jitter and an explicit pending-retry bound. Use the
existing property, Spring metadata, generated CLI, configuration diagnostics and locking mechanisms. Exact policy signatures,
property keys, numerical defaults, history limits and replay-storage thresholds belong in the reviewed implementation plan.
This decision deliberately does not invent universal timing defaults or borrow an MTA's persistent delivery schedule.

### Concrete behavior

The following timelines illustrate the result contracts; they are examples, not captured output:

| Situation | Result contract |
| --- | --- |
| An email to Alice gets a temporary refusal with evidence that its full envelope is unsent. A later attempt is accepted. | Wait according to policy, replay the same prepared message through the same selected Mailer, then complete once with the final receipt and one successful observer outcome. |
| While Alice's email waits, a separate email to Bob is acceptable. | Bob can proceed; Alice's refusal does not put the relay on a global cooldown. Both submissions still obey configured sending limits. |
| A multi-recipient email is accepted for Bob but rejected for Alice. | Preserve the partial-failure receipt and failed completion. Do not resend the whole message or automatically construct an Alice-only email. |
| The final reply is lost, or acceptance is followed by a cleanup failure. | Preserve uncertainty or known acceptance and finish without automatic replay. |
| The temporary failure is eligible, but pending-retry capacity is exhausted. | Finish with the real failure and an explanation that another attempt could not be scheduled; do not wait in an unbounded collection. |

## Alternatives considered

| Alternative | Benefit | Reason not selected as the built-in default |
| --- | --- | --- |
| Application scheduler or durable job system only | Supports business rules, persistence and cross-process coordination. | Remains appropriate for those needs, but repeats ordinary safe-retry orchestration for applications that only need bounded local retrying. |
| Retry generic exceptions or every `SAFE_TO_RETRY_ALL` receipt | Small implementation and broad recovery. | Neither proves both temporary failure and safe unchanged replay; cleanup and local failures can be included. |
| Separate Mailer setters for retry-only resource limits | Makes the initiating owner visible in the setter location. | Scatters one configuration concept across the general API. Keep the fields in MailSendRetryPolicy and explain the selected-schedule/initiating-resource ownership instead. |
| Reroute and prepare a new Email for each attempt | Can use another available relay. | Can change headers, BCC, signatures, content and locks; conflates retries with connection failover. |
| Automatically retry only unaccepted recipients | Can make progress after partial acceptance. | Changes envelope/result contracts and requires separate treatment of duplicate occurrences and permanently rejected recipients. Deferred. |
| Publish an observer outcome for every attempt | Makes intermediate failures immediately visible. | Changes logical-send counting and can cause existing application retry recipes to schedule duplicate work. |
| Block ordinary async send workers during every delay | Keeps scheduling simple. | Long backoff consumes sending capacity even though no transport work is happening. Retained scopes remain an explicit exception. |
| Unbounded suspended retry collection | Releases workers without rejecting a retry. | Moves overload into retained messages and timers instead of bounding it. |
| Automatically pause an entire relay | Reduces repeated refusals during some service-wide incidents. | A refusal can be recipient-specific; it would delay unrelated successful mail and needs a separate coordination contract. |
| Rebuild content from the original Email on every attempt | Avoids replay-storage ownership. | Changing attachment sources, generated MIME and signatures can make the next attempt a different message. |

## Consequences

Users can opt into convenient local recovery without changing their send calls. Provider-neutral receipt facts remain authoritative,
and connection failover remains a separate feature. One final observer event keeps existing logical-send accounting understandable.

The implementation is more than a loop around `sendMail`. It needs repeatable content, bounded suspended operations and explicit
intermediate-versus-terminal transitions. Snapshot creation can add work and retain sensitive message data. Suspended retries
occupy waiting capacity and can lengthen graceful shutdown; synchronous and retained-scope waits still occupy an execution thread.

Conservative eligibility deliberately leaves some recoverable-looking failures with the caller. Backoff and jitter do not guarantee
provider quota compliance or prevent all backlog. Intermediate failures need history and logs because the final observer is not an
attempt-by-attempt stream. Application-owned retry recipes must not independently reschedule a logical operation that the built-in
policy is still managing.

## Boundaries

This is opt-in, in-process submission retrying, not a durable delivery queue, distributed coordinator, broker or exactly-once system.
It does not retry onward delivery, rewrite recipients, weaken TLS or locked requirements, or promise that identifiers deduplicate mail.
An application with durable business jobs, recipient-subset recovery or ambiguous acceptance still owns those decisions.

The implementation record contains commands and results. This ADR records the architectural contract, not runtime evidence or release status.

## Related decisions

| Record | Relationship |
| --- | --- |
| [0003: Configuration snapshots](0003-immutable-configuration-snapshots.md), [0004: Provenance](0004-configuration-provenance-diagnostics.md), [0005: Spring](0005-spring-integration-and-boot-compatibility.md), [0018: CLI generation](0018-cli-from-builder-contracts.md) | Reuse resolution, metadata, parsers and safe diagnostics; mutable waiting work is not configuration. |
| [0007: Provider-neutral MIME boundary](0007-provider-neutral-mime-boundary.md), [0013: Exact EML](0013-exact-eml-submission.md) | Preserve repeatable prepared content and provider options without rebuilding exact or protected bytes. |
| [0011: Submission outcomes](0011-transport-neutral-submission-outcomes.md) | Retry eligibility requires structured acceptance and temporary-failure evidence; uncertainty remains a stop condition. |
| [0012: Terminal observation](0012-terminal-send-observation.md) | Keep one final callback for one logical send and genuine final receipt/failure identity. |
| [0015: Execution and pooling](0015-execution-views-and-transport-pooling.md) | Keep execution mode separate from connections; ordinary waits release resources, intentional retained scopes keep their ownership. |
| [0016: Admission and shutdown](0016-bounded-async-admission-and-shutdown.md) | Bound suspended retries separately from the executor queue and drain the same admitted operation. |
| [0017: Cancellation and deadlines](0017-deadlines-and-physical-cancellation.md) | Extend the private state transitions without renewing budgets or freezing an intermediate failure as terminal. |
| [0022: DSN identifiers](0022-dsn-identifiers-belong-to-send-attempts.md) | Keep generated ENVID transaction-specific and fixed IDs unchanged; neither implies deduplication. |
| [0025: Configuration locks](0025-factory-scoped-locked-configuration.md) | Retain applicable requirements and integrate the policy's property counterparts without an escape around locks. |
| [0026: Actual-send diagnostics](0026-actual-send-diagnostics.md) | Explain bounded intermediate history, backoff and exhaustion honestly through the final outcome. |
| [0027: Sending limits](0027-factory-scoped-sending-limits.md) | Charge every provider invocation to the selected allowance; configured rates are not retry delays. |
| [0029: Connection failover](0029-smtp-submission-failover.md) | Honor initial routing, then keep submission retries on the selected Mailer; do not restart failover after provider invocation. |

## Research and source boundaries

- [RFC 5321](https://www.rfc-editor.org/rfc/rfc5321.html#section-4.2.5) and
  [RFC 3463](https://www.rfc-editor.org/rfc/rfc3463.html#section-3.3) establish the response and recipient distinctions, not this library's policy defaults.
- [RFC 5321's queue-retry guidance](https://www.rfc-editor.org/rfc/rfc5321.html#section-4.5.4.1) concerns persistent SMTP delivery work;
  this embedded feature does not take over those obligations.
- [Nodemailer pooled sending](https://nodemailer.com/smtp/pooled) documents bounded requeueing when a connection closes before a
  message is sent. The [v10.1.0 pool implementation](https://github.com/nodemailer/nodemailer/blob/v10.1.0/src/smtp-pool/index.ts)
  is a narrower recovery reference, not evidence that every SMTP refusal should be retried.
- [Symfony Messenger retry configuration](https://symfony.com/doc/current/messenger.html#retries-failures) illustrates configurable
  delay, capped backoff and jitter in an application job system. [Symfony Mailer's failover](https://symfony.com/doc/current/mailer.html#high-availability)
  and [its 8.0 transport implementation](https://github.com/symfony/mailer/blob/8.0/Transport/RoundRobinTransport.php) are comparison
  points, not definitions of Simple Java Mail's submission-safety boundary.
- The [cross-language comparison](../research/simple-java-mail-world-class-smtp-research.pdf) and
  [progress companion](../research/simple-java-mail-world-class-smtp-progress-report.md) supply the wider research context.
  This ADR records the architectural direction, not a competitive ranking or performance result.

## Implementation obligations

[ADR 0034](0034-optional-opentelemetry-integration.md) places optional telemetry around this existing lifecycle: one logical-send span
and metric count, with children for actual provider invocations. Captured context survives retry suspension; no thread-bound scope
stays open while waiting. A recovered refusal does not fail an ultimately successful logical span. This extension
cannot alter replay eligibility, receipt identity or the one-final-outcome contract.

The accepted implementation plan settles policy signatures, property names/defaults, pending capacity and replay-storage limits.
Follow the [API expansion workflow](../API_EXPANSION_WORKFLOW.md) and [coding guide](../CODING_STYLE_GUIDE.md). Verification requires tests for:

- **Eligibility and truth:** temporary refusal then acceptance; permanent, partial and uncertain results; missing evidence; acceptance
  followed by cleanup failure; unchanged final receipt/exception identity and no automatic recipient-subset replay.
- **Mailbox versus relay:** a deferred recipient alongside unrelated successful mail; service-wide refusals exhausting the bounded
  waiting collection; no automatic global cooldown or unbounded timer backlog.
- **Replayability:** unchanged content, envelope occurrences, attachment-read counts, signatures, exact bytes, provider options,
  Message-ID and transaction-specific versus fixed ENVID. Verify cleanup of snapshot resources on every terminal path.
- **State and lifecycle:** deterministic delay/resumption races, waiting-capacity saturation, cancellation, deadlines, stale
  continuations, shutdown/draining, primary versus cleanup failure and completed disposal before another invocation.
- **Destination and allowance:** pinned reacquisition, concurrent pooled reuse, no cross-attempt facts, selected locks and per-invocation
  rate charging, without restarting cluster/failover traversal or multiplying budgets.
- **Send scopes:** synchronous/asynchronous sends, direct/caller-runs executors, lazy batches, first-terminal-failure stopping,
  retained connections and application callbacks invoked once. Keep the disabled path's behavior and cost unchanged.
- **Observation and integrations:** one final callback/completion, bounded truthful history and logging, ordinary third-party providers,
  CustomMailer and logging-only boundaries, property/Spring/CLI parity, provenance, locks, classpath and JPMS consumers.

These obligations apply to changes in the retry engine as well as its initial implementation. Recorded verification belongs in the implementation record.

## Shared layered resolution (accepted 26 October 2026)

ADR 0039 shares declaration selection for supplied, configured and built-in retry settings. Schedule/resource completeness, clear/reset semantics, validation and execution/replay ownership remain with this record's feature; shared configuration does not generalize retry execution. See [ADR 0039](0039-explicit-configuration-resolution.md) and [#867](https://github.com/bbottema/simple-java-mail/issues/867).
