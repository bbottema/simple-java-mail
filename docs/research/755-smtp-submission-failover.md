# SMTP submission failover research (#755)

- Research date: 2026-10-07.
- Tracking: [#755](https://github.com/bbottema/simple-java-mail/issues/755), milestone **10.0.0**.
- Status: Research supporting accepted ADR 0029. Implementation is planned, not implemented; common-outage classification is a required verification gate.
- SJM baseline: `codex/10.0.0` at `c365b6dc0e6dca655af5ebcf57b66c4641b9de1c`.
- Supporting baselines: smtp-connection-pool 4.3.0 at `0e12e3925ba61af98034377429569ae877041cf7`,
  clustered-object-pool 4.2.0 at `cd55bb3572591c5426b66daa2a95c301ca36d620`, and generic-object-pool 2.5.1 at
  `c9b0cb7a0a1dad637dfdf50838a951e843b26a42`.
- Review document: [accepted ADR 0029](../adr/0029-smtp-submission-failover.md).

## Why this is worth doing

The [original comparison](simple-java-mail-world-class-smtp-research.pdf), especially pages 3–5 and 11–12, identifies transport failover
as a useful framework capability, but rejects blind automatic retries. The [progress companion](simple-java-mail-world-class-smtp-progress-report.md)
records the receipt, cancellation, resource, rate-limit and configuration work now available to support a safer design.

The user-facing setup is small: configure an authorized failover SMTP service, then keep using ordinary `mailer.sync().sendMail(email)`
or `mailer.async().sendMail(email)`. If the primary cannot provide a usable connection, the library can find an eligible failover destination without
application try/catch orchestration. Pooling must not be required merely to configure a failover destination.

This controls submission to a relay, not the accepted relay's queue, downstream delivery or Exchange's server-side availability.
It must not be marketed as exactly-once delivery or a replacement for server-side HA.

## Accepted maintainer decisions

The maintainer accepted connection-only failover on 2026-10-07. The final decision preserves ordinary pool waiting and leaves capacity
expansion to pooling, clustering and load balancing; it supersedes the earlier capacity-spillover proposal.
The maintainer also selected independent opt-ins for authentication failure and certificate-verification failure, both disabled by default.
The expired-primary-certificate scenario must be documented as a use case: a permitted, independently verified failover can keep submission
working while the original failure remains visible. ADR 0029 is now accepted with a practical common-outage coverage obligation and
Mailer-specific inspection. Concrete FailoverPolicy methods and new property spelling belong in the implementation plan.

| Decision | Accepted behavior | Boundary or cost |
| --- | --- | --- |
| Initial scope — accepted | Connection/acquisition failover, before provider submission invocation. | Broader retry/replay deferred to [#756](https://github.com/bbottema/simple-java-mail/issues/756). |
| Busy primary — accepted | Preserve ordinary pool, executor and rate-limit waiting and timeouts. | No capacity-spillover option in failover; load expansion belongs to pooling, clustering and load balancing. |
| Authentication and certificate verification — independent opt-ins accepted | Each failure stops by default; enabling its own option permits an eligible, independently checked failover. | Explicit fallback can still change performance; retain the original failure, selected destination and all applicable checks. |
| Routing and timing shape — accepted | Try primary A once, then eligible B immediately; A's per-destination backoff does not throttle usable B. Later emails prefer A again after usable setup there. | Additional primary setup retries are configurable; concrete periods and exact API remain pending. No message replay or background recovery probe. |
| Primary recheck timing — accepted | Configurable fixed interval by default; optionally use increasing backoff capped at a configured maximum. | Exact durations and parameters remain pending; do not silently delay B with A's backoff. |
| Recovery during a simple batch — accepted | Finish the current email on B, then move the untouched remainder to usable A at an email boundary. | Preserve the same lazy iterator, operation, completion and deadline. Actual cancellation stops work; explicit open-connection scopes remain pinned. |
| Connection-history ownership — accepted | Mailers from one factory share failure observations and coordinate primary rechecks for the same configured destination. | Separate factories remain independent; each invoking Mailer's permissions still apply. No hostname-based merging or global registry. |
| Operational visibility — accepted | Automatic ERROR for genuine relay setup incidents, INFO for meaningful failover/recovery transitions and DEBUG for repeated rechecks. | Suppress duplicate notices across participating Mailers; visibility must not depend on registering an observer. A successful failover does not repair the primary. |
| Structured history placement — accepted | Optional ordered connection-attempt history in the existing terminal observer diagnostics. | No per-server failure handler or send-result wrapper. Intermediate setup errors do not make a successful failover send fail; exact history signatures remain pending. |
| Failover/policy entry point — accepted | Configure `withFailoverMailer(failoverMailer, policy)` on the primary's normal fluent builder. Replacement or clearing affects the pair. | No third resilient Mailer, standalone policy setter or staged intermediate interface. Named definitions supply the property-backed equivalent; final policy fields and property spelling remain pending. |
| Independent message templates — accepted | Resolve the original Email with the actual selected Mailer's own complete ordinary defaults/overrides. Configure shared ordinary behavior on both Mailers or on the supplied Email. | No template hierarchy, merged precedence or relay-field whitelist. A's ordinary BCC/headers do not carry into B; mandatory message locks do. A B-only maintainers BCC remains valid. |
| Nested failover Mailers and operation ownership — accepted | Honor B's configured fallback to C under B's policy, within A's one logical operation, initiating executor/observer, cancellation and deadline. | Do not flatten B into an endpoint, ignore its failover configuration or create independent public sends. Existing cluster ownership is a precedent for one owner per shared concern, not all settings. |
| Failover lifetime — accepted and refined | An application-supplied failover Mailer remains borrowed; SJM-created private failover Mailers belong to the primary and close after its admitted work drains. | Lifecycle guards cover all admitted fallback use. Do not close supplied/shared Mailers through a primary, leak private descendants or add a factory close contract. |
| Cross-factory failover Mailers — accepted | Reuse a failover Mailer from another factory, retaining its captured snapshot, resources and runtime ownership. | Do not reload or merge factories, group names or histories. Apply the separately accepted scoped locks to the participating route. |
| Scoped locks and approved failover Mailers — accepted | Preserve applicable mandatory message requirements through fallback; keep connection locks with each selected destination and execution locks with the initiator. Make captured named failover relationships centrally configurable and lockable. | More validation in SJM and compatible route configuration from its owner. A matching name alone cannot authorize a replacement. No public propagation mode or silent lock bypass. |
| Property configuration — accepted | Named independent Mailer definitions resolved by the existing loader, with each policy alongside its failover reference. Java, Spring and CLI use the same captured configuration. | SJM constructs and closes private failover Mailers. No implicit bean lookup, global object registry, ordinary-template inheritance or new public SMTP-profile builder. Final new key spelling remains illustrative. |
| Practical failure classification — accepted | Ordinary supported-stack outages must trigger eligible failover Mailer; preserve connection-stage and cause evidence. | Stop for genuinely opaque causes, not for routine outages hidden by wrappers. No catch-all permission or public failure-classification enum. |
| Inspection — accepted | Getters, validation, rehearsal and connection diagnostics describe the called Mailer; actual-send diagnostics describe the route used. | No implicit all-routes preparation. B-only rehearsal does not simulate A-to-B inherited message locks. |

The initial scope prohibits automatic replay after provider invocation. Optional failure handling cannot bypass locks,
weaken TLS, remove REQUIRETLS/DSN requirements, rewrite addresses or alter protected content.

Primary-connection retry, backoff and reconsideration belong to the policy, evaluated against minimal private attempt history. The maintainer
does not want a separate server-health/state-orchestration subsystem. This does not permit post-invocation Email replay; that remains #756.

The maintainer confirmed the A-to-B-to-A shape: B continues with its own sending limits and burst/spreading behavior while A is retried
only when its policy timing permits. A permitted failed recheck leaves B eligible without imposing A's backoff; usable A setup returns
later emails to A under A's limits. A simple batch may move its untouched remainder between emails; the email currently being submitted
and explicitly retained open-connection scopes stay on B. Do not reset or transfer sending-limit counters on route changes, and preserve
any explicitly shared allowance. When A is usable again, its normal pool/rate waiting applies; a busy pool does not retain eligibility for B.

All architectural decisions are now accepted in ADR 0029, including the paired `withFailoverMailer(failoverMailer, policy)` shape,
named definitions, supplied/private lifetime, nesting and scoped locks. Concrete policy options, property names, attempt-history
signatures and timing/count values follow in the implementation plan. FailoverPolicy is immutable supplied configuration, not a
callback or public runtime coordinator. No additional policy-builder signature, enum or finalized property key is implied here.

Normal pool waiting is unchanged. Reliable handling of ordinary connection outages is a required feature gate, not optional provider
coverage that can routinely degrade into unknown-failure stopping. Record actual connection-stage/cause evidence on direct, pooled and
clustered paths. Inspection, validation, rehearsal and connection diagnostics remain specific to the Mailer called; only actual-send
diagnostics report the selected route. See the [final boundary decisions](../adr/0029-smtp-submission-failover.md#final-boundary-decisions-2026-10-07).

The final shape supersedes the earlier third-Mailer/merged-template proposal. A's operation delegates routing to B without inheriting A's
ordinary templates or disabling B's configured fallback. Each relevant policy controls its own alternatives. The shared operation does not
reset its cancellation/deadline or multiply independent retry loops. No failover array is required to express A-to-B-to-C.

Borrowing, non-cascading close for supplied instances and cross-factory participation are accepted. Preserve the actual failover's captured settings and runtime
context; equal group names, snapshots or endpoint values do not merge independent factories. Explicitly reusing one configured Mailer
can reuse its existing destination context. The exact lifecycle mechanism and destination identity remain implementation obligations.
The maintainer accepted scoped locks with centrally configurable, lockable failover relationships on 2026-10-07. The
[ADR 0025 addendum](../adr/0025-factory-scoped-locked-configuration.md#accepted-addendum-delegation-to-approved-failover-mailers-2026-10-07)
records the lock-contract extension; [ADR 0029's decision matrix](../adr/0029-smtp-submission-failover.md#decision-matrix) compares the alternatives.
This is accepted design, not a claim about current runtime behavior. Keep mandatory message locks from the initiating and participating
failover Mailers, reject conflicting values, and use each approved destination's own connection settings and locks. Company-wide TLS intent
must be configured and locked on every eligible route; do not infer security-strategy equivalence. Known composition conflicts should
identify the properties and the need for a compatible centrally supplied failover Mailer before an outage exposes them.

The maintainer subsequently accepted named configuration definitions and refined lifetime on 2026-10-07. Use the existing loader and
snapshot to capture complete independent definitions and their permitted references. Ordinary primary settings are not fallback defaults
for those definitions. Names are local configuration references, not Spring beans, hostnames or a global object registry; resolve them
once and reject missing references, cycles and knowable lock conflicts during construction. A locked reference covers its resolved
permitted relationship, including nested alternatives, rather than accepting any object claiming the same label.

SJM owns only the private failover Mailers it constructs for a primary. Normal primary close, Spring destruction and CLI cleanup drain work and
close those private descendants; application-supplied instances remain borrowed. Named definitions do not become singleton live Mailers.
Keep factory/destination history and explicit allowance sharing under their established owners so repeated private construction neither
resets recovery nor multiplies allowance. Independent factories remain independent. Capture the construction/rollback and mixed-ownership
tests in the implementation plan. See the [ADR 0003 extension](../adr/0003-immutable-configuration-snapshots.md#accepted-extension-named-failover-configuration-2026-10-07)
and [ADR 0029's configuration and lifecycle sections](../adr/0029-smtp-submission-failover.md#accepted-named-mailer-configuration).

The failover website walkthrough must include a concrete observer-based application retry recipe using existing receipt guidance,
with illustrative output. The application retains the job/Email and schedules eligible work outside the callback; it must not infer safety
from failed completion, missing receipts or reused Message-IDs. Show safe recipient-only retries while excluding accepted or possibly
accepted recipient occurrences, accepted mail followed by cleanup failure, cancellation and logging-only mode. A built-in retry policy and possible backoff
are separate research in #756, with no release target assigned yet.

The website must also show the primary certificate-expiry use case from ADR 0029. Certificate-failure fallback must not imply accepting
the expired certificate, disabling checks or enabling authentication-failure fallback. If every eligible failover Mailer also fails, the operation
still fails. A successful failover send needs visible incident facts for operations, not a claim that certificate renewal or final delivery
has been solved.

The maintainer also required complete documentation of failover during implementation: extend the canonical defaults/overrides
section, the cross-cutting Use cases page, the email workload field guide and its directory/downloadable examples, and all relevant
sending, pooling, security, result, configuration, Spring/CLI and architecture guidance. Case studies and Engineering Journal work remain
maintainer-owned and excluded. The specific coverage map is recorded in ADR 0029's implementation obligations; no website edits were
made during this ADR pass. Include one canonical ownership matrix comparing sends initiated on A through B or nested C with direct
B sends; cover execution/observation, cancellation/deadlines, each fallback policy, selected templates/security/rates and shared cluster
pool settings, plus scoped locks and approved relationships. Cross-link the matrix rather than duplicating inconsistent versions.

## What the existing code actually does

| Owner | Current responsibility | Consequence for failover |
| --- | --- | --- |
| `MailerImpl` | Prepares the Email, selects a destination, verifies locks, obtains sending allowance, then constructs the send closure. | Reselection must happen above the destination-specific validation and reservation, not hidden inside a later claim. |
| `EmailGovernanceImpl` | Resolves the invoking Mailer's templates; a different selected cluster member currently adds locks without reapplying its ordinary templates. | Selected-only governance during failover is planned behavior, not automatically supplied by today's cluster path. |
| `BatchTransportEngine` | Delegates selection and acquisition to the upstream SMTP pool. | Reuse it for lease ownership; do not add another physical pool or independent retry loop. |
| `SmtpTransportSelection` | Identifies one registration and later claims only from it. | No fallback is currently performed. Retirement cannot silently redirect a stale selection to a replacement. |
| `ResourceClusters` | Runs the chosen load balancer, then claims from that selected pool. | Cluster membership/load balancing is not acquisition failover. |
| `TransportRunner` | Connects or borrows, converts with the actual Session, invokes the adapter, captures receipts and cleans up. | Candidate failures need to be handled before freezing the logical send's final failure. |
| Provider adapter | Owns submission interpretation, not connection acquisition or disposal. | Pool libraries cannot decide that a message is safe to resend. |

Source anchors:

- [Mailer destination selection and sending](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java).
- [Batch selection/claim bridge](../../modules/batch-module/src/main/java/org/simplejavamail/internal/batchsupport/BatchTransportEngine.java).
- [Pinned SMTP selection](https://github.com/simple-java-mail/smtp-connection-pool/blob/0e12e3925ba61af98034377429569ae877041cf7/smtp-connection-pool/src/main/java/org/simplejavamail/smtpconnectionpool/SmtpTransportSelection.java).
- [Cluster selection and claim](https://github.com/bbottema/clustered-object-pool/blob/cd55bb3572591c5426b66daa2a95c301ca36d620/src/main/java/org/bbottema/clusteredobjectpool/core/ResourceClusters.java).
- [Send/cleanup boundary](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/util/TransportRunner.java).
- [Selected Session and locks](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/SessionBasedEmailToMimeMessageConverter.java).
- [Current simple-batch loop](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/SendMailsInSimpleBatchClosure.java).
- [Public cancellation contract](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailSend.java).

### Executable acquisition evidence

Before the desktop restart, the research worker ran these existing clustered-object-pool suites on Java 8:

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk1.8.0_152'
mvn -o compiler:compile compiler:testCompile surefire:test "-Dtest=ResourcePoolSelectionTest,ControlledClusterClaimTest"
```

Result: **31 tests, zero failures/errors/skips**. The source and retained Surefire reports were rechecked during this continuation;
this is the earlier run, not a claim that another reactor verification ran after restart.

A two-pool executable probe also held the primary's sole resource, and separately injected an allocator failure. Both left the failover
healthy, but neither claim automatically used it. The following summary normalizes labels to the current failover terminology;
it is not a verbatim transcript:

```text
saturation: selected claim=null, automatic failover allocations=0, explicit failover=healthy
allocator failure: primary allocations=1, automatic failover allocations=0, next caller claim=failover
```

The probe and its output were recovered from the preceding research run. It used a resource already held to force saturation,
not a performance threshold. The retained temporary source was
`C:/Users/Benny Bottema/AppData/Local/Temp/sjm-755-pool-probe-c20e43639f2f4baab3a146c44aff07a5/PoolFailoverProbe.java`;
that path is recovery evidence, not a durable repository test dependency. Preserve the reproducer as a normal test when upstream work is planned.

Existing selection tests additionally cover retirement/re-registration: an old selection does not acquire from the replacement
registration. New candidate traversal must preserve this fencing.

### Important gaps in classification

The accepted design requires working recovery for DNS failures, refused/unreachable connections, setup timeouts, setup EOF/reset and
reliably identified temporary service-unavailable replies, including failed pooled connection creation/replacement. Losing their evidence
inside a wrapper is an implementation gap to fix, not an acceptable reason to finish with failover disabled for ordinary outages.
Characterize those paths and verify them deterministically; unknown third-party failures still stop without guessing or overriding
the independent authentication/certificate permissions. This requirement does not claim those classifications are already implemented.

Direct connection failures currently pass through `MailTransportResult.failed(failure, null)` and can produce an `UNKNOWN` receipt.
That does not mean the connection phase might already have submitted this email: the orchestration knows whether provider invocation
began. Conversely, receipt absence is never proof of no acceptance.

Pool timeout, missing/retired registrations and allocator errors currently use general exception types or wrappers. Do not infer
availability from an `IllegalStateException`, any nested `MessagingException`, exception prose or a lone status code. Separate
acquisition evidence and typed failure categories are implementation obligations, not existing capability claims.

`MailSendControl.translateFailure()` freezes failure/stop arbitration. A recoverable candidate failure must not prematurely become
the final caller-facing failure, masking a later stop or misattributing the terminal diagnostic. This rules out simply catching the
existing complete send operation and recursively calling it again.

## Primary-source comparison

This is source review, not cross-library fault testing. Versions/commits matter more than feature labels.

| Implementation | Verified behavior | Lesson for SJM |
| --- | --- | --- |
| Symfony Mailer 8.1, `8783380ecdafa23d36fc90c5873fd44b635c6e10` | Its shared round-robin implementation catches `TransportExceptionInterface`, attempts another transport and records a cooldown. Failover favors the current usable transport. | Convenient ordered routing and cooldown are useful; the generic catch does not demonstrate an SMTP acceptance-safety gate. |
| PHPMailer, `55e2a9434f041db55d047cbc52c3000ae597a97f` | `smtpConnect()` traverses configured hosts during connection setup, including setup exceptions. `smtpSend()` does not use that loop to retry an already attempted DATA submission. | Connection fallback is independently useful without implementing general message replay. |
| Nodemailer, `645e97c8f0586404d9fb861c9527be7572c2c832` | The pool requeues a pending entry on unexpected connection close, subject to `maxRequeues`; this source uses a default of five and backoff. | Bounded recovery is valuable, but the pool close handler alone is not an acceptance-aware resend contract. |

References: [Symfony routing source](https://github.com/symfony/mailer/blob/8783380ecdafa23d36fc90c5873fd44b635c6e10/Transport/RoundRobinTransport.php),
[Symfony failover source](https://github.com/symfony/mailer/blob/8783380ecdafa23d36fc90c5873fd44b635c6e10/Transport/FailoverTransport.php),
[official Symfony guide](https://symfony.com/doc/current/mailer.html#high-availability),
[PHPMailer source](https://github.com/PHPMailer/PHPMailer/blob/55e2a9434f041db55d047cbc52c3000ae597a97f/src/PHPMailer.php),
[Nodemailer pool source](https://github.com/nodemailer/nodemailer/blob/645e97c8f0586404d9fb861c9527be7572c2c832/src/smtp-pool/index.ts),
and [official Nodemailer guide](https://nodemailer.com/smtp/pooled).

The Nodemailer guide retrieved on this date describes an unlimited default, while the reviewed source defaults to five.
Do not report either as a universal behavior across releases, and do not infer exactly-once safety from a retry bound.

## Failure and acceptance matrix

These record the accepted connection-only policy; the broader-recovery column remains research, not an accepted feature or a promise from today's public API. “Connection-only” stops before provider invocation;
“broader recovery” requires trustworthy attempt facts and repeatable content as well as authorization to change relays.

| Failure boundary | What is known | Connection-only recommendation | Broader recovery candidate |
| --- | --- | --- | --- |
| DNS/connect refusal or connection timeout, before submission | This email has not been submitted. | Next eligible destination. | Same. |
| Greeting/setup connection loss or temporary service-unavailable reply | No provider submission invocation yet. | Next eligible destination when reliably classified. | Same. |
| Authentication or certificate/TLS verification failure | No submission, but not an ordinary availability diagnosis. | Stop unless the matching independent opt-in allows an eligible, fully checked failover. Cached history must preserve that policy distinction. | Connection-only permissions do not authorize post-invocation replay. |
| Lock conflict, bad local configuration, invalid email | No useful unchanged retry. | Stop. | Stop; no requirement stripping. |
| Full pool | Local capacity pressure, not proven outage. | Preserve normal waiting/timeouts; do not trigger failover. | Capacity expansion belongs to pooling/cluster management, not retry policy. |
| Configured rate wait | Deliberate sending-limit backpressure, not proven outage. | Do not use it as an availability failure or silently bypass the configured limit. | Same. |
| Registration retired between selection and claim | No borrowed resource; selection is stale. | Bounded reselection of an eligible registration; preserve identity fencing. | Same. |
| Missing capability or local SIZE rejection in provider preflight | Usually known unsent, but provider invocation began. | Stop. | Destination-aware capability handling needs a separately agreed scope; not a generic retryable exception. |
| Temporary MAIL/RCPT/DATA-start rejection | Potentially known unsent, with command/recipient evidence. | Stop and retain receipt. | Only where complete evidence permits the unchanged whole envelope; no unsafe recipient subset inference. |
| Permanent message/recipient rejection | No appropriate automatic unchanged retry established. | Stop. | Stop by default; changing relay is not a workaround for rejection policy. |
| Transfer interrupted before any possible commit | Managed Angus may establish no acceptance; opaque providers may not. | Stop. | Requires explicit reliable evidence and replayable content. |
| Final DATA reply is a known temporary rejection | A conforming server did not accept the transaction. | Stop. | Possible under broader scope, with whole-envelope retry guidance and repeatable content. |
| Final commit/reply is uncertain | Acceptance is possible. | Stop. | Stop; do not create duplicates. |
| Partial final acceptance | Some recipients accepted; whole-envelope replay duplicates them. | Stop. | Stop; recipient splitting is deferred. |
| Accepted submission followed by cleanup failure | Acceptance remains established. | Stop; preserve acceptance facts. | Stop; do not resend. |
| Cancellation, total deadline, shutdown or interruption | Operation must stop; socket closure is not rollback. | No next destination. | Same. |

[RFC 5321 §4.2.5](https://www.rfc-editor.org/rfc/rfc5321.html#section-4.2.5) separates successful completion from negative completion.
Its [DATA-termination timeout guidance](https://www.rfc-editor.org/rfc/rfc5321.html#section-4.5.3.2.6) explains the duplicate risk of
losing the final reply. The classifications above additionally use local orchestration and
[managed Angus commit observations](../../modules/angus-mail-provider-module/src/main/java/org/simplejavamail/internal/mailprovider/angus/ManagedAngusTransport.java);
they are not inferred from RFC reply codes alone. [Jakarta Mail's send contract](https://jakarta.ee/specifications/mail/2.1/apidocs/jakarta.mail/jakarta/mail/transport)
does not provide a universal protocol-progress observation API.

## Accepted architecture, not implemented

### One routing coordinator, not nested retry loops

Configure a primary Mailer A with failover B and a policy; B may itself have C configured with its own policy. A or B can use a registered
cluster, such as `{A1, A2}` or `{B1, B2}`. An individual server is a one-candidate group. Preserve configured cluster load balancing.
Honor B's own fallback instead of flattening it into a bare endpoint or ignoring C. A's policy governs A-to-B and B's governs B-to-C.
SJM coordinates the logical send; upstream libraries provide candidate selection and exclusive acquisition without silently choosing
another server after SJM has checked its locks and reserved its allowance.

Candidate traversal needs an exclusion/visited mechanism and registration identity. Repeatedly cycling today's API until a different
Session appears can loop, distort a sticky strategy or confuse a retired registration with a new one. Do not duplicate the load balancer
or expose SMTP acceptance logic in a generic resource pool.

Each candidate follows selection, compatibility/lock checking, allowance reservation, acquisition, and then submission.
On an eligible acquisition failure, acknowledge cleanup and release unused allowance before moving on. Keep one logical operation,
deadline, cancellation control and finite candidate budget. A per-destination cap cannot restart the total deadline; without a smaller
cap the first slow destination can consume the whole available time. Do not promise hard completion bounds for non-cooperative code.

Reject cyclic failover relationships, and distinguish them from explicitly bounded retries. The initiating Mailer owns scheduling and
terminal observation; nested Mailers participate in routing/lifecycle without creating new async jobs or repeated terminal outcomes.
Direct calls on B instead use B as the initiator. The first-registration rule for shared pool settings in
[ADR 0015](../adr/0015-execution-views-and-transport-pooling.md) is a precedent for scoped ownership, not a claim that cluster registration
already implements this failover. [BatchTransportEngine](../../modules/batch-module/src/main/java/org/simplejavamail/internal/batchsupport/BatchTransportEngine.java)
retains the first cluster settings; [BatchSupport](../../modules/batch-module/src/main/java/org/simplejavamail/internal/batchsupport/BatchSupport.java)
warns when later settings differ. It does not make the first registrant owner of every participating Mailer's configuration.

### Actual destination ownership

The maintainer superseded both transport-only borrowing and the proposed merged-template hierarchy. Use only the actual selected
Mailer's full Email defaults/overrides against the original Email. A's headers, BCC, signing settings and other ordinary template values
do not carry into B; B's do not carry into C or back into A. Configure common behavior on each Mailer that needs it, optionally sharing
the same Email templates. Do not restrict a selected template to fields considered relay-related.

For a single value, retain selected override, supplied Email, selected default. Headers preserve key-wise replacement and collections
remain additive within that existing governance. Preserve suppression, explicit false, template replacement/clearing and exact-Email
bypass. Never use A's effective Email as B's supplied input or add another precedence/provenance layer across Mailers.

Include the selected Mailer's BCC in its actual envelope, recipient allowance and receipt facts. Discard failed-candidate reservations
before moving on. Preserve deliberate recipient occurrences and apply only the selected snapshot/template once. Reserve and charge
against the actual selected registration's sending rules, not against the invoking Mailer by mistake.

Today's code does not provide this selected-only route behavior: `EmailGovernanceImpl.applyLocksToPreparedEmail(...)` leaves the selected
member's ordinary templates unapplied. The Configuration page's existing server-specific-template cluster example therefore needs
reconciliation during implementation. Do not quietly broaden ordinary cluster behavior or claim the proposed resolver already exists.

Credentials, OAuth refresh, TLS/proxy handling, provider settings and content compatibility must use the selected route's configuration.
Existing `SendMailClosure` receives the invoking Mailer's proxy bridge; per-destination authenticated-proxy startup therefore needs
explicit characterization before promising heterogeneous proxy routes. This is an implementation boundary identified by source review,
not a newly reproduced existing proxy defect.

Apply the accepted originating/participating message locks independently of ordinary selected-Mailer templates. Connection locks remain
with each approved destination and operational locks with their existing owners. Keep captured named failover relationships centrally
configurable and lockable; final property spelling remains an implementation detail. Locks are not a minimum-security lattice where supposedly
stronger configuration can automatically replace a fixed value. A detected applicable conflict is not a connection outage.

### Policy timing, scopes and results

Derive primary reconsideration from policy rules and actual connection-attempt facts, not a separate healthy/unhealthy/probing model.
Keep private classified failures, bounded counts and monotonic timing information; do not expose a health indicator, introduce a background
probe loop or build another circuit-breaker framework. The resulting behavior is an implicit state machine; the policy is immutable and
runtime bookkeeping remains private.

The maintainer accepted factory-local sharing of connection-failure observations and primary recheck coordination for the same configured
destination. The factory owns this runtime bookkeeping, not the immutable snapshot or policy. Separate factories remain independent even
with equal snapshots; distinct destination configurations must not be merged merely by hostname. The final route API must establish their
identity. Each Mailer retains operation/resource ownership and applies its own policy permissions to shared facts, including independently
enabled authentication/certificate fallback. Closing one Mailer must not reset another participant's relevant observations.

Any suppression must require permission for the recorded failure category. Deliberate rate waiting and local saturation do not establish
relay health; authentication/certificate facts must retain their independent opt-in distinction. Brief synchronization can reserve the next
eligible attempt without holding a monitor during waits, DNS, claims, network work or callbacks. Characterize interaction with existing
pool maintenance, preserve usable connections and avoid simultaneous reconsideration by every sender.

The maintainer accepted fixed-interval primary reconsideration by default, with optional increasing capped backoff. Concrete periods/counts,
within-send versus later-send connection retries still require decisions. Every repeated connection attempt shares
the same cancellation/deadline budget; no process-global registry, additional executor or automatic submission replay follows.

Ordinary single sends are the primary scope. Simple batches and open-connection scopes may acquire through fallback initially. With failover
enabled, a simple batch finishes its current email on B, then can move the untouched remainder to A after policy-permitted usable setup.
Keep the same iterator, operation, completion, cancellation control and deadline; the first email failure still stops the batch. Preserve
once-only per-email outcomes and no callbacks for untouched emails. Without failover configured, today's one-connection behavior is unchanged.

This is normal route replacement using cancellation-aware acquisition and resource retirement, not public cancellation followed by restart.
Today's cancellation stops the entire batch and may abort the current submission; it does not make an arbitrary iterable resumable or prove
that email was unsent. A recovery check can consume time at an email boundary, within the existing remaining deadline. Subsequent emails
use the new selected Session's settings and allowance history; retire old abort registrations so stop signals cannot reach retired resources.

Explicit `withOpenConnection` scopes stay pinned, and arbitrary application callbacks are never rerun. Probes/tests keep their explicitly
targeted diagnostic meaning. Reconcile the opt-in simple-batch exception with ADR 0015 and the public contract during implementation.

An opaque `CustomMailer` owns transport execution; do not automatically invoke it again or pretend a relay policy controls it.
Ordinary third-party providers can participate in connection-only fallback at a known pre-invocation boundary. Physical cancellation
support remains a separate capability, required consistently when a total deadline is configured.

Keep one terminal observer outcome and one completion for the logical send, after ordinary cleanup. Return the exact receipt from the
actual submission, preserving acceptance even if later cleanup fails. Candidate connection failures are history, not manufactured SMTP
receipts. Final exhaustion must retain useful causes without implying SMTP rejection. The agreed home for bounded, ordered connection
history is the existing observer diagnostics; concrete signatures remain pending and the current diagnostics expose only one endpoint.
A failed A setup followed by a successful B send produces one successful outcome with an empty `getFailure()`, not an application failure
handler invocation. Distinguish current attempts from retained facts that explain why a destination was skipped.

The maintainer accepted automatic operational logging as the notification baseline, independent of observer registration. Report genuine
connection/authentication/certificate failures at ERROR, meaningful failover/recovery transitions at INFO, and repeated unsuccessful rechecks at DEBUG. A successful failover is a workaround, not a repaired primary. Suppress repeated incident and
route-transition notices across factory participants without adding a public health model or reporting loop. Emit meaningful changed
failures and return-to-primary only after usable setup there. Use bounded escaped labels/classified reasons without credentials, content,
full URLs or SMTP transcripts. Logging runs outside bookkeeping locks and leaves the operation's result semantics unchanged.

### Why broader replay is a separate commitment

Ordinary attachment DataSources are required to be stable/repeatable, but SJM does not freeze every composed message into one immutable
wire artifact. Rebuilding can change Message-ID, boundaries or signing/encryption output; a provider may also perform permitted MIME
conversion. Exact/protected sources already preserve bytes, but that does not settle all ordinary-source and destination differences.

A broader design must choose content materialization, Message-ID preservation, automatic versus fixed ENVID across actual submissions,
and per-attempt evidence without casually adding a spool or additional attachment reads. Safe-to-retry receipt guidance is necessary
but not sufficient: it does not authorize another destination or prove stream repeatability. These costs explain the connection-only
recommendation; they do not make broader recovery impossible.

## Implementation gates

1. Settle scope and failure categories, then route/configuration and result-history contracts in ADR 0029.
2. Characterize candidate traversal, retirement, allocator/connect categories, failed-acquisition cleanup, deadline exhaustion and
   cancellation in the supporting libraries. Plan compatible upstream additions; do not relabel generic pool failures by parsing prose.
3. Add one SJM route coordinator. Verify selected credentials, TLS, locks, quota ownership and proxy lifecycle, including no-pool sending.
4. Cover concurrent fallback, return-to-primary during a one-shot lazy batch, stale leases and selected-registration replacement. Observe
   no resource held while ordinary sending allowance waits, and no repeated callback or iterator opening on setup failure. Verify actual
   cancellation/deadline races during connection handoff, selected-server ownership and explicit open-connection pinning.
   Include shared recheck coordination among Mailers from one factory, separate-factory/configuration isolation, participant closure and
   strict policies observing facts recorded by permissive ones.
5. Prove the non-replay boundary with loopback failures before MAIL, during DATA, at final-reply loss, partial acceptance, acceptance
   followed by failed cleanup, unsupported providers and CustomMailer. Compare attachment reads and serialization counts.
6. Follow the [API expansion workflow](../API_EXPANSION_WORKFLOW.md): property/schema/locks, Spring metadata, CLI conversion/help,
   supplied-data configuration, classpath/JPMS, and honest diagnostics. Add migration guidance only for actual compatibility changes.
7. Update current website usage, release history and relevant concurrency maps during implementation, not as claims about this research.
   Show default operational logs, duplicate suppression and optional observer history, including successful failover use with an unresolved
   primary fault. Verify log levels with and without observers and distinguish actual current attempts from cached earlier failures.
8. Verify independent selected-Mailer governance, retaining established value/header/additive/suppression/replacement behavior and exact
   bypass. Test B-only additions, ordinary A-only settings absent on B, explicitly shared templates, A-to-B-to-A recovery and concurrent reuse.
   Verify actual envelope cost/receipt facts and unchanged content reads solely for routing. Cover nested A-to-B-to-C policies without
   ignoring B's failover Mailers, one initiating executor/observer, finite budgets and cycles. Implement borrowed lifetime for supplied instances and
   owned lifetime/rollback for privately constructed failover Mailers. Implement accepted named definitions, cross-factory participation and scoped
   locks; retain the accepted Mailer-specific inspection and preparation contract. Verify repeated construction retains allowance/history without sharing
   private transport resources. Cover mandatory archive/onward-TLS requirements across nested routes, conflicting locks, protected
   bytes, locked failover replacement/clearing and actionable errors for application developers who cannot change central properties.
9. Carry the ADR's documentation coverage map into the reviewed implementation plan. Extend existing use-case and field-guide scenarios,
   synchronized navigation/downloadable examples and the authoritative defaults/overrides explanation rather than adding a competing
   policy narrative. Explain that a maintainer BCC copies the business email, can affect recipient rejection and is not guaranteed alert delivery.
   Preserve unrelated work and exclude case studies/Journal changes.

No new reactor, live-email, benchmark or Docker-conformance run is claimed by this report. The missing failover behavior is deliberate
current architecture, not a released regression discovered by the probe. [#382](https://github.com/bbottema/simple-java-mail/issues/382)
and [#722](https://github.com/bbottema/simple-java-mail/issues/722) remain closed; distributed coordination and protocol-engine expansion
remain separate work.
