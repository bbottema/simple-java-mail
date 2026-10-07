# ADR 0029: Coordinated SMTP submission failover

- Status: **Accepted**.
- Decision date: 2026-10-07.
- Target: 10.0.0; connection-only initial scope accepted.
- Implementation: **Planned, not implemented**; acceptance of this ADR does not authorize implementation.
- Tracking: [#755](https://github.com/bbottema/simple-java-mail/issues/755).
- Evidence: [SMTP failover research](../research/755-smtp-submission-failover.md).

## Context and decision drivers

Applications with a primary SMTP relay and an authorized failover should not have to write exception-driven recovery around every send.
Existing cluster selection and connection pooling do not provide that feature: acquisition stays with the selected registration.
The historical [#382](https://github.com/bbottema/simple-java-mail/issues/382) request was closed because automatic replay could duplicate
messages and there was insufficient failure evidence to decide safely.

The unreleased 10.0.0 branch now has structured submission receipts, recipient facts, cancellation, locks, sending limits and actual-send
diagnostics. Those foundations justify revisiting the problem, but a failed operation still does not prove a message was unsent.

The desired experience is ordinary synchronous/asynchronous Mailer sending with explicit eligible failover Mailers, independent of whether
pooling is enabled. Keep message construction, execution, transport ownership and server acceptance as separate concerns.

The maintainer's earlier server-specific-header use case is important to the failover design: a relay may need a particular header,
sender or signing configuration on every message. Its Mailer can also have application policy unrelated to an SMTP requirement,
such as copying maintainers whenever the failover Mailer is used. Treating components as transport configuration alone would discard those uses.

## Decision summary

On 2026-10-07 the maintainer selected **connection failover first**: try an explicitly configured failover destination when the primary cannot provide
a usable connection. Once provider submission invocation begins, retain the existing failure and receipt behavior without automatic replay.
This applies to ordinary synchronous/asynchronous sends, with or without pooling. The architectural decisions below are accepted;
concrete implementation signatures and mechanics follow in the implementation plan.

Failover handles failure modes, not capacity expansion. Preserve normal pool waiting and existing claim timeouts; a busy pool, executor
or sending-rate limiter does not trigger another destination. Pool sizing, clustering and load balancing own capacity management.
The immutable `FailoverPolicy` configures eligible failures and connection retry/reconsideration, not a capacity-spillover option.

The policy also owns any primary-connection retry, backoff and reconsideration rules. The maintainer prefers behavior emerging from
policy evaluation and minimal private attempt history, not a separate server-health/state-orchestration subsystem. This direction does
not accept concrete retry intervals or additional retry limits.

The default tries the primary connection once before considering an eligible failover Mailer; additional primary setup retries/backoff can be
explicitly configured. Backoff is per destination, not a delay imposed on the entire fallback chain. A working failover's sending flow is
unchanged by the primary's retry timing. Eligible later sends return to the primary after a usable connection is obtained there.

Primary reconsideration uses a configurable fixed interval by default, with optional increasing backoff capped at a configured maximum.
The interval, increasing-backoff parameters and exact signatures remain to be settled; no example duration is an accepted built-in default.

Mailers created by one factory share connection-failure history and coordinate primary rechecks for the same configured destination.
Separate factories remain independent, even when created from identical configuration snapshots. Sharing observations does not override
an invoking Mailer's failover permissions or give one Mailer ownership of another's operation, resources, cancellation or deadline.

With failover enabled, a simple batch can return to the primary between emails instead of finishing its entire remainder on the failover Mailer.
Finish the current email on B; after policy-permitted recovery establishes a usable A connection, move only the untouched remainder to A.
Keep the same iterator, operation, completion handle, cancellation control and overall deadline. This is a route change, not cancellation
or replay. An explicitly retained `withOpenConnection` scope remains pinned, and actual cancellation still stops the whole operation.

Automatic operational logging is the baseline notification mechanism, including when no observer is configured. A successful failover
submission works around an unresolved relay problem; it does not erase that problem or repair the primary. Log genuine setup failures
at ERROR, meaningful failover/recovery transitions at INFO and repeated unsuccessful rechecks at DEBUG.
Suppress duplicate incident messages across participating Mailers. Optional ordered connection-attempt history belongs in the existing
observer diagnostics, not a new failure handler or send-result wrapper. Concrete history signatures remain pending.

The website must explain how applications can handle later retry decisions through the existing observer and receipt APIs.
General mail-send retry policies, including possible backoff, are separate research under [#756](https://github.com/bbottema/simple-java-mail/issues/756);
they do not expand the agreed initial scope or acquire a release target through this ADR.

The maintainer selected a failover Mailer on the primary Mailer's builder, not a third resilient Mailer. The accepted planned shape is
`withFailoverMailer(Mailer failoverMailer, FailoverPolicy policy)`, returning the normal fluent builder. The failover Mailer and policy are configured
together; replacing or clearing that relationship replaces or clears both. Do not add a standalone `withFailoverPolicy(...)` setter
or a staged intermediate builder interface. Existing sync/async send entry points remain unchanged.

The Mailers are alternative destinations, not a template hierarchy. Resolve the original Email through the selected Mailer's own
ordinary defaults/overrides only. A's ordinary headers, BCC, signing settings and other template values do not carry into B. Configure shared behavior
on both Mailers, optionally reusing the same Email templates, or supply it on the Email itself. The selected Mailer's complete templates
participate; do not whitelist only relay-related fields. A failover-only maintainers BCC is a valid ordinary default.

This refinement on 2026-10-07 supersedes the earlier proposed third-Mailer composition and merged-template precedence. It adds no
defaults/overrides layer and preserves their established standalone replacement, additive, suppression and exact-message semantics.
The maintainer also accepted nested failover behavior and the shared-operation ownership boundary. If B has C configured as its failover Mailer,
delegating from A to B honors B's fallback to C under B's policy. Do not flatten B into an endpoint or ignore its own failover configuration.
The paired single-failover setter is retained; an array is not required to express this chain. A owns the logical operation, while each
Mailer's policy governs its own alternatives and the actual selected Mailer supplies its destination configuration.

The precedent is scoped ownership in existing clusters: the first registration establishes shared cluster pool settings, not every
participating Mailer's complete configuration. The analogous failover boundary is one owner for shared execution, not another public
send or executor admission at every nested Mailer. Failover Mailers from different factories, scoped lock ownership and named configuration are
accepted. Inspection remains specific to the Mailer called; concrete policy fields and final property spelling follow in implementation planning.

The primary owns its normal execution, coordination and connection resources. An application-supplied failover Mailer remains borrowed: closing
the primary does not close that failover Mailer, its executor or pool registration. Applications close supplied failover Mailers separately after their
users have drained. In the accepted refinement for property configuration, SJM-created private failover Mailers are owned by the primary and
closed after its admitted work and observer handoffs drain. Both forms participate in lifecycle accounting; reject new use during shutdown
and drain admitted use before disposal.

The maintainer accepted failover Mailers from independently configured factories on 2026-10-07. Reuse the actual Mailer and its captured snapshot,
resources and runtime ownership; do not reload or merge factories merely because one Mailer refers to another. Equal rate-group names
or configuration values in separate factories do not combine their histories. The maintainer subsequently accepted scoped locks with
centrally configurable, lockable failover relationships: mandatory message requirements survive fallback, while connection settings
remain with each approved destination. This restriction does not reinstate inheritance of ordinary Email templates.

The maintainer also accepted named Mailer configuration blocks resolved by the existing loader on 2026-10-07. Java, Spring and the CLI
can construct private failover Mailers from captured definitions without application-specific object registration. Names identify independent
configuration definitions, not Spring beans or process-global live Mailers. Supplied instances still use the paired Java setter and
retain borrowed ownership. Final new property names remain illustrative until the implementation plan settles their spelling.

## Accepted locked configuration and approved failover Mailers

The maintainer accepted this decision on 2026-10-07 after comparing the alternatives below. It settles lock ownership, not the remaining
route-reference syntax or the complete ADR. The feature remains unimplemented.

The lock contract is recorded in [ADR 0025's accepted addendum](0025-factory-scoped-locked-configuration.md#accepted-addendum-delegation-to-approved-failover-mailers-2026-10-07).
This ADR owns the failover-specific alternatives, ownership matrix, route representation and execution consequences.

Application developers may receive centrally supplied properties that they cannot reasonably change. They should be able to use an
approved failover without writing their own recovery orchestration or discovering during an outage that a mandatory archive copy vanished.
Put the additional validation in SJM and the route configuration with its owner, rather than requiring every application to reconstruct it.

### Decision matrix

| Alternative | Behavior during A-to-B fallback | Benefit | Decision and cost |
| --- | --- | --- | --- |
| Carry every A lock unchanged | B must satisfy A's locked host, credentials and message settings. | One strict rule. | Rejected: a locked hostname effectively prevents a different relay, leaving developers unable to use failover without changing centrally supplied configuration. |
| Apply only B's locks | A's restrictions disappear when B is selected. | Independent Mailers and simpler implementation. | Rejected: an outage could silently remove an archive BCC or onward-TLS requirement; every shared restriction must be duplicated and kept aligned. |
| Preserve message locks and keep connection locks with their destination | Message requirements survive while B supplies its own connection configuration. | Useful failover and meaningful ownership. | Insufficient alone: attaching an arbitrary failover could bypass a centrally selected relay. |
| The same scoped ownership with centrally configurable, lockable failover relationships | An explicitly approved alternative can submit while applicable mandatory message requirements remain binding. | Convenient sending with predictable restrictions. | Accepted: SJM performs more validation, and the configuration owner supplies compatible destinations and permitted relationships. |

These alternatives do not become public modes. Use one ownership rule, without a lock-propagation enum, an ignore-locks switch,
a third Email template or a general-purpose compliance evaluator.

### Ownership and conflict handling

| Setting | Accepted ownership |
| --- | --- |
| Host, port, credentials and proxy | The actual selected destination retains its own values and locks. Selecting B does not mutate A or require B to have A's hostname. |
| Connection TLS and certificate verification | Each approved destination retains its own configuration and locks. A company requiring TLS on every route must configure and lock it on every route. |
| Mandatory message requirements, including archive BCC and onward REQUIRETLS | The initiating Mailer's applicable message locks remain binding through fallback, together with the locks of participating failover Mailers. |
| Ordinary Email defaults and overrides | Only the selected Mailer's templates apply to the original Email, followed by the applicable lock checks. |
| Execution, cancellation and overall deadline | The initiating Mailer remains the owner, including its applicable operational locks. |
| Permitted failover relationship | A captured named configuration reference can be supplied centrally and locked against conflicting changes, including unapproved nested alternatives. Final property spelling and converter details belong in the implementation plan. |

For A-to-B-to-C, preserve the mandatory message requirements of A and B when C submits, as well as C's own locks. Direct B-to-C calls
do not acquire A's locks. These are restrictions within the existing governance mechanism, not inherited ordinary templates or a merged
factory snapshot. Resolve each selected path from the original Email; do not carry a previous email's route-specific requirements into
later batch emails after returning to A.

Keep [ADR 0025](0025-factory-scoped-locked-configuration.md)'s value semantics: equal parsed values are compatible, conflicting fixed
values are rejected, and required recipients remain in the actual envelope using the existing additive rules. Do not silently prefer A's
lock over B's, suppress a restriction, or use a configuration conflict as a reason to try another relay. Clearing or suppressing ordinary
Email templates cannot remove these requirements. Exact/protected bytes remain authoritative; reject incompatible combinations rather
than rewrite content. Ordinary, unlocked failover configuration remains available without a separate approval service or workflow.

Keep the failover relationship configurable and lockable through the existing configuration mechanisms. Resolve named references from
the immutable captured configuration; a locked relationship must protect the resolved permitted alternative and its nested relationships,
not just compare its display name. Reject conflicting replacement or clearing through Java customization and unapproved nested alternatives.
Do not invent a hostname-only trust check, implicit Spring bean lookup or merged factory identity. Centrally supplied locks are still local
configuration restrictions, not a security sandbox; source precedence remains unchanged and application code can deliberately construct
an independently configured factory.

Connection security is explicitly configured per approved destination. Do not reinterpret a locked transport strategy as a minimum
security level, assume another strategy is equivalent, or disable verification when moving to a failover. The independent authentication
and certificate-failure opt-ins authorize trying that failover Mailer; they do not weaken its checks. Central configuration that requires TLS
everywhere must provide it on every eligible destination, including nested failover Mailers.

Detect known incompatibilities when composing/building the configured Mailers; retain send-time checks for facts available only then.
Errors must identify the conflicting properties, explain that they are managed through `simplejavamail.locked.`, and tell the application
developer when the configuration owner must supply a compatible failover. Do not present removing company locks as the normal remedy.
SJM cannot guarantee usable failover when the supplied configuration forbids every available alternative.

### Use case: centrally supplied primary, failover and mandatory archive copy

The platform team supplies A and B with different fixed hosts and credentials, their required TLS settings, and a locked relationship
permitting A to use B. A also requires an archive BCC through its locked message configuration. B may have its own ordinary maintainer BCC.
When A is unavailable, B can submit with the mandatory archive recipient still present and its own ordinary maintainer copy included.
Neither selecting B nor suppressing ordinary templates removes the archive requirement. The maintainer copy retains its normal
suppression and exact-Email boundaries, and neither copy is a guarantee of delivery.

If B has a conflicting mandatory message value, report the incompatibility before submission instead of quietly choosing one lock.
The configuration owner must supply a compatible route; application developers keep using the ordinary send API. Include this scenario,
the ownership matrix and the distinction between ordinary templates and mandatory message requirements in the implementation's public
documentation. No new property syntax is established by this example.

## Accepted credentials and certificate-verification choices

The maintainer selected two independent policy opt-ins on 2026-10-07: permit failover use after an authentication failure, and permit failover
use after a certificate-verification failure. Both are disabled by default. Enabling one does not enable the other. Final method names
and property keys remain pending; this decision does not implement the options.

No permission means no fallback for that failure category, and permission to use a failover must never mean disabling its checks.

The reason is operational predictability, not a claim that every authorized failover is insecure: switching relays can change latency,
throughput and downstream behavior while hiding a configuration problem. Report the primary failure so the application can fix it.
Explicit failure-driven failover can still change performance; the contract makes those route changes intentional and diagnosable.
An opt-in similarly authorizes different routing without guaranteeing identical performance. Retain the primary failure and selected
failover in bounded attempt history even if sending succeeds. A catch-all configuration-failure switch must not also permit bypassing
locks, malformed-message checks or incompatible local configuration.

Distinguish a known credential or verification rejection from an actual network outage during setup. Do not infer availability merely
from an authentication/TLS exception category or its message; missing classification is not permission to continue to another relay.
Any future cached-failure or cooldown handling must retain the failure category and honor the invoking policy's matching permission.
Shared failure history must not make a strict policy silently skip authentication/certificate failures as though they were ordinary outages.

### Use case: the primary relay's certificate expires

An application normally sends through its company's primary relay. Operations has also configured an authorized failover relay with a
valid certificate and its own credentials. The primary's certificate expires during a renewal incident.

The application has opted into failover use for certificate-verification failures, but has left authentication-failure fallback disabled.
The primary fails verification before submission. The send can then use the failover Mailer only after it independently passes verification and
all applicable security, routing and locked-setting checks. The email is submitted once through that usable connection.

The application can keep sending during the incident without accepting the expired certificate, disabling hostname checks or weakening
TLS. Authentication rejection still stops the operation because that separate option was not enabled. If no eligible failover Mailer can pass
the required checks, the operation fails normally; this is not a promise that the application cannot fail or that mail will be delivered.

Retain the primary certificate failure and actual selected failover destination in bounded attempt history even on success. Automatic operational
logging makes the incident visible without requiring an observer; the optional observer/audit path provides programmatic detail.
A successful failover submission does not repair the certificate or establish final mailbox delivery.

Include this scenario in the website failover guide, with both opt-ins explained independently, illustrative output and the default-stop
behavior. Final API examples must use the agreed policy methods once those signatures have been settled.

## Final boundary decisions (2026-10-07)

These decisions complete the architecture. Final policy method/property names, attempt-history signatures, retry intervals/counts,
registration identity, traversal and lifecycle mechanics belong in the implementation plan. They must implement the agreed ownership
and safety boundaries rather than introduce new routing behavior.

### Failure modes, not capacity expansion

Preserve ordinary pool waiting, claim timeouts, executor admission and sending-rate behavior. An occupied pool or an expired wait for
its capacity is not evidence that its SMTP server failed. Do not switch destinations merely to avoid that wait, even as an opt-in
within this feature. Pooling, cluster management and load balancing own capacity expansion.

A real connection-creation or health failure encountered through a pool can still qualify for failover. Distinguish that evidence
from all connections simply being in use; a generic claim-timeout wrapper cannot make the distinction for us. Existing cancellation
and total-deadline expiry stop the logical operation. Do not start another route to evade them.

Capacity pressure does not create an outage observation, extend failure backoff or prolong failover eligibility after recovery.
Previously recorded genuine failure can still explain an eligible alternative under the agreed recovery policy; a busy pool alone
cannot establish that condition. A usable primary is preferred again under its normal pool and rate behavior.

### Practical failure classification

The accepted conservative fallback is for genuinely opaque causes, not permission to leave ordinary SMTP outages unsupported.
Reliable automatic failover for common failures is a release acceptance criterion. The managed Angus integration and supported pool
paths must preserve enough connection-stage and cause information to satisfy it, adding narrow internal/upstream evidence where
existing wrappers lose the distinction. Do not copy SMTP sequencing or infer the cause by matching exception messages.

| Failure observed before provider submission invocation | Required handling |
| --- | --- |
| DNS resolution failure, refused/unreachable connection or connection-establishment timeout | Permit the next eligible failover destination under the configured policy. |
| Timeout, EOF/reset or connection loss while waiting for the greeting or completing connection setup | Classify the actual setup failure and permit failover when it is an availability problem, without overriding known authentication/security facts. |
| A reliably identified temporary service-unavailable response during connection setup | Permit failover; preserve the response stage rather than treating every 4xx as equivalent. |
| Failed pooled-connection health/replacement work caused by those outages | Preserve the underlying connection evidence and apply the same policy as direct acquisition. This is not ordinary pool saturation. |
| Authentication rejection or certificate/identity-verification failure | Keep the two independently configured permissions, both disabled by default. A generic TLS handshake failure is not automatically a certificate failure. |
| Invalid local configuration, lock conflict, message-preparation failure or an unmet required capability | Stop; do not weaken the requirement or disguise the defect as an outage. |
| Genuinely unclassifiable provider/custom-hook setup failure | Stop with a useful explanation and retain the underlying failure. Do not make this the routine result for the supported stack's ordinary outages. |
| Cancellation, expired total deadline, shutdown or interruption | Stop the operation; do not move to another destination. |

Classification remains an implementation responsibility, not another public enum, exception predicate or setting users must configure.
Preserve the existing independent authentication/certificate permissions. There is no catch-all opt-in for unknown failures.

The implementation plan must include a source/evidence map and deterministic direct, pooled and clustered tests for the common failure
rows, including failed replacement of an established connection. A broken classifier that turns these into unknown failures blocks
completion of the feature. Show authentication/certificate opt-ins working and remaining independent, as well as honest stopping for
opaque third-party failures. Do not claim that arbitrary provider implementations expose facts they do not provide.

This is an accepted coverage obligation, not a claim that those tests or connection classifications already exist.

### Inspection and offline preparation stay specific to the called Mailer

| Call on Mailer A | Meaning with failover configured |
| --- | --- |
| Configuration accessors, including Session/server/operational/governance getters | A's configured values and resources; never whichever destination happened to be used most recently. |
| `validate(email)` or `rehearse(email)` | Prepare using A's rules and Session without selecting an online route or preparing every alternative. |
| `testConnection()` or `probeConnection(...)` | Inspect A's configured endpoint. A successful connection elsewhere cannot turn A's diagnostic into a success. |
| Actual-send diagnostics | Report the selected submission destination and the agreed bounded attempt history for that logical send. |

Do not repeatedly read attachments or render/sign an email for every destination merely because failover is configured. An unavailable
or incompatible unused alternative must not make a primary-only rehearsal fail. Existing no-SMTP rehearsal and targeted-probe contracts
remain intact; inspection does not predict which destination a future send will select.

Calling B's rehearsal directly describes B as an independent Mailer. It is not an A-to-B route rehearsal: A's mandatory message locks
additionally apply when A actually delegates to B. Reject known route conflicts during construction and message-dependent conflicts
before submission. Document that distinction without adding an implicit all-routes rehearsal or route-inspection API in this feature.

## Decision

### Coordinate one logical send

Opt in by attaching an explicitly configured failover and its policy to the primary's builder. The built primary is the resilient Mailer;
do not require a third wrapper, a `withPrimaryMailer(...)` setter or post-build mutation. Ordinary sync and async sends remain unchanged.
Keep primary/failover destination selection separate from the policy's failure-handling rules. A standalone relay is a one-candidate group.
Preserve configured load balancing within a group. Each Mailer can have its own failover/policy pair, including B's configured fallback to C.
Do not require a parallel public SMTP-profile builder merely to repeat configuration already expressible on Mailer.

Keep one Mailer-owned routing coordinator, one operation, one cancellation/deadline budget and one finite traversal budget. Supporting
pool libraries supply candidate selection and exclusive leases, not independent SMTP retry policies. Failover use is not implemented by
catching A's public send failure and starting B's public send. Reuse the controlled pre-submission path and do not create another physical
pool around existing pools. The initiating primary owns the operation; the selected Mailer supplies its configuration and resources.
Using B's configuration and failover policy does not mean repeating its complete public send pipeline or resetting the overall budget.

Candidate traversal must preserve registration identity and avoid accidentally revisiting a failed candidate within the same selection
pass. Any repeated connection attempt needs explicit, bounded policy permission; its count and delay remain to be settled. Retirement
does not grant permission to redirect an old selection to a new registration using the same key. Upstream exclusion/traversal support
must preserve existing load-balancer semantics rather than reproduce them in SJM.

### Accepted nested failover behavior and shared-operation ownership

With A configured to use B, and B configured to use C, A's policy governs fallback from A to B; B's policy governs fallback from B to C.
Do not overwrite B's policy with A's, skip B's configured failover Mailer, or treat B as a transport-only profile. Reevaluate the relevant policy
against classified connection facts at each boundary. Direct calls on B retain B as their initiating Mailer.

All stages still belong to one logical send. The initiating Mailer owns execution, admission, terminal observation, cancellation,
completion and the remaining overall deadline. Nested setup attempts share the operation's finite traversal budget. They cannot reset
the deadline, revive stopped work, multiply independent retry loops or continue after any provider submission invocation has begun.
Reject cyclic failover configurations clearly; explicit bounded connection retries are distinct from following a cycle.

#### Planned ownership matrix

In the first two columns the application sends on A; in the last it sends directly on B. The named destination actually submits the email.
These are accepted design semantics, not implemented behavior. Synchronous calls use their caller's thread rather than an async executor.

| Concern | A sends through B | A sends through C via B | B sends directly through C |
| --- | --- | --- | --- |
| Async executor and admission queue | A | A | B |
| Terminal observer and completion | A | A | B |
| Cancellation and overall deadline | A | A | B |
| Fallback permission and timing | A's policy for A-to-B | A's policy for A-to-B; B's for B-to-C | B's policy for B-to-C |
| Email defaults/overrides and envelope preparation | B | C | C |
| Credentials, TLS, proxy and provider settings | B | C | C |
| Message/recipient sending rules and destination resources | B | C | C |
| Shared pool settings, when clustered | First registration in B's selected cluster | First registration in C's selected cluster | First registration in C's selected cluster |
| Execution/deadline locks | A | A | B |
| Connection-setting locks, including TLS | B | C | C |
| Mandatory message locks | A and B | A, B and C | B and C |
| Locked failover relationships | A-to-B restriction | A-to-B and B-to-C restrictions | B-to-C restriction |

Exact Email retains its ordinary-template bypass. All nested routing uses the initiating operation's remaining budget; neither a
child policy nor a route change resets it. Applicable message locks are cumulative restrictions, not ordinary template inheritance;
conflicting fixed values reject the configuration. Named references protect the captured permitted relationships; final property spelling
remains an implementation-plan detail. Failover lifetime follows the supplied/private distinction below, without changing operation ownership.

The public failover walkthrough must contain one canonical ownership matrix of this form. Sending/configuration guidance, the field
guide and Javadocs should link to it or explain their relevant rows rather than introduce conflicting versions of the ownership rule.

This follows the ownership principle recorded in [ADR 0015](0015-execution-views-and-transport-pooling.md). Current
[cluster registration](../../modules/batch-module/src/main/java/org/simplejavamail/internal/batchsupport/BatchTransportEngine.java)
retains the first settings, and [BatchSupport](../../modules/batch-module/src/main/java/org/simplejavamail/internal/batchsupport/BatchSupport.java)
warns when later settings differ. That is evidence for scoped shared-resource ownership, not evidence that nested SMTP failover exists today.
The rule must be explicit in public Javadocs, examples and concurrency documentation, rather than described as selectively ignoring a Mailer.

### Independent selected-Mailer templates

Resolve each candidate against the original supplied Email using only that candidate's own governance. If B is selected, do not feed it
an Email already resolved with A's defaults/overrides and do not merge A's templates with B's. Primary and failover are alternatives,
not parent and child. For an ordinary single value, keep the established order:

```text
selected Mailer override -> supplied Email -> selected Mailer default
```

There is no cross-Mailer precedence or inherited shared base. Configure common behavior independently on both Mailers, possibly using
the same Email template. Reusing a template does not create a new inheritance mechanism. Absence and explicit false retain their meanings.

| Value shape | Existing selected-Mailer contract |
| --- | --- |
| Single or compound value | Use the precedence above; retain the field's existing whole-value boundary rather than recursively merging signing/DSN configuration members. |
| Headers | Combine distinct keys from the selected Mailer and supplied Email. For a conflicting key, the higher-priority contribution replaces the complete value list. |
| Recipient, attachment and embedded-image collections | Retain additive behavior in the established override, supplied, default order, using only the selected Mailer's templates. Preserve intentional repeated occurrences. |
| Recipient-specific settings | Preserve the scope rules in ADR 0001; fallback does not replace an explicit recipient certificate or notification preference with an Email fallback. |

No field filter limits this to SMTP-required configuration. The selected Mailer can contribute BCC recipients, bodies, headers,
attachments, sender details, signing configuration and other fields already participating in Email governance. Only the selected path
contributes ordinary templates: neither an unselected failover nor an unsuccessful candidate leaks its ordinary defaults/overrides into
the actual submission. Applicable mandatory message locks are retained separately under the accepted restriction boundary.

The submitted Email's default/override suppression, including per-property exclusions, applies to the selected Mailer. Suppression flags on
templates do not introduce a separate nested policy. Repeated template setters and clear operations retain their standalone meanings:
an explicit defaults template replaces that Mailer's property-derived defaults, and clearing it restores that Mailer's snapshot defaults.

Resolve the selected Mailer's property-derived or explicitly supplied template once. Choosing B does not also apply A's snapshot defaults,
even if both came from one factory. Do not deduplicate recipients by address or attachments by value, and do not add a cross-Mailer
template-provenance system merely to undo duplicated governance. Ordinary template replacement behavior remains unchanged.

Resolve each candidate from the original Email and the selected path's templates. Failed connection setup discards that candidate's
effective configuration; B is never prepared from A's effective Email. Perform content conversion and signing for the selected usable
connection, without attachment reads or MIME serialization merely to evaluate failed connection candidates. Validate the effective
configuration and determine the intended envelope before its sending-allowance reservation; the selected Mailer's extra BCC must
participate in ordinary envelope resolution. Any resulting BCC recipient must be included in recipient cost, locks, receipt facts and
normal rejection handling; do not count the header/model list instead of the actual resolved envelope.

Apply the accepted scoped locks after selected-template resolution: retain the originating and participating failover Mailers' mandatory
message requirements, with connection settings governed by each approved destination. A conflict is not a connection failure to
route around. Exact Emails keep their ordinary-template bypass and authoritative bytes; a failover header or ordinary BCC
default must not silently rewrite them. Prebuilt signed/protected bytes likewise retain their existing preservation boundaries.

#### Concrete use case: copy maintainers only when the failover Mailer is used

Primary A and failover B each configure the same archive BCC and application header. A also configures its own account header; B
configures its account header and a default BCC to maintainers. The archive copy is shared because it was configured on both Mailers,
not because B inherits A's templates. If this ordinary archive default is configured only on A, it is absent during fallback to B.
This example uses composed Email without default suppression or an explicit recipient-envelope override. Those existing controls retain
their meaning; a failover BCC is an ordinary template contribution, not an unsuppressible lock. Mandatory recipient inclusion remains ADR 0025's domain.

| Actual selected path | Effective headers | Additional envelope recipients |
| --- | --- | --- |
| A selected | A's configured application header and account header | A's archive BCC |
| A falls back to B | B's configured application header and account header | B's archive BCC and maintainers BCC |
| Return to A between batch emails | Shared application header and A's account header | Archive BCC; no leftover maintainers BCC from B |

For a one-recipient business email, the B submission consumes one message attempt and three recipient attempts when those two BCC
contributions each contain one address. It is one submission of the email, not a separate incident-notification message. A's unused
reservation is released after its failed setup; B is charged under B's applicable rules when its provider is invoked.

The maintainer copy carries the actual message content. It is not a durable alert or a promise of delivery, and an added BCC can change
authorization, encryption-key requirements, quota use and partial-rejection behavior. Retain normal failure semantics if a configured
recipient is rejected; do not silently remove the copy to make the send appear successful. Automatic incident logging remains available
independently of this optional message-policy use case.

#### Relationship to existing cluster behavior

Today's [selected-session preparation](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java)
adds selected locks, and [EmailGovernanceImpl](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/EmailGovernanceImpl.java)
explicitly does not reapply a selected cluster member's ordinary templates to an already prepared Email. The Configuration page nevertheless
describes server-specific templates as a clustered-Mailer use case. Do not present the proposed selected-template behavior as already implemented.
During implementation, reconcile ordinary cluster selection, failover selection and documentation without silently changing unrelated
cluster behavior. Any actual change against released behavior needs migration guidance; the additive failover API alone does not.

### Accepted failover lifetime: supplied instances are borrowed, private instances are owned

The maintainer accepted this refinement on 2026-10-07. It supersedes the earlier blanket statement that every failover is borrowed;
the original rule remains unchanged for application-supplied instances.

| How the failover Mailer was obtained | Ownership and shutdown |
| --- | --- |
| Application supplies an existing Mailer through `withFailoverMailer(failoverMailer, policy)` | Borrow it. Closing the primary leaves the failover Mailer, its executor and pool registration usable. The application closes the failover Mailer separately after all its users drain. |
| SJM constructs a private failover Mailer from a named configuration | The primary owns it. Closing the primary drains its admitted work and observer handoffs, then closes its private failover Mailers and their owned resources. |

The primary retains ownership of its normal execution, coordination and connection resources. Apply the same distinction at nested links:
close privately constructed descendants, but do not cross into application-owned borrowed Mailers merely because they are reachable.
No new factory `close()` contract or public resource-manager object is introduced; the factory still owns no Mailer resources.

Fallback use must participate in B's lifecycle accounting. B beginning shutdown rejects new uses and drains already admitted ones,
including fallback work, before disposing resources. Do not borrow its Session/transport behind its lifecycle guard or make graceful
failover close abort another Mailer's active submission unexpectedly. Specify racing admission/shutdown, retained views, shared users
and self-deadlock detection before implementing this boundary.

The initiating primary's execution queue, operation control and terminal observer govern its logical send. B's queue and observer govern
direct calls on B, not fallback work initiated on A. Do not enqueue the same email successively on both executors or report it twice.
B supplies its own connection resources, sending rules, templates, failover policy and applicable checks. If C is reached through B,
its lifecycle participates too. The concrete admission/shutdown mechanism still needs implementation design and verification.

Named definitions are not automatically shared singleton Mailers. Each primary owns only the private instances constructed for it;
sharing a live failover remains explicit through the Java API. Keep factory/destination histories and explicitly shared sending allowances
under their existing owners: constructing another private instance must not reset relevant history or multiply configured allowance.
Do not confuse reuse of an immutable definition or runtime history with shared ownership of connections/executors.

Spring's default Mailer destruction and normal CLI cleanup must close privately constructed descendants through that owner. User-provided
Spring beans remain borrowed, with their existing lifecycle declarations. Roll back privately created resources if construction fails,
without closing any application-supplied instance. Configuration loading/reference validation alone must not open SMTP connections.

### Accepted cross-factory failover use

Allow a failover Mailer built by a different SimpleJavaMail factory. This lets an application reuse existing Spring-managed Mailers or separately
loaded primary/failover snapshots rather than rebuild both just to enable failover. Borrow the configured failover Mailer; do not reload its sources,
replace its snapshot or clone its resources.

Preserve captured connection settings, selected-Mailer templates and sending-limit histories. Equal group names in separate factories
do not merge rate histories. Apply the separately accepted scoped locks and approved failover relationships across this boundary without
merging ordinary templates or configuration snapshots. Cross-factory use does not remove a participating Mailer's message requirements.

Retain destination observations with the runtime owning that configured destination, rather than copy them into another factory merely
because another primary borrows it. Explicit reuse of the same Mailer/registration can share its existing destination context;
independently configured Mailers remain independent even with equal snapshots or hostnames. Evaluate each relevant fallback policy's
permissions against those facts. Cross-factory participation is accepted, not implemented; pin down destination identity and the exact
runtime boundary in the implementation plan, preserving the owning factory's context rather than silently copying or merging it.

### Accepted named Mailer configuration

Resolve named Mailer configuration blocks through the existing loader and immutable snapshot. Each definition describes an independently
configured destination using existing property tails, types, validation, diagnostics and sensitivity rules. Ordinary missing settings
must not inherit the primary's credentials or Email templates; resolve that definition's normal builder defaults instead. Mandatory
message restrictions still follow the accepted logical-send boundary.

The following alternatives were reviewed and the named-definition model accepted on 2026-10-07:

| Configuration model | Benefit | Decision and cost |
| --- | --- | --- |
| Names identify application-created Mailers or Spring beans | Reuses existing objects and their ownership. | Not the property model: requires application wiring and cannot by itself provide a self-contained CLI configuration. Explicit Java instance composition remains supported. |
| Names identify complete Mailer configuration definitions | Self-contained Java/Spring/CLI configuration, readable chains, reusable definitions and useful diagnostic names. | Accepted: SJM constructs private failover Mailers and the primary owns their cleanup. |
| Embed a complete configuration under every failover | Simple for one failover. | Not selected: deeper chains become unwieldy and shared definitions are duplicated. |

This is the accepted shape with illustrative new property names, not implemented configuration. The implementation plan settles final
spelling, converter details and policy fields; the example supplies no arbitrary retry-duration default. Credentials are omitted.

```properties
# Primary
simplejavamail.locked.smtp.host=relay-a.company.com
simplejavamail.locked.transportstrategy=SMTP_TLS
simplejavamail.locked.defaults.bcc.address=archive@company.com

# Approved failover relationship
simplejavamail.locked.failover.mailer=standby

# Independently configured failover
simplejavamail.locked.mailers.standby.smtp.host=relay-b.company.com
simplejavamail.locked.mailers.standby.transportstrategy=SMTP_TLS
simplejavamail.mailers.standby.defaults.bcc.address=maintainers@company.com
```

Each definition's failover policy sits alongside its own failover reference, matching the paired Java API. Nested references describe
the accepted chain and preserve each Mailer's policy; they do not flatten it into one list. No additional public SMTP-profile builder
or application-maintained object registry is required. Applications with existing Mailers retain `withFailoverMailer(existingFailover, policy)`.

Names are local to the captured configuration, not Spring bean names, hostnames or process-global lookup keys. Resolve all referenced
definitions once; do not reload configuration or look up beans during sending. Reject missing references, cycles and knowable lock
conflicts during construction before they can first appear during an outage. A locked reference protects the resolved permitted
relationship, including its nested alternatives; an arbitrary replacement cannot qualify merely by claiming the same name. Individual
destination values that must stay fixed retain their own locks, as the example shows.

Spring keeps its shared Environment-to-loader path and constructs the normal default Mailer. The CLI uses its normal sending path and
the same snapshot; it does not need a container of prebuilt Mailers. Extend wildcard discovery, metadata, provenance and CLI profile
identity for the captured definitions without a second parser or hidden source overlay. Configuration objects remain supplied-data
holders; builders resolve defaults and initialize owned runtime resources. There is no automatic reload or new background executor.

Factory-scoped history remains separate from configuration and privately owned transport resources. The implementation must map named
definitions to stable configured-destination contexts within their owning runtime, preserving sending-limit sharing and observation
isolation. Equal names/configurations in independent factories do not establish shared identity. Test repeated primary construction and
closure so it neither multiplies configured sending allowance nor disposes another primary's privately owned connections.

### Accepted paired failover/policy configuration

Pair the application-supplied failover Mailer and immutable supplied-data policy in `withFailoverMailer(failoverMailer, policy)`. Return the normal builder so unrelated
configuration remains fluent. Do not add a standalone policy setter or a staged interface after choosing the failover Mailer. Replacing that Mailer
also replaces the policy; clearing the relationship removes both. No failover means unchanged existing sending behavior.

The entry-point shape is accepted, but not implemented. Policy construction, concrete options, reset naming, defaults and property keys
still need implementation design. Named configuration supplies the property-backed equivalent with private ownership as described above.
For example, `policy` below is an already constructed policy; no policy-builder signature is implied:

```java
Mailer primary = mail.mailerBuilder()
    .withSMTPServer("primary.example.org", 587)
    .withFailoverMailer(failoverMailer, policy)
    .buildMailer();

primary.async().sendMail(email);
```

The same setter configures a nested failover. In this planned example, B keeps its own fallback to C:

```java
Mailer failoverB = mail.mailerBuilder()
    .withSMTPServer("failover-b.example.org", 587)
    .withFailoverMailer(failoverC, policyB)
    .buildMailer();

Mailer primaryA = mail.mailerBuilder()
    .withSMTPServer("primary-a.example.org", 587)
    .withFailoverMailer(failoverB, policyA)
    .buildMailer();
```

Sending on A uses A's logical operation and terminal observer. A-to-B eligibility follows `policyA`; B-to-C eligibility follows
`policyB`, within that same cancellation/deadline budget. Only the ultimately selected Mailer's templates govern the original Email.

The policy supplies failure permissions and retry/backoff timing, not transports, mutable history, a callback, a generic exception
predicate or another public health coordinator. Route definition and policy behavior remain separate responsibilities even though the
builder accepts them together. A policy has no independent effect when no failover is configured.

FailoverPolicy has no capacity-spillover rule. Preserve the existing pool-claim wait and timeout rather than adding a failover-specific
capacity timer, changing shared pool settings or routing around deliberate rate/admission waits. Only a qualifying connection failure
can make another destination eligible. Connection-attempt and overall-operation budgets remain bounded; none can extend the remaining
deadline or revive cancelled work. The same connection-failure policy applies with pooling disabled.

Resolve defaults in builders and expose the immutable resolved policy through OperationalConfig. The policy owns no transports, executors,
counters or Session mutation. Property, Spring, CLI, diagnostics and locked-setting representations must resolve the same choices rather
than introducing another independent configuration layer. Their exact keys and converter shape remain to be agreed.

### Policy-driven reconsideration, without a health subsystem

An actual sending operation evaluates candidate eligibility using the configured policy and observations from earlier connection attempts.
Keep only the private facts needed for that evaluation: classified setup failures, relevant attempt counts and monotonic timing information.
Do not maintain a parallel public healthy/unhealthy/probing model, introduce a health-indicator API or add an independent background probe
loop, circuit-breaker framework or executor.

The agreed traffic shape is:

1. A fails before submission in a way that permits fallback; try eligible B immediately, without waiting for A's retry interval.
2. B sends according to its own configured limits and burst/spreading behavior. A's backoff does not throttle a working B.
3. Later sends continue through B while A is not yet eligible for another connection attempt. If an eligible attempt to A still fails,
   preserve B's normal sending behavior and update only A's applicable retry timing.
4. When a policy-permitted attempt obtains a usable A connection, later individual sends prefer A again, under A's configured limits.
   A simple batch finishes its current email on B, then can move the untouched remainder to A at the next email boundary.
   An email already being submitted and an explicitly retained open-connection scope stay on B; never replay either.

If B also fails, B has its own retry eligibility and any further eligible destination can be considered. Backoff controls reconsideration
of a failed destination; attempt limits and the remaining deadline separately bound the operation. No scheduled health probe discovers
recovery: an actual operation obtains the usable connection. This records observed setup behavior, not server health, SMTP acceptance or
successful final delivery.

Preserve existing sending-limit history across route changes. Do not reset either destination's allowance to create a fresh burst or
transfer A's counters to B. Explicitly shared rate-limit groups remain shared as configured. Returning to A resets only the relevant
connection-failure backoff after usable setup, not message/recipient accounting. Once A is usable again, normal pool/rate waiting on A
is preserved. A busy but working A is not a reason to keep routing new work through B.

Minimal bookkeeping still needs thread-safe updates and bounded coordination so concurrent sends do not all make the same delayed
primary attempt at once. Reserve eligibility under a short critical section; never hold its lock while waiting, connecting, preparing MIME,
submitting, cleaning up or invoking application code. No named health states are required to express this contract. The concrete mechanism
and interaction with existing pool maintenance/reconnection must be characterized rather than assumed.

Reevaluate the invoking policy's permissions when using cached failure facts, including the independent authentication/certificate options.
Local pool saturation and sending-rate waits must not become a cached claim that the relay is unhealthy. Do not invalidate existing usable
connections merely because a different connection attempt failed.

Here, primary retry/backoff means **connection setup before provider submission invocation**. It does not mean resending an Email. Any
repeated setup attempts share the operation's cancellation, deadline and finite attempt budget; do not nest unbounded retry loops or restart
the overall timeout. The agreed baseline prefers a failover destination after one failed primary setup attempt. Primary reconsideration defaults to a
configurable fixed interval; optional increasing backoff has a configured cap. Concrete periods, repeat controls and destination-identity
representation still require agreement. General submission retry/backoff remains separate under #756.

### Factory-local connection history

The factory's runtime owns the minimal classified connection observations and recheck coordination shared by participating Mailers.
Keep this mutable bookkeeping separate from its immutable configuration snapshot, resolved policies and individual operation reports.
One Mailer still coordinates each logical send and owns its operation's execution, cancellation, deadline and resources.

Share only for the same configured destination, not merely because two configurations name the same hostname. The route API must provide
an unambiguous configuration identity; differing credentials, TLS or proxy configuration must not be silently merged by an endpoint guess.
Separate factories have separate histories even when their snapshots contain equal values. No process-global registry or public shared
coordinator is introduced. Closing one participant must not reset the observations used by another participant in that factory.

Coordinated primary rechecks prevent every participating Mailer from independently repeating the same due connection attempt. Evaluate
the invoking policy against retained facts, including its independent authentication/certificate permissions. A permissive Mailer must
not make a stricter one silently bypass a recorded failure. Sharing failure history does not transfer sending-limit counters, reset
deadlines, revive cancelled operations or make a successful connection prove SMTP acceptance or final delivery.

### Accepted initial scope: find a usable connection, submit once

For classified availability failure before submission invocation, clean up the failed candidate and try the next eligible destination.
Once provider submission invocation begins, preserve the current result/failure behavior and do not automatically replay the Email.
This conservative portable boundary includes provider-internal preflight in submission, even if that preflight sends no SMTP commands.

Known wrong credentials, certificate/TLS verification, local message/configuration errors, missing required capabilities and locked-value
conflicts are not blanket availability failures. Known authentication/certificate-verification failures stop by default; each has its own
explicit failover permission. No option weakens requirements or strips a rejected option. Pool saturation remains backpressure and never
triggers failover; sending-rate waits do not become availability failures.

Broader recovery belongs to #756 and needs its own evidence/replay contract before implementation. Known retryability alone does not
authorize a new relay. Confirmed, partial or uncertain acceptance always prevents whole-message replay. Recipient splitting and repeated
same-destination submission are not implicitly included.

### Preserve configuration and resource owners

Select first, verify applicable originating/selected locks, resolve the actual envelope, reserve the selected destination's allowance,
then acquire. On a recoverable acquisition failure, finish disposal and release unused allowance before the next candidate.
Provider invocation charges the selected destination as [ADR 0027](0027-factory-scoped-sending-limits.md) requires.

Credentials, OAuth refresh, TLS, proxy resources, provider settings, content support and maximum-size checks remain destination-owned.
Resolve the original Email only through the selected Mailer's templates, then apply the accepted mandatory message restrictions from the
participating route. Never carry unsuccessful candidate template changes into another candidate. Locked values are not a minimum-security policy language;
do not silently replace an applicable fixed value on the assumption that another route is stronger. Different-proxy routes require
explicit lifecycle verification before being promised.

No borrowed transport or started proxy is held during ordinary allowance waiting. Fence/retire each candidate's cancellation authority
before another candidate or borrower can use the resource. Do not publish completion until required failed-acquisition disposal is
acknowledged. Per-candidate limits can shorten, never reset, the remaining total deadline.

Do not permanently record a recoverable candidate error as the final operation failure before the routing decision. Preserve the
existing first-terminal-failure/stop arbitration, without allowing earlier recovery history to hide a later cancellation or timeout.

### Preserve scope and observation meaning

Ordinary single sends support direct and pooled routes. A simple batch or open-connection scope may use fallback to obtain its initial
connection. Without failover configured, both retain their existing one-connection behavior.

With failover enabled, a simple batch reuses its connection for successive emails but may change destination between them. Finish the
current email's submission and required per-email observer handoff before switching. A failed email still stops the batch; recovery must
not hide that failure or replay it. Reconsider A only when the policy permits, and switch the untouched remainder only after obtaining
a usable connection that passes the applicable checks. A permitted failed A recheck leaves B eligible without imposing A's backoff on B.

For example, after emails 1–414 finish on B, a due recovery check obtains a usable A connection; emails 415 onward use A. Keep one
lazy iterator, operation, completion handle, cancellation control and overall deadline. Do not reopen, pre-consume, clone or restart the
iterable. Untouched emails still have no outcomes until reached, and each reached email retains its existing once-only observation.

Use normal connection retirement and the existing cancellation-aware acquisition/cleanup primitives, not public
`MailSend.requestCancellation()`, to stop using B. Retire B's abort registration before reusing its resources, and ensure actual cancellation
or deadline expiry can stop any newly acquired A resources. Cancelled work must never be revived by a route change. Use A's actual Session,
credentials, proxy, locks and sending-limit history for subsequent emails, without transferring B's reservations or counters.

`withOpenConnection` remains pinned after initial acquisition because it explicitly promises a retained connection for caller-managed
work. Do not rerun the application callback or substitute another connection inside that scope. Standalone arbitrary batch callbacks
have the same non-reexecution boundary. Reconcile the opt-in simple-batch exception with ADR 0015 and public Javadocs during implementation.

Third-party providers can participate only at boundaries the orchestration can establish honestly. Physical abort/deadline support is
a separate requirement. Do not automatically reinvoke CustomMailer or standalone arbitrary batch callbacks. Probing and connection
tests remain targeted diagnostics, not opportunities to disguise a broken primary with a successful failover.

Publish one terminal outcome and completion for the logical email send, after ordinary cleanup. Return the exact actual-submission
receipt. Earlier connection failures are useful routing history, not invented SMTP receipts. Acceptance followed by cleanup failure
retains its acceptance facts and never starts another submission.

Put bounded immutable, ordered connection-attempt history in the existing `MailSendDiagnostics` supplied through the terminal observer.
Keep the submission receipt about actual SMTP submission, without introducing another result wrapper. If A's setup fails and B's send
succeeds, publish one successful outcome with an empty `getFailure()` and history retaining A's connection failure. If all eligible
connections fail, retain normal exceptional completion and one unsuccessful observer outcome. Do not invoke a separate per-server
application failure handler or manufacture a failed-email outcome for an intermediate connection error.

Concrete history signatures remain to be settled before implementation. Current diagnostics contain one endpoint, not a route history;
this decision does not treat that missing API as already implemented. Distinguish actual attempts for this operation from cached facts
that explain a skipped destination; never present an earlier operation's failure as a newly performed connection attempt.

### Operational visibility without application handlers

Log relay incidents through the existing logging mechanism even when no `MailSendObserver` is configured. Applications may route their
ordinary logs into alerts without having to write SMTP recovery code. Library logging is not guaranteed delivery of an operations alert;
the application's logging backend still controls collection, levels and retention.

- ERROR: a genuine relay connection, authentication or certificate-verification failure, even when an eligible failover Mailer subsequently works.
- INFO: meaningful activation of a failover and return to the primary after obtaining a usable primary connection.
- DEBUG: repeated unsuccessful rechecks of an already reported problem.

For example, the intended operational log is:

```text
ERROR Primary relay A failed certificate verification; trying a configured failover Mailer.
INFO  Using failover relay B. The primary certificate problem remains unresolved.
INFO  Connected to primary relay A again; subsequent emails will use A.
```

These are planned examples, not output from an implemented feature. Emit a new incident notice for a materially changed problem, but
do not log an identical ERROR or failover-activation INFO for every email in a long outage. Coordinate duplicate suppression using the
factory-local observations already required for failover; do not add a public health model or another background reporting loop. Keep
logging outside bookkeeping locks. A usable primary connection, not a successful failover send, ends the corresponding connection incident.

Use bounded escaped route labels and concise classified reasons; omit credentials, full connection URLs, message content and raw
SMTP transcripts. Logging must not change receipt/completion classification or invoke a new application failure callback. Include the
level distinction, suppressed repeats and the unresolved-primary wording in the website walkthrough alongside optional observer history.

### Show application-owned retry handling in the website

The failover walkthrough must distinguish finding a connection from retrying a completed submission attempt. Include a concrete
`withMailSendObserver(...)` recipe, job/envelope handling and illustrative output, using the existing receipt's `getRetryDisposition()`
and `getRetryableRecipients()` rather than treating every unsuccessful outcome as unsent mail.

The observer should hand eligible work to an application-owned queue or scheduler. Do not sleep or recursively resend inside the callback;
callbacks can run concurrently, and a retained batch/open-connection scope may still own its transport. Explain how the application retains
its original Email and correlates the outcome to a job without assuming Message-ID is present or unique. Observation is not durable delivery
of a job event, and reusing a Message-ID does not provide server-side deduplication.

Show whole-envelope and safe-unaccepted-recipient decisions without replaying accepted or permanently rejected recipient occurrences.
Absent receipts, unknown acceptance, cancellation, logging-only mode and acceptance followed by cleanup failure must not trigger blind
resends. Application code owns delays, attempt limits, persistence and any address/message corrections until a separate retry policy is accepted.

## Alternatives considered

| Alternative | Benefit | Reason not recommended as the initial design |
| --- | --- | --- |
| Caller try/catch around two Mailers | No library expansion; maximum application freedom. | Repeats safety/lifecycle decisions and easily replays accepted mail. Different Mailers can change governance. |
| Generic-pool failover only | Useful acquisition convenience. | Hides the actual destination from locks and allowance reservation; generic pools cannot assess SMTP acceptance. |
| Independent retry at each layer | Locally simple loops. | Multiplies attempts, deadlines, waits and cleanup complexity; obscures the logical result. |
| Compose Mailers by calling their public send operations successively | Reuses complete send entry points. | Repeats scheduling and observation, and a failed public send does not prove submission has not begun. Honor nested routing inside one controlled operation instead. |
| Third resilient Mailer with inherited or merged templates | Provides a common outer configuration layer. | Adds a template hierarchy that is not intended between alternative endpoints. Configure the failover on A and shared behavior on each Mailer that needs it. |
| Flatten failover Mailers into a list and ignore their own fallback configuration | Simplifies traversal. | Makes a configured Mailer behave differently when borrowed. B's fallback to C must remain honored under B's policy. |
| Standalone policy setter or a staged failover-options interface | Separates policy editing or constrains builder order. | Allows an ineffective policy without a failover or adds another builder surface. Pair failover and policy in the ordinary fluent setter. |
| Use only connection settings, or whitelist relay-related Email fields | Smaller apparent policy surface. | Discards useful selected-Mailer policy such as a failover BCC, attachment or signing template. Its full existing templates apply to the original Email. |
| Apply each Mailer's governance to the previous effective Email | Reuses the current resolver sequentially. | Promotes defaults into explicit values, duplicates collections and leaks A's configuration into B. Resolve only selected-Mailer governance against the original Email. |
| Cancel and restart a batch when the primary returns | Reuses the public stop request. | Stops the whole operation, may leave the current submission uncertain and cannot safely resume an arbitrary iterable. Switch only untouched work between emails instead. |
| Route elsewhere when the primary pool is busy | Can increase throughput under load. | Not part of failover: preserve ordinary waiting; pooling, clustering and load balancing own capacity management. |
| Treat every setup exception as an outage | Broad apparent coverage with little classification work. | Can bypass explicit authentication/security choices or conceal bad configuration. Support ordinary outages with reliable evidence and reserve stopping for genuinely unknown causes. |
| Always fall back after wrong credentials or certificate verification | A separately configured failover Mailer may remain usable. | Can hide primary configuration failure and change performance/routing. Selected design defaults to stop, with two independent explicit opt-ins and retained failure history. |
| Retry on every transport exception | Handles more outages automatically. | Exceptions include partial/uncertain acceptance, cleanup, permanent rejection and local configuration failure. |
| Replay every definitely rejected submission | Wider resilience than connection fallback. | Needs repeatable finalized content, trustworthy provider evidence and deliberate message/ENVID identity handling. Deferred to #756. |
| Automatically resend an unaccepted subset | Can make partial progress. | Changes the envelope/recipient-occurrence and result contracts; ambiguous and accepted occurrences must never be replayed. Deferred. |
| Durable queue, broker or distributed registry | Coordinates applications and persists work. | Different infrastructure and failure contracts; not this embedded-library feature. |

## Consequences and tradeoffs

Connection-only fallback gives applications practical outage recovery without replaying submissions or reading/serializing message content for failed candidates.
It works without pooling and can reuse the existing pooled lifecycle when available. Explicit failover Mailers preserve application control over
where content may be submitted.

The cost is more route ownership, candidate classification and cleanup orchestration. A finite attempt count is not a hard time bound;
DNS, custom factories, external providers and callbacks can remain non-cooperative. Per-candidate time allocation and cooldown also
affect how quickly a failover destination is reached and how soon the primary is reconsidered.

A policy-enabled simple batch can reuse connections across successive destination segments rather than promise one physical connection
for its whole lifetime. A recovery connection attempt can pause that batch at an email boundary and consumes its remaining deadline;
this does not impose the primary's backoff on other work using B. No zero-cost or instantaneous recovery guarantee follows.

The accepted initial scope deliberately stops on a lost connection after provider invocation, including cases managed Angus may know
are unsent. It is useful but not general automatic SMTP retry. Broader replay is possible only after its additional contract is chosen.

Different relays can have different sender authorization, routing, privacy, quotas, provider capabilities and downstream behavior.
Explicit configuration cannot guarantee equivalent delivery. SMTP acceptance remains different from final delivery.

Independent selected-Mailer templates deliberately allow the effective message and envelope to vary by destination. This is more useful
than transport-only fallback but requires isolation tests and explicit configuration of shared behavior on each Mailer. A failover BCC or
attachment is a real message contribution, not free operational metadata. Do not promise identical content, recipient count or signing
output across alternatives. Nested fallback adds traversal and recovery bookkeeping without adding another public operation or template layer.

Scoped locks and approved failover relationships put compatibility validation in SJM and route configuration with its owner. They preserve
mandatory message requirements without making a locked primary hostname prohibit every alternative. This adds configuration-reference
and conflict-checking work. An incompatible approved route can still stop a send; configuration owners must supply usable alternatives,
and applications cannot solve every outage without changes to their centrally supplied configuration. The decision matrix above records
why this cost is preferable to silently dropping restrictions or asking every application developer to recreate them.

Named definitions make failover usable from centrally supplied properties in Java, Spring and the CLI without an object registry in
application code. SJM must validate references and close privately constructed failover Mailers, including rollback after construction failure.
The supplied/private ownership distinction follows who created the instance; a name alone does not create a shared live Mailer or pool.
Configuration reuse must not accidentally reset factory history or multiply allowance. These obligations add implementation work without
another lifecycle object or send API for users.

## Boundaries and related decisions

This proposal narrows [ADR 0011](0011-transport-neutral-submission-outcomes.md)'s application-owned retry boundary only for an explicitly
authorized fallback before submission, as selected for the initial scope. It does not redefine receipt statuses, retry guidance or uncertainty.
Any broader amendment belongs to #756 and needs its own explicit accepted wording.

- [0001](0001-email-configuration-scopes-and-inheritance.md) and [0002](0002-email-defaults-and-overrides.md): preserve existing scopes and
  selected-Mailer governance, including value-shape, suppression, replacement and recipient-specific rules; add no template hierarchy.
- [0003](0003-immutable-configuration-snapshots.md): its accepted named-configuration extension preserves immutable definitions, runtime-owned history and the factory's resource-free lifetime.
- [0007](0007-provider-neutral-mime-boundary.md) and [0013](0013-exact-eml-submission.md): providers own submission; exact/protected bytes remain authoritative.
- [0012](0012-terminal-send-observation.md) and [0026](0026-actual-send-diagnostics.md): one terminal observation with honest timing and receipt retention.
- [0015](0015-execution-views-and-transport-pooling.md), [0016](0016-bounded-async-admission-and-shutdown.md) and
  [0017](0017-deadlines-and-physical-cancellation.md): shared execution, lifecycle, deadline and lease fencing.
- [0021](0021-mandatory-starttls-configuration-consistency.md), [0023](0023-per-message-requiretls.md) and
  [0025](0025-factory-scoped-locked-configuration.md): fallback does not relax connection, onward-delivery or locked requirements.
- [0027](0027-factory-scoped-sending-limits.md): reserve/charge against the selected registration; sharing does not follow hostname guesses.
- [0028](0028-per-email-recipient-rejection-handling.md): partial continuation still fails overall; it does not authorize failover replay.
- [API expansion workflow](../API_EXPANSION_WORKFLOW.md): Java/property/Spring/CLI parity, supplied-data configuration and provider boundaries.

Keep #382 and #722 closed. Distributed coordination (#753), PIPELINING/CHUNKING (#699), durable spooling and accepted server queues
remain outside this ADR. No implementation, release-readiness or runtime-verification claim follows from acceptance of this ADR.

## Implementation obligations

First save a reviewed implementation plan that includes the documentation coverage below. This pass records architecture and future
obligations only; it does not implement public methods, edit current website guidance or claim runtime verification.

Verify candidate ordering/exclusion, selected registration retirement/replacement, no-pool sending, exhaustion, classified setup failures,
and unchanged default behavior when no policy is configured. Include healthy-capacity waits and wrong-authentication/certificate cases
according to the maintainer choices rather than a generic exception catch.

Verify both opt-ins independently: neither enabled, authentication only, certificate verification only and both enabled. Include an expired
primary certificate with a valid failover Mailer, an invalid failover certificate, authentication rejection with only certificate fallback enabled,
and strict policies observing shared failure history. Assert no submission on a failed-verification connection, no bypass of applicable
security/locks, one actual submission after usable setup and retained primary-failure facts on successful failover use.

Use synchronization-driven tests for concurrent fallback, cancellation, overall deadlines, cleanup acknowledgement, stale leases,
pool-size-one reentrancy, selected-server limits and cooldown recovery. Cover caller-owned Sessions, supported third-party providers,
proxy lifecycle, locks and exact/protected bytes. Preserve attachment read counts and Message-ID/ENVID contracts.

Use deterministic clocks/barriers to verify policy-derived retry eligibility, bounded primary setup retries, backoff/reset semantics,
concurrent reconsideration and history isolation. Prove no new background probing or health-state API is required and that retained
failure categories cannot bypass a different invoking policy's permissions. Characterize the existing pool's maintenance behavior.
Cover fixed-interval reconsideration and optional increasing capped backoff without interpreting example durations as defaults.

Verify multiple Mailers from one factory share observations and coordinate due rechecks for the same destination, while separately
configured destinations and factories with equal snapshots remain independent. Cover participant closure, registration replacement,
different policy permissions and cancelled recheck ownership so shared facts cannot become stale authority over another operation.

Verify the agreed A-to-B-to-A traffic shape with independent destination timing: A backoff does not delay usable B, a failed A recheck
does not alter B's spreading/burst rules, successful A setup changes later routing without migrating an email being submitted on B, and route switching
does not reset or transfer sending-limit counters. Cover explicitly shared allowances and prove ordinary pool/rate waits do not trigger failover.

Verify return-to-primary inside a large one-shot lazy batch: complete the current email on B, switch the untouched remainder to A,
retain the same operation/iterator/deadline and produce each per-email outcome once. Cover failed recovery checks, first-email failure,
observer dispatch, actual cancellation and deadline expiry racing resource handoff. Assert no public cancellation request is used for
automatic failback, no repeated content reads solely for routing, correct selected-server ownership and unchanged pinned open-connection scopes.

Capture logs with and without an observer to verify genuine incidents at ERROR, meaningful route transitions at INFO and expected
repeated rechecks at DEBUG. Cover concurrent duplicate suppression, materially changed failures, successful failover
submission while the primary remains broken and usable-primary recovery. Verify bounded escaping and omission of credentials/content.
Assert one terminal observer outcome, empty outcome failure after a successful failover send, unchanged normal exhaustion failure and
history distinguishing current connection attempts from cached earlier observations. Do not turn logging into per-server failure callbacks.

Assert one logical outcome/completion, exact final receipt identity, useful exhaustion history and no replay after partial acceptance,
lost final reply or acceptance followed by failed cleanup. Preserve lazy batches and explicit open-connection pinning. Verify property
precedence/provenance/redaction, Spring metadata, generated CLI, classpath/JPMS and documentation without claiming future verification
has already happened. Verify the observer-based retry recipe against the existing API, including safe recipient selection, job correlation,
application-owned scheduling and its acceptance/uncertainty exclusions.

Verify independent selected-Mailer templates: existing single/compound precedence, conflicting/distinct header keys, additive BCC/attachments,
explicit false, suppression at both categories and per-property exclusions, replacement/clearing and snapshot defaults. Cover a failover-only
maintainer BCC, an A-only ordinary archive default absent on B, explicitly shared templates, A-to-B-to-A isolation and concurrent reuse of the same Email.
Assert real envelope cost and receipt recipient facts, deliberate duplicate occurrences and no application of unselected templates. Cover signing/encryption, exact/protected bytes,
local template failures, and no repeated content reads solely for connection fallback. Pin down caller-thread preparation and scheduling
contracts rather than silently moving all preparation to workers or labelling route-specific preparation as pure connection time.

Verify A-to-B-to-C nesting under distinct policies without ignoring B's configured fallback or starting another async job/observer outcome.
Cover cyclic configurations, finite traversal, cancellation/deadline propagation, shared failover use and recovery toward earlier destinations
between batch emails. Reconcile validators, maximum-size checks, the accepted scoped lock rules, retained views and graceful shutdown in the
implementation plan. Do not silently discard an applicable restriction or make a shared failover Mailer unusable when another primary closes.
Verify one initiating executor/observer and direct B calls using B's own executor/observer. Implement the accepted supplied/private lifetime decision.

Verify named definition resolution, missing references, cycles, independent defaults and captured source isolation through Java, Spring
and CLI construction. Check metadata, per-leaf provenance/redaction and daemon profile isolation for route, policy and lock differences.
Prove no bean lookup or configuration reload occurs during sending. Cover private-failover cleanup on normal close and partial construction
failure, nested and mixed supplied/private relationships, and preservation of borrowed application Mailers. Repeated construction from
the same owning runtime must not multiply allowance or reset destination history; separate factories remain independent and private
transport resources are not silently shared. Configuration loading alone opens no SMTP connection.

Verify mandatory archive recipients and onward REQUIRETLS survive fallback, including nested A-to-B-to-C, direct B-to-C calls, separate
factories, template suppression/clearing, exact/protected messages and return to A between emails. Cover compatible and conflicting fixed
values, real envelope costing and unchanged deliberate recipient occurrences. Distinguish ordinary A-only BCC defaults from mandatory
message locks; do not leak restrictions from a previous batch email's selected route. Verify destination-specific hosts, credentials and
TLS locks without implicit security-strategy equivalence. Test centrally locked failover relationships against replacement, clearing and
unapproved nested alternatives using captured named definitions. Known composition conflicts must fail early,
runtime-only incompatibilities before submission, with actionable property-specific messages and no fallback around a lock violation.

### Documentation coverage required in the implementation plan

Keep one authoritative explanation of defaults/overrides and one failover walkthrough, with task-oriented entry points linking to them.
Documentation is an implementation deliverable, not something satisfied by copying this ADR into a feature page. Current website prose
remains version-neutral; release notes describe additions and migration notes cover only actual released compatibility/behavior changes.

| Documentation home | Required treatment |
| --- | --- |
| Public Mailer and builder Javadocs | Complete paired-setter, nested fallback, selected-only templates, suppression, ownership and scope contracts. Distinguish borrowed supplied instances from privately constructed owned failover Mailers. Implementation Javadocs refer to the interface source of truth. Explain ordinary versus exact Email and one initiating operation/observer. |
| README and canonical failover walkthrough | Small primary/failover setup, pooling-independent sending, independent permissions, bounded primary rechecks, logs/history and no post-invocation replay. Include the canonical ownership matrix comparing A-through-B, A-through-nested-C and direct-B calls, including scoped locks and approved relationships. Examples use agreed APIs. |
| [Configuration defaults/overrides](../../simplejavamail.org/src/pages/configuration.hbs) | Extend the canonical section without a new template hierarchy. Show unchanged selected-Mailer precedence, headers/additive values, failover BCC, suppression and clearing/replacement. Ordinary A-only settings do not carry to B; mandatory message locks do. Explain snapshot defaults, approved failover relationships and conflicts requiring the configuration owner's help. Reconcile the clustered-template claim with verified runtime behavior. |
| [Cross-cutting use cases](../../simplejavamail.org/src/pages/use-cases.hbs) | Add a discoverable resilient-sending route linking configuration, execution, security, pooling and diagnostics. Distinguish authorized failover routes from load balancing or another customer's healthy relay. Review affected batch/throughput wording. |
| [Email workload field guide](../../simplejavamail.org/src/guides/email-workloads.md) | Add concrete independent scenarios for primary outage, a failover-specific header/BCC, centrally approved failover Mailers preserving a mandatory archive copy, certificate expiry with a verified failover Mailer, and return-to-primary within a large lazy batch. Reconcile existing approved-relay, customer-isolation, backlog, quotas and shutdown scenarios; don't turn automatic connection fallback into automatic message retries. |
| Field-guide directory and downloadable examples | Keep [scenario metadata](../../simplejavamail.org/src/guides/email-workloads.11tydata.json), [Java examples](../../simplejavamail.org/src/assets/guides/email-workloads/FieldGuideExamples.java) and [usage notes](../../simplejavamail.org/src/assets/guides/email-workloads/README.md) aligned with the visible guide. Compile examples and check generated navigation, targets and cross-links. |
| Sending/execution and pool orchestration guidance | Explain cluster load balancing versus nested fallback, each Mailer's own policy, one initiating executor/observer and operation budget, selected-Mailer settings/rates, failure-driven failover versus unchanged pool/queue/rate waiting, batch failback, pinned scopes and supplied/private close/shutdown ownership. |
| Analyzing send results and Diagnostics | Show successful failover use with earlier setup failures, truthful actual-attempt versus cached history, effective recipient facts including BCC, logging without an observer and the observer-based application retry recipe. Keep acceptance, partial failure, uncertainty and delivery distinct. Explain targeted probes versus actual-send diagnostics. |
| Security, exact-message and integration references | Explain independent auth/certificate permissions without downgrades; route-specific signing/encryption and added-recipient consequences; unchanged exact-byte boundaries; caller-owned Sessions/provider limitations and applicable locks. |
| Properties, Spring and CLI guidance | Show named independent Mailer definitions, captured and lockable failover relationships, paired policy configuration and matching Java usage. Explain that names are not implicit bean lookups and private failover Mailers close with the primary. Reuse schema types, source diagnostics, redaction, locking and generated CLI converters/metadata. Do not invent a second runtime loader or lock-propagation mode. |
| Architecture, API workflow and concurrency documentation | Cross-link ADRs 0001/0002/0015: independent selected templates, nested fallback and scoped operation/resource ownership. Update transition/lock diagrams and review the shared infographic; document whether its resource-layer overview changes. |

Each practical scenario needs a concrete input, selected route, effective headers/envelope where useful, result/log output and a boundary
that prevents a misleading inference. Cover the actual-message nature and delivery limits of a maintainer BCC, not just its setter.
Follow the [field-guide authoring contract](../../simplejavamail.org/src/guides/README.md), including independently readable scenarios,
matched directory headings and synchronized downloadable examples. Perform a site-wide incoming-reference scan and the normal website
checks, clean build and internal-link verification after implementation; documentation-only checks here do not stand in for those runs.

**Case studies and Engineering Journal work are excluded.** The maintainer handles those in a separate session. Do not edit them or
make the feature's documentation dependent on changing their narratives, and preserve all concurrent website work.
