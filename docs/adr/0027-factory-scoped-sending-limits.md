# ADR 0027: Factory-scoped sending limits

- Status: Accepted
- Decision date: 2026-09-30
- Target: 10.0.0
- Implementation: Implemented for 10.0.0; not released
- Tracking: [#751](https://github.com/bbottema/simple-java-mail/issues/751)

## Context and decision drivers

An application can stay within its connection-pool size and still submit mail faster than its SMTP service allows. A bounded async queue
limits pending work, and a connection pool limits concurrent connections; neither sets a sending rate. Users should be able to configure
the service's message or recipient limit without writing sleeps around individual sends, concurrent workers and batches.

The useful abstraction is a count over a period, with an explicit way to share that allowance. It is not a bandwidth controller or a way
to make delivery faster. Nor can a local library reconstruct a provider's account-wide quota: other applications may send through that
account, the provider may count different events, and a process restart loses local history.

The decision needs to balance the following:

- Let users configure both the maximum count and period, using Java or their existing properties/Spring configuration.
- Keep message limits, recipient limits and connection concurrency distinct, without a counting-mode enum or provider-specific presets.
- Allow bursts where useful, while offering predictable spacing for deployments that need it.
- Share allowance across participating Mailers and connections without requiring another public Java object to construct or inject.
- Charge the selected SMTP configuration when clustering chooses a connection, rather than assuming the invoking Mailer's destination.
- Preserve cancellation, deadlines, lazy batches, observation and resource ownership on every supported send path.
- Describe local attempted submissions honestly, without claiming server-observed arrival rates, persistent quotas or final delivery.

This record supersedes the initial message-only, per-Mailer interval proposal in [#751](https://github.com/bbottema/simple-java-mail/issues/751).
The discussion and research in that issue remain useful background, but the accepted design is the one recorded here.

## Decision

Add optional message and recipient sending limits. Associate their live allowance with the registered SMTP configuration, and allow
explicitly named groups to share that allowance within one `SimpleJavaMail` factory. Use rolling windows, allow bursts by default, and
offer derived spacing when bursts are disabled. Count attempts at the provider-invocation boundary, not successful delivery.

### Configure counts and periods, not a hard-coded delay

The builder API is:

```java
.withMessageRateLimit(30, Duration.ofMinutes(1))
.withRecipientRateLimit(100, Duration.ofMinutes(1))
.withRateLimitGroup("company-account")
.withRateLimitBurstsAllowed(false)
```

The corresponding property shape is:

```properties
simplejavamail.smtp.ratelimit.group=company-account

simplejavamail.smtp.ratelimit.messages.limit=30
simplejavamail.smtp.ratelimit.messages.period=PT1M

simplejavamail.smtp.ratelimit.recipients.limit=100
simplejavamail.smtp.ratelimit.recipients.period=PT1M

simplejavamail.smtp.ratelimit.allowbursts=false
```

These APIs are part of the local 10.0.0 implementation, not a released version. Thirty messages per minute is an example,
never an implicit default. Rate limiting is disabled unless a limit is configured. Setting only a group name or burst preference does
not create a default rate.

Support one optional message rule and one optional recipient rule. Both the maximum count and period are configurable, positive and
representable. When both rules exist, a submission must satisfy both; it must not consume one allowance while waiting for the other.
Calling a rule's setter again replaces that rule rather than adding another window.

Provide paired `resetMessageRateLimit()` and `resetRecipientRateLimit()` operations that remove the corresponding rule, including a
property-loaded rule, rather than restoring an arbitrary rate. `resetRateLimitGroup()` restores a private allowance, and
`resetRateLimitBurstsAllowed()` restores the default of allowing bursts. Neither creates a sending limit.

Resolve defaults and overrides in the builders. Immutable configuration objects receive the resolved values; they do not resolve their
own defaults or hold counters. Expose the settings through `OperationalConfig`, never the mutable tracker. Reuse the existing property
loader, typed schema, safe provenance diagnostics, Spring metadata and generated CLI routes. Do not introduce a counting-mode enum,
public coordinator object or collection of arbitrary rules.

### Use rolling windows, with optional spreading

With bursts allowed, submissions may immediately use available allowance, subject to the configured rolling-window ceiling. The window
follows recent submissions rather than resetting at a wall-clock minute boundary. Keep elapsed-time accounting monotonic.

When bursts are disabled, derive the minimum spacing from the configured count and period. For example, 30 messages per minute produces
two-second spacing between local submission starts; 120 per minute produces half-second spacing. These are calculations, not defaults.

The first submission may start immediately. A slow submission does not require another full interval after it finishes, and sufficiently
long submissions may overlap when connections are available. Idle time does not earn catch-up credit. The rolling-window ceiling still
applies; spreading is an additional restriction, not a replacement for it.

Recipient-based spacing accounts for the whole email's cost. For example, submitting an email to ten recipients against a limit of
100 recipients per minute imposes six seconds before the following submission can start. If both message and recipient limits apply,
satisfy both spacing restrictions as well as both rolling-window ceilings.

An email remains one submission. If its recipient count exceeds the entire configured recipient allowance, reject it clearly before
waiting or acquiring transport resources. Explain the email's recipient count and the configured limit so the application can use fewer
recipients or correct its configuration. Do not wait forever, silently split the email, change its contents or borrow future allowance.

### Share allowance within one factory

Mutable trackers belong to the factory's runtime, separate from its immutable configuration snapshot. Sharing counters does not make
the factory own another executor or the Mailers' connections.

- All connections using one registered SMTP configuration share its tracker.
- Without a group name, that registered configuration has a private allowance.
- An explicit group name combines participating SMTP configurations within one `SimpleJavaMail` factory.
- Separate factories remain independent, including factories built from identical configuration snapshots.
- A group identifies shared allowance, not a connection-pool cluster, hostname or automatically discovered provider account.
- Participating configurations supply their resolved rules and must agree on limits and burst behavior. Reject contradictory declarations
  instead of silently using the first configuration. The name is a sharing key, not a replacement for specifying the rules.
- Closing one Mailer must not reset another participant's allowance. Reusing a named group within the factory's lifetime retains its
  relevant recent history; closing the last participant must not create fresh allowance for an immediate replacement.
- A failed Mailer build does not establish a new group's rules. Concurrent builders may share provisional registration; rollback must not
  discard another successful participant or established history. Pool initialization stays outside the registry lock.
- Bind allowance to the Mailer and the selected pool registration, not a mutable Session property. Reusing a caller-owned Session does not
  replace another participant's rules. Within one pool cluster, the same Session identifies one registration; conflicting allowance owners
  must be rejected rather than silently choosing the latest Mailer.

For example, two Mailers built from the same factory and configured with `company-account` and 30 messages per minute share one allowance
of 30, even if they use separate connection pools. The same name in another factory has independent history. Equal hostnames, credentials
or pool cluster keys do not implicitly combine allowances.

For clustered sends, charge the allowance associated with the selected SMTP configuration, not necessarily the Mailer receiving the send
call. A cluster may contain independent accounts, and one account may deliberately share allowance across multiple registered configurations.

### Count attempted submissions

Reserve allowance before transport work and charge it when invoking the provider. A reservation is not a completed charge: cancellation,
connection failure or MIME-preparation failure before invocation releases the unused reservation. Stop and invocation races must resolve
once, so abandoned work cannot submit later or leave allowance permanently reserved.

Once the provider is invoked, keep the charge whether it accepts, rejects, loses the connection with acceptance uncertain, or returns an
acceptance receipt followed by a cleanup failure. Do not retrospectively refund rejected recipients. Provider-internal preflight failures
may conservatively consume allowance even when no SMTP command was sent, because invocation is the portable boundary. These counters
describe local attempts; they do not claim to reproduce the provider's quota accounting.

Recipient cost comes from the resolved intended submission envelope, including BCC and envelope overrides, not merely the original
Email builder's recipient list. Preserve recipient occurrences instead of silently deduplicating them. Validation, rehearsal, probing
and logging-only operations consume no allowance.

Support `CustomMailer` by limiting its callback invocations using the invoking Mailer's configured group and intended envelope. One
invocation costs one message and its intended recipient count. The callback's hidden retries, recipient changes and additional sends
remain outside the guarantee; do not pretend to discover its remote account or transport behavior.

### Preserve execution and resource ownership

Ordinary allowance waits must hold no borrowed connection or started proxy resource. Destination selection and authoritative recipient
costing therefore need to be available before those waits. Separate selection from acquisition where necessary, without duplicating
upstream load-balancing logic or adding message serialization and attachment reads just to count recipients.

Delayed reservations must not become a bank of unrestricted later submissions when connection setup or MIME preparation catches up.
Respect the selected burst policy at the invocation boundary, not merely at an earlier acquisition-admission boundary.

Use existing caller/worker threads and cancellation/deadline controls, not another executor or an unbounded scheduling queue. Waiting
counts against the existing send deadline and must wake promptly for cancellation or timeout. Keep synchronization limited to tracker
bookkeeping; never hold its monitor during waiting, conversion, network work or application callbacks.

Prefer FIFO among live waiters that have reached the gate. This is not a promise of global send order across executors and producers.
Document head-of-line blocking rather than adding priority scheduling. Preserve lazy batches, first-failure stopping, observer ordering
and graceful draining. A batch obtains allowance per reached email, not for the entire iterable in advance.

Dedicated simple-batch and `withOpenConnection` scopes intentionally retain their connection between emails. Keep that contract during
per-email allowance waits. Long waits can encounter the server's idle timeout; document this exception to ordinary pre-acquisition waiting.
Do not silently reconnect or bypass the limit to keep a scope alive.

Explain deliberate rate-limit waiting separately in the existing send diagnostics rather than labeling it queue residence or connection
acquisition. Preserve the established outcome and completion contracts, including the distinction between acceptance and later failure.

## Alternatives considered

| Alternative | Benefit | Reason not selected |
| --- | --- | --- |
| Sleep after every completed send | Small implementation and an easy single-threaded model. | Makes spacing depend on send duration and poorly represents concurrent sending limits. |
| Count only accepted mail | Can resemble some providers' quota accounting. | Needs pending reservations and refund rules, and cannot universally resolve rejection or uncertain acceptance. |
| Always spread submissions | Smooths traffic without a burst setting. | Prevents useful bursts even when the deployment permits them. |
| Per-Mailer limits only | Straightforward ownership and isolation. | Multiple Mailers multiply allowance, and clustering may select another SMTP configuration. |
| Public shared tracker object | Makes sharing explicit through object identity. | Adds Java wiring and a public abstraction that is inconvenient for property-configured applications. |
| Process-global named registry | Makes sharing across independently created factories easy. | Introduces ambient state, name collisions and lifecycle/reconfiguration coupling. |
| Multiple windows per counting unit | Represents more combinations of published restrictions. | Expands configuration without solving persistence or account-wide visibility. |
| Distributed limiter or SMTP broker | Can coordinate separate application processes. | Introduces infrastructure and failure contracts outside this embedded-library feature. |

The selected combination keeps the ordinary case small while allowing explicit sharing and optional smoothing. Its count/period settings
are useful on their own; users do not need a new service or a public scheduling object just to configure a sending rate.

## Consequences

### Benefits

Users can configure message and recipient rates in familiar units, through Java or existing configuration sources. Named groups let
multiple components share allowance without confusing that relationship with connection pooling. All participating send paths use the
same accounting boundary, including custom callbacks, and applications do not need to maintain their own sleeps around each send.

Rolling windows prevent fixed-window reset bursts, while the optional spacing setting allows users to spread traffic when appropriate.
Neither choice assumes that every SMTP service wants the same rate or that a provider's published maximum guarantees acceptance.

### Costs and limitations

Coordination and pre-acquisition route/envelope handling add implementation complexity. The existing pooling and preparation paths must
be integrated deliberately; a counter placed around the outer `sendMail` call is not sufficient.

Waiting occupies an existing execution thread and can lengthen graceful shutdown. FIFO can delay smaller sends behind larger ones.
Conservative attempt accounting may underuse a provider's actual allowance, and retained connections may time out while waiting.

Local history disappears when its owning runtime is discarded. Separate factories and processes do not see one another's submissions.
Provider preparation, thread scheduling, network timing and remote behavior can change when work reaches the server. Local invocation
limits therefore cannot guarantee server-observed arrival timing, acceptance, account-wide quota compliance or final delivery.

## Boundaries

This feature is opt-in local control, not provider quota discovery, automatic backoff, retry or failover. It does not change message bytes,
split recipients, manage bandwidth, retire connections after a message count, or add durable quota history. A long configured window is
still local in-memory accounting, not a persistent daily quota shared with every sender using that provider account.

Cross-application SMTP pooling/brokering is research tracked in [#753](https://github.com/bbottema/simple-java-mail/issues/753), not an
architecture selected by this ADR or a dependency of #751. Add no Redis dependency, durable spool, remote pool or speculative extension
interface for this feature.

Factory-scoped configuration locks and allowance sharing are distinct mechanisms. Locks restrict later customization of configured
values; rate trackers coordinate live attempts. Both can use the same factory boundary without sharing mutable configuration state.
Implementing [#751](https://github.com/bbottema/simple-java-mail/issues/751) does not require implementing
[#740](https://github.com/bbottema/simple-java-mail/issues/740) first.

## Related decisions

| Record | Relationship |
| --- | --- |
| [0003: Immutable configuration snapshots](0003-immutable-configuration-snapshots.md) | Keep resolved settings immutable; place live allowance in factory runtime state rather than the snapshot. |
| [0004: Configuration provenance diagnostics](0004-configuration-provenance-diagnostics.md) | Explain where configured limits came from through the existing safe report, not mutable counters. |
| [0005: Spring integration](0005-spring-integration-and-boot-compatibility.md) | Reuse Environment-based loading and IDE metadata rather than adding a separate runtime binder. |
| [0007: Provider-neutral MIME boundary](0007-provider-neutral-mime-boundary.md) | Count the resolved intended envelope and preserve prepared content without extra serialization. |
| [0011: Submission outcomes](0011-transport-neutral-submission-outcomes.md) | Attempt charges do not redefine accepted, rejected or unknown submission facts. |
| [0012: Terminal observation](0012-terminal-send-observation.md) | Preserve per-email scope, observer ordering and the first-failure boundary. |
| [0015: Execution and transport pooling](0015-execution-views-and-transport-pooling.md) | Keep execution mode, connection ownership and allowance sharing separate; use the selected SMTP configuration. |
| [0016: Admission and shutdown](0016-bounded-async-admission-and-shutdown.md) | Retain bounded admission and graceful draining without an additional unbounded waiting queue. |
| [0017: Deadlines and cancellation](0017-deadlines-and-physical-cancellation.md) | Include allowance waits in the existing send budget and preserve stop/acceptance arbitration. |
| [0018: Generated CLI](0018-cli-from-builder-contracts.md) | Expose the planned builder settings through the normal conversion and metadata routes. |
| [0025: Factory-scoped locks](0025-factory-scoped-locked-configuration.md) | Configuration restrictions are independent of mutable allowance and are not an implementation prerequisite. |
| [0026: Actual-send diagnostics](0026-actual-send-diagnostics.md) | Explain deliberate allowance waits separately from scheduling and acquisition without changing completion semantics. |

## Implementation obligations

Implementation is tracked under [#751](https://github.com/bbottema/simple-java-mail/issues/751); this record alone is not proof of completion. Follow the
[API expansion workflow](../API_EXPANSION_WORKFLOW.md) and [coding guide](../CODING_STYLE_GUIDE.md). Before claiming support, cover:

- **Rate arithmetic:** deterministic rolling-window, recipient-weighting and burst-spreading tests, including measured boundaries,
  first admission, idle time, slow submissions, representable limits and satisfying both rules together.
- **Reservation correctness:** concurrent grants, cancellation, deadlines and failure races without double charging, double release,
  stale reservations, stranded waiters or submission after cancellation. Verify failures on both sides of provider invocation.
- **Ownership:** group agreement, private allowances, factory isolation, participant closure and relevant history retention when a
  named group is reused. Use the actual selected clustered configuration; do not infer account identity from connection settings.
- **Resource boundaries:** no ordinary leased connection or started proxy resource while waiting, no bank of delayed reservations,
  no network work under the tracker monitor, and no duplicate load-balancing implementation.
- **Envelope and content:** authoritative recipient costing, BCC and overrides, preserved duplicate occurrences, oversized-email
  rejection and unchanged serialization/attachment-read counts. Keep exact and protected content intact.
- **Send paths:** sync/async, lazy batches, first-failure stopping, retained scopes, caller-owned Sessions, third-party providers,
  CustomMailer callbacks and no-send modes. Verify cancellation, observation and graceful shutdown remain consistent.
- **Configuration and diagnostics:** builder replacement/reset behavior, property precedence and isolation, Spring metadata, CLI
  options, safe provenance and honest wait reporting. Keep the disabled path's behavior unchanged.

The [verification report](../research/sending-limits-verification.md) records the integration runs and holistic-review corrections.
Documentation checks do not replace runtime tests or establish release readiness.
