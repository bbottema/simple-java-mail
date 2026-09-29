# Jakarta Mail / Angus Capability Parity for Simple Java Mail 10.0

**Research snapshot:** 2026-09-09  
**Simple Java Mail baseline:** current `codex/10.0.0` working tree  
**Provider baseline:** Jakarta Mail API 2.1.5 and Eclipse Angus Mail 2.0.5  
**Scope:** outbound SMTP submission and the Jakarta Mail/MIME/session behavior that directly affects it. POP3, IMAP, hosted delivery services, mailbox processing, and direct-to-MX delivery are out of scope.

## Executive conclusion

Simple Java Mail should not pursue Angus *property parity*. That would turn a coherent mailing library into a second, less complete spelling of the provider's configuration table.

The holistic audit does, however, change the answer from “add a few high-value abstractions” to **build a deliberate four-layer surfacing model**. Some low-level capabilities express durable application intent and belong in provider-neutral API. Some are genuinely useful but Angus-specific and deserve typed convenience in the Angus provider module. Some should be consumed internally as safer defaults. The remainder should stay behind raw properties, an expert SPI, or an upstream contribution. Treating all four categories alike would either hide valuable provider power or pollute the core builder.

The better goal is **provider-mechanism parity at the level of user intent**:

- turn stable Angus mechanics into typed, provider-neutral policies;
- put each control at its correct lifetime: mailer, physical connection, or individual message;
- add observable outcomes where Angus otherwise exposes only properties or exception chains;
- retain a clearly labelled expert escape hatch for uncommon provider properties;
- contribute upstream when Angus lacks the seam required to implement a truthful contract.

This is already how the strongest recent Simple Java Mail work behaves. Angus's `reportSuccess` mode communicates recipient success through provider-specific exceptions. Simple Java Mail temporarily enables it, restores the transport state before a pooled lease is returned, and converts the result into a provider-neutral receipt containing per-recipient replies and retry guidance. The facade is more useful than the mechanism it wraps.

The highest-value finding in this comparison is unexpectedly operational rather than protocol-visible:

> **Simple Java Mail enables an SMTP write timeout by default. Angus implements that timeout with a scheduled executor and, unless an external executor is supplied, creates one executor per physical socket. A pooled mailer should provide and own a shared write-timeout scheduler.**

That is a concrete robustness and high-volume improvement hiding behind an Angus capability that Simple Java Mail technically permits through raw `Properties`, but does not safely or naturally expose. It belongs in the 10.0 foundation.

The strongest newly uncovered security hardening is similarly invisible at feature-list level. Angus defaults `mail.smtp.socketFactory.fallback` to `true`: if a configured custom socket factory fails, its socket acquisition path may continue with the default factory. When a caller selected a custom factory to supply client certificates, private trust anchors, or pinning, silently abandoning it violates the caller's security intent. Simple Java Mail should set fail-closed behavior whenever it installs a custom factory, without requiring another public toggle.[^34]

The next strongest candidates are a per-message partial-recipient policy, split connect/read/write timeouts, an explicit authentication-over-plaintext policy, ordered authentication-mechanism policy, clearer provider-property precedence, TLS protocol/cipher controls, native HTTP CONNECT proxy support, and a deliberate RFC 5322 address model. The latter includes named recipient groups, empty privacy groups, multiple authors, and the distinct `Sender` field; the initial provider-centric pass underweighted this Jakarta Mail capability family.

Several attractive ESMTP features are **not** ready to be presented as simple facade work. Full DSN needs per-recipient `ORCPT`; a complete capability report needs a provider snapshot seam; standards-compliant SMTPUTF8/8BITMIME needs stricter provider behavior; and CHUNKING still has an open Angus correctness fix while PIPELINING is not implemented. These should become upstream collaborations or tested provider extensions, not optimistic 10.0 checkboxes.

## Recommendation at a glance

| Priority | Capability to expose or own | Why it matters | Recommended horizon |
|---|---|---|---|
| P0 | Shared write-timeout scheduler | Prevents a scheduler/thread resource from multiplying with physical pooled connections | 10.0 |
| P0 | Independent connect, read, and write timeouts | These protect different failure phases and should not be forced to the same value | 10.0 |
| P0 | Per-message recipient acceptance policy | Angus already supports partial sending; Simple Java Mail already has the result model needed to make it safe and understandable | 10.0 |
| P0 | Explicit authenticated-plaintext policy | Removes ambiguity when opportunistic STARTTLS is combined with credentials | 10.0 decision, even if migration is staged |
| P0 | Defined typed-vs-provider-property precedence and object-valued escape hatch | Makes the Angus escape hatch dependable and allows executor/verifier objects without accidental string conversion | 10.0 |
| P0 | Fail-closed custom socket factories | Prevents an explicitly selected trust, pinning, or client-certificate path from silently falling back to provider defaults | 10.0 |
| P1 | Provider-scoped typed options lane | Makes useful Angus controls discoverable without freezing 61 provider properties into the portable core API | Decide the extension shape in 10.0 |
| P1 | Ordered authentication mechanism policy | Lets security-conscious deployments exclude legacy mechanisms without a bag of negative flags | 10.0 API shape or 10.x |
| P1 | General rotating credential provider | Extends the existing dynamic OAuth-token pattern to password rotation, secret managers, and short-lived credentials | 10.0 API shape or 10.x |
| P1 | Transport TLS material, including mutual TLS | Replaces hand-built socket factories with explicit client identity, trust anchors, and verification policy | 10.x; shape with TLS policy |
| P1 | TLS protocol and cipher-suite policy | Useful for enterprise baselines and controlled interoperability | 10.x; additive in 10.0 if small |
| P1 | Native HTTP CONNECT proxy | Angus supports it; Simple Java Mail currently models SOCKS only | 10.x |
| P1 | RFC 5322 groups and originator model | Jakarta Mail models groups, multiple `From` mailboxes, and `Sender`; SJM's normal model is one author plus flat mailbox recipients | Decide 10.0 API shape; implement in 10.x if additive |
| P1 | Structured submission failure phase and enhanced status | Turns Angus command/code exceptions and current string status into stable operational facts | 10.x; additive result-model work |
| P1 | Provider-neutral post-TLS capability facts | Unlocks truthful diagnostics and later extension policies | Freeze SPI shape in 10.0; deliver incrementally |
| P1 | Pool connection-health policy | Converts `NOOP`/`RSET` and strictness knobs into a lifecycle-aware pool contract | 10.x |
| P2 | REQUIRETLS and exact SIZE policy | Valuable modern ESMTP behaviors, but only with capability checks and exact outcome reporting | 10.x after capability seam |
| P2 | Reply identity and MIME composition policies | Makes Jakarta reply/serialization behavior explicit without exporting global compatibility switches | 10.x/watch |
| Upstream | Full DSN, strict SMTPUTF8/8BITMIME, complete capability snapshot, selected AUTH/TLS facts | Angus lacks a sufficient or fully verified public seam | Coordinate with Angus first |
| Upstream | Correct CHUNKING and future PIPELINING | Transport-state-machine work belongs in the provider | Angus first; expose later |

### Method

The comparison used all 61 documented `mail.smtp.*` rows in Angus 2.0.5, the separately documented `mail.smtp.chunksize` control, public `SMTPMessage`/`SMTPTransport` APIs, and relevant Jakarta Mail session/MIME behavior. The tagged source was then checked for precedence, state lifetime, wire-command construction, and source-only controls. Those mechanics were mapped against the current Simple Java Mail builders, session initialization, Angus adapter, message model, result types, tests, and accepted SMTP robustness plan. Protocol conclusions were checked against the relevant RFCs and IANA registry. “Not implemented” in this report means not found in the released Angus 2.0.5 surface/source inspected here; it is not a claim about later or unreleased code.[^35]

## 1. The architectural boundary

Jakarta Mail deliberately separates the general mail API from protocol providers. `Transport` defines the submission abstraction; Angus supplies the SMTP state machine, wire protocol, sockets, TLS, authentication, and provider-specific message and transport classes.[^1] Simple Java Mail sits above both. Its durable public API therefore should not depend on `org.eclipse.angus.mail.smtp.SMTPMessage` or `SMTPTransport`, whose own package documentation warns that provider-specific APIs are experimental.[^2]

That leads to five admission tests for bubbling a provider capability upward:

1. **Does it express a recognizable caller goal?** “Send to valid recipients if one address fails” does. “Set `mail.smtp.sendpartial`” does not.
2. **Can the contract survive a provider replacement or major Angus change?** A provider adapter may implement the contract differently, but core API types must remain meaningful.
3. **Is it scoped correctly?** Recipient policy and REQUIRETLS are per-message. Timeouts and proxy routes are mailer or connection policy. Process-global MIME switches should not masquerade as message options.
4. **Can Simple Java Mail report what actually happened?** A requested extension is not the same as an advertised extension, an emitted parameter, server acceptance, or delivery.
5. **Can it be tested at the wire and resource levels?** Configuration-only tests are insufficient for SMTP behavior.

Every Angus feature in this report is assigned one of four dispositions:

- **Type it:** provide a normal Simple Java Mail API.
- **Internalize it:** use the Angus capability to improve behavior without adding a public knob.
- **Keep it expert-only:** preserve access through explicitly provider-specific configuration or SPI.
- **Upstream first:** do not promise the feature until Angus exposes or fixes the necessary behavior.

## 2. Current baseline: Simple Java Mail already covers the obvious layer

The current 10.0 tree uses Jakarta Mail API 2.1.5 and Angus Mail 2.0.5.[^3] It already presents typed or higher-level support for:

- SMTP, implicit TLS, mandatory STARTTLS, opportunistic STARTTLS, and OAuth2-oriented transport strategies;
- server identity verification, trust configuration, custom SSL socket factories, local bind address and port, and the EHLO/HELO client hostname;
- SMTP host, port, credentials, envelope sender, DSN `NOTIFY` and `RET`, and content-transfer-encoding controls;
- SOCKS proxying, including an authenticated local bridge;
- a dynamic OAuth2 token provider resolved when a physical connection opens or reconnects;
- synchronous, asynchronous, batched, and pooled sending with bounded submission/backpressure controls;
- byte-sensitive handling for exact EML and cryptographically protected content;
- structured submission receipts with per-recipient SMTP replies and retry guidance;
- a raw Jakarta Mail property escape hatch.

This means most Angus properties are not “missing features.” Many are already represented at a better level. The real gaps cluster around resource ownership, advanced security policy, per-message transaction choices, diagnostics, and newer ESMTP negotiation.

## 3. The hidden 10.0 priority: write-timeout resource ownership

### What Angus provides

Angus separates connection, read, and write timeouts. Its write timeout is implemented by wrapping the socket and scheduling a task that closes the socket if a write exceeds the deadline. Angus accepts an external `ScheduledExecutorService` through `mail.smtp.executor.writetimeout`. Its documentation recommends `ScheduledThreadPoolExecutor.setRemoveOnCancelPolicy(true)` so cancelled timeout tasks do not accumulate until their original deadline.[^4]

When no executor is supplied, Angus 2.0.5 constructs a new single-thread scheduled executor for each `WriteTimeoutSocket`. Its source even calls out the possibility of sharing an executor across instances.[^5] Threads are created lazily, but a connection that has performed writes can retain its executor worker for that socket's lifetime.

### What Simple Java Mail currently does

Simple Java Mail has one `withSessionTimeout` value. During session initialization it writes that same value to Angus's connect, read, and write timeout properties. The default is 60 seconds, so built-in SMTP sessions enable Angus write-timeout machinery even when the caller never mentions it.[^6]

This has two subtle consequences:

1. A pool of physical SMTP connections can imply a corresponding collection of provider-owned schedulers and, after use, worker threads.
2. The existing raw-property escape hatch does not solve this cleanly. General properties are applied before Simple Java Mail rewrites the three timeout values, and `withProperty(String, Object)` calls `toString()`. Only `withProperties(Properties)` preserves an executor object.[^6]

This is not automatically a catastrophic leak: Angus shuts down an internally owned executor when its socket closes. It is nevertheless an avoidable resource multiplier, especially in the exact high-volume configurations where Simple Java Mail's connection pooling is valuable.

### Recommendation

For 10.0:

- add independent connect, read, and write timeout values while retaining the combined setter as convenience/migration API;
- have each mailer or pool own a shared write-timeout scheduler and pass it to every physical Angus connection;
- apply remove-on-cancel behavior for a library-owned `ScheduledThreadPoolExecutor`;
- shut the scheduler down with the owning mailer, after connections and accepted work have drained;
- allow a caller-owned executor only through an advanced, object-preserving API with explicit ownership semantics;
- record scheduler queue size, rejected scheduling, and actual write-timeout events in operational metrics without exposing message data;
- benchmark simultaneous stalled writes before choosing the scheduler's default parallelism.

This feature should be implemented as infrastructure, not advertised as “Angus executor configuration.” The user-facing promise is predictable timeout behavior and bounded supporting resources.

## 4. Per-message recipient acceptance policy

Angus supports `sendpartial` both as a session property and on `SMTPMessage`. When disabled, a rejected recipient prevents DATA from being sent to any recipients. When enabled, Angus sends the message to the accepted recipients and reports the rejected recipients through its failure model.[^7]

Simple Java Mail already proves that its receipt model can explain both outcomes: current tests vary the raw `mail.smtp.sendpartial` property, retain original recipient order, distinguish temporary and permanent RCPT failures, say whether the message entered DATA, and derive which addresses are safe to retry.[^8]

One Angus detail must shape the implementation: `SMTPMessage.setSendPartial(false)` is not a tri-state override. Angus consults the session property whenever the message flag is false. Simple Java Mail should therefore keep the session-level setting false/absent and use the per-message flag only for the opt-in policy, or obtain a true per-message override upstream. A conflicting raw `mail.smtp.sendpartial=true` must not silently defeat `REQUIRE_ALL_RECIPIENTS`.

That makes this nearly ideal facade material:

- the caller intent is clear;
- the Angus mechanism is stable and can be set per message;
- the structured result removes the ambiguity that makes partial sending dangerous in lower-level APIs;
- per-message placement prevents one choice from leaking to the next message on a pooled connection.

Recommended policy names are semantic, for example:

- `REQUIRE_ALL_RECIPIENTS` — preserve today's default; do not transmit the message if any RCPT is rejected;
- `SEND_TO_ACCEPTED_RECIPIENTS` — transmit to accepted recipients and return/throw a partially accepted receipt for the rest.

The documentation must state that SMTP is not transactional across mailboxes. “Require all” means “do not issue DATA after a known RCPT rejection”; it cannot retract a later delivery or control failures after the submission server accepts the message.

This belongs in 10.0 because it is per-email API shape, it reuses completed receipt work, and it eliminates a common reason to know Angus property names.

## 5. Authentication should become a policy, not a collection of flags

Angus exposes built-in LOGIN, PLAIN, DIGEST-MD5, NTLM, and XOAUTH2 authentication, an ordered `auth.mechanisms` list, per-mechanism disable properties, and a separate Java SASL path with mechanisms, authorization identity, realm, and canonical-hostname behavior.[^2] It also exposes NTLM-specific compatibility flags.

Simple Java Mail currently makes username/password submission and XOAUTH2 easy, including dynamic OAuth2 token refresh. What remains missing is deliberate mechanism and transport policy.

### What is worth bubbling up

An `AuthenticationPolicy` should be able to express:

- no authentication, password/secret authentication, token authentication, or delegated/SASL authentication;
- an ordered allow-list of acceptable mechanisms;
- whether authentication is forbidden until a verified TLS channel exists;
- an optional authorization identity for delegated submission;
- what happens when the server advertises none of the allowed mechanisms.

The default policy should be short and opinionated. Legacy mechanisms such as DIGEST-MD5 and NTLM should not each gain a prominent top-level builder method. They can remain in an advanced mechanism list or provider configuration for deployments that genuinely require them.

### The 10.0 security decision

Simple Java Mail's `SMTP` strategy currently enables STARTTLS but permits plaintext fallback, while `SMTP_TLS` and `SMTP_OAUTH2` require STARTTLS.[^9] If credentials are configured with the opportunistic strategy and the server does not upgrade, authentication policy becomes security-critical.

The major release should make the rule explicit. The strongest default is: **never send credentials unless the connection is encrypted and the server identity was verified**. Compatibility modes can remain available, but should require a visibly insecure opt-in rather than emerge from the interaction of host credentials and an opportunistic transport strategy. RFC 4954 also requires the AUTH capability list to be reconsidered after STARTTLS, because the advertised mechanisms may change with the channel's security state.[^10]

### What cannot yet be promised

Angus does not expose a clean provider-neutral result saying which authentication mechanism was selected. A future connection-facts model should include the selected mechanism only after the provider supplies a stable observation seam. Until then, Simple Java Mail can report the configured allow-list and authentication success/failure, but should not infer the negotiated mechanism from debug output.

## 6. TLS policy and TLS evidence

Angus exposes STARTTLS enabled/required settings, implicit SSL, hostname verification, trusted hosts, custom hostname verifiers, socket factories, enabled protocols, and enabled cipher suites.[^2] Simple Java Mail already covers the high-value basics: explicit transport strategies, certificate trust, identity verification, and custom socket factories.

Two Angus capabilities are useful additions:

- **enabled TLS protocols**, for organizations enforcing a runtime-specific baseline or interoperating with a constrained relay;
- **enabled cipher suites**, as an expert-level allow-list where policy demands it.

These should be described as constraints on JSSE, not as evergreen “secure cipher” presets. Java runtime defaults change faster and more safely than a mailing library release. Simple Java Mail may validate obviously empty or unsupported policies early, but it should avoid freezing a security baseline into an enum that becomes stale.

A custom `HostnameVerifier` is technically possible through Angus, but it is easy to misuse and object-valued. It belongs in an advanced TLS customization API or provider-properties path, with explicit warnings and secret-safe diagnostics, rather than beside normal hostname verification.

The larger missing feature is **TLS evidence**: upgraded or implicit TLS, negotiated protocol, cipher suite, peer identity, certificate chain summary, and verification result. Angus's public `SMTPTransport.isSSL()` can establish only that the current socket is SSL/TLS. It does not expose the complete negotiated `SSLSession`. A truthful `SmtpConnectionFacts` result therefore needs an upstream/provider seam or controlled socket instrumentation. This is a good SPI shape to consider before the 10.0 API freeze, but not a fact set to fake from configuration.

## 7. Native HTTP CONNECT proxy support

Angus natively supports HTTP web proxies through `mail.smtp.proxy.host`, `port`, `user`, and `password`, in addition to SOCKS host and port properties.[^11] Simple Java Mail currently has a typed SOCKS model and supplies its own local bridge for authenticated proxying.

HTTP CONNECT is worth exposing because it is a normal enterprise egress pattern and because the provider already owns the tunnel establishment. A future proxy model should distinguish:

- SOCKS proxy;
- HTTP CONNECT proxy;
- direct connection;
- optional future caller-supplied connector SPI.

Credentials must be held in secret-bearing configuration, redacted from diagnostics, and excluded from equality/string representations. The implementation should test CONNECT authentication failure, proxy refusal, proxy timeouts, TLS through the tunnel, and cancellation while tunnelling.

Angus also offers `SMTPTransport.connect(Socket)`. Directly surfacing that method would punch through mailer pooling, reconnect, timeout, TLS, and ownership contracts. If customers need private network transports, service meshes, or custom tunnels that neither SOCKS nor CONNECT covers, a provider-neutral socket-connector SPI is safer than exposing an Angus transport or preconnected socket in core API.

## 8. Capability diagnostics: useful, but narrower than it first appears

Angus exposes `supportsExtension(name)` and `getExtensionParameter(name)`. This is enough to ask about known extensions on the current SMTP connection. It does not publicly expose a complete immutable capability map, the original server greeting, a retained pre-TLS capability set, selected authentication mechanism, or negotiated TLS session details.[^12]

After STARTTLS, SMTP clients and servers must discard pre-upgrade knowledge and perform a new EHLO. Angus follows this model by replacing its extension map after the post-TLS EHLO.[^13] A Simple Java Mail capability API must therefore attach both timing and connection identity to every fact.

### A truthful first version

A narrow provider-neutral report can expose known post-TLS facts such as:

- whether EHLO succeeded;
- advertised `AUTH` mechanisms/parameter text;
- `SIZE` and its optional numeric limit;
- `DSN`, `8BITMIME`, `SMTPUTF8`, `REQUIRETLS`, `CHUNKING`, `PIPELINING`, and selected other registered extensions;
- whether the live connection is currently TLS-protected;
- the last relevant SMTP response already captured by Simple Java Mail;
- `UNKNOWN` for custom transports or facts the provider cannot observe.

It should say **observed on connection X at time Y, after STARTTLS**, not “the server supports this forever.” With pooling, capabilities belong to a physical connection and can differ across endpoints or reconnects.

### The 10.0 move

If capability-driven features are accepted as a direction, freeze a small provider SPI and immutable facts vocabulary during 10.0. Full arbitrary extension enumeration can wait for an Angus contribution. Do not use reflection into Angus's private extension map, parse debug logs, or expose `SMTPTransport` from the Simple Java Mail API.

## 9. Per-message envelope and ESMTP features

### 9.1 Already surfaced well: envelope sender and basic DSN

Angus's `SMTPMessage` supports an envelope sender, DSN notification choices, and DSN return-content choice. Simple Java Mail already maps its bounce address and typed DSN model to these per-message fields in its Angus adapter.[^14]

This is the right pattern: the core model speaks mail semantics, while the provider module converts them to Angus mechanics.

### 9.2 Complete DSN needs an Angus seam

RFC 3461 defines four important envelope elements: `RET` and `ENVID` on MAIL FROM, plus `NOTIFY` and `ORCPT` on each RCPT TO.[^15] Angus directly models only `RET` and `NOTIFY`. `ENVID` might be squeezed into its generic MAIL extension string, but `ORCPT` is recipient-specific and has no public per-recipient hook.

Simple Java Mail should not label the current feature “complete DSN.” A complete typed model should wait for an upstream provider extension that can safely add `ENVID` and a separately encoded `ORCPT` for each envelope recipient. It must also handle internationalized DSN forms alongside SMTPUTF8.

### 9.3 REQUIRETLS is feasible after capability reporting

RFC 8689 defines a `REQUIRETLS` MAIL FROM parameter and requires the submission server to advertise support on the security-relevant, post-STARTTLS connection.[^16] Angus has no dedicated REQUIRETLS method, but its per-message generic mail-extension field can emit a MAIL FROM parameter.

Simple Java Mail can turn that low-level escape hatch into a safe policy if it:

- verifies the live post-TLS connection advertises REQUIRETLS;
- fails before DATA when the message requires it but the server does not support it;
- adds the parameter per message without leaking it across a pooled connection;
- reports requested, advertised, emitted, and server response as separate facts;
- explains that REQUIRETLS is a downstream transport request, not end-to-end encryption or proof of delivery.

That places REQUIRETLS after the capability seam, probably in 10.x rather than the 10.0 critical path.

### 9.4 SIZE needs the finalized bytes, not an estimate

RFC 1870 lets a client announce message size in MAIL FROM and lets a server advertise a maximum.[^17] Angus exposes the server's extension parameter through its capability query and can append a generic MAIL parameter, but its 2.0.5 SMTP transport does not itself provide a typed exact-size policy.

Simple Java Mail has an unusual advantage here: its exact-EML, DKIM, S/MIME, OpenPGP, and rehearsal work already reasons about finalized content. A useful SIZE feature should measure the same finalized representation that the provider will submit, compare it with the current post-TLS server limit, and distinguish:

- the application's configured maximum email size;
- the measured RFC-relevant submission size;
- the server's advertised maximum;
- a SIZE parameter the client actually emitted;
- a later SMTP rejection, which remains possible.

If provider framing or rewriting means Simple Java Mail cannot prove the measured value corresponds to the provider's submission representation, the feature must stop at “local finalized size” until that seam is fixed.

### 9.5 `SUBMITTER`/AUTH and delegated submission

`SMTPMessage.setSubmitter` supports the SMTP AUTH parameter identifying the submitter. This can matter in delegated enterprise submission, where the authenticated identity and asserted submitter differ. It is legitimate but niche. It should enter the public roadmap only with a concrete delegated-authentication use case, aligned with authorization-ID support and appropriate xtext encoding tests—not as a lone string setter.

### 9.6 Other registered MAIL extensions

Angus's generic per-message extension string can technically carry parameters for extensions such as DELIVERBY, FUTURERELEASE, or MT-PRIORITY. The IANA registry and their RFCs define real semantics, but Angus does not turn them into typed negotiation or policy.[^18]

Keep these on a watch list. Promote one only when there is demonstrated relay support and user demand, then implement capability gating, validation, encoding, and outcomes. Angus exposes all extra MAIL parameters as one undifferentiated string, so typed REQUIRETLS, SIZE, or future extensions must also compose tokens, reject duplicates/conflicts, and define whether they replace a raw session-level extension. A generic raw MAIL FROM suffix is an expert escape hatch, not a safe feature API.

## 10. SMTPUTF8 and 8BITMIME: upstream verification first

These look like easy Angus checkboxes and are not.

### SMTPUTF8

Angus reads `mail.mime.allowutf8`. In 2.0.5, if that property is enabled but the server does not advertise SMTPUTF8, the SMTP transport logs the mismatch and continues; when enabled it also writes protocol commands using UTF-8. It adds the SMTPUTF8 MAIL parameter only when the extension is advertised.[^19]

That is a permissive provider switch, not a complete Simple Java Mail policy. A facade worthy of a robustness claim must inspect the actual envelope and finalized headers, determine whether SMTPUTF8 is required, require both the necessary server capabilities, and fail without lossy downgrade if the requirement cannot be met.

RFC 6531 also requires a server advertising SMTPUTF8 to advertise 8BITMIME, and messages using SMTPUTF8 are sent with `BODY=8BITMIME`.[^20] The policy therefore cannot be designed as one unrelated boolean.

### 8BITMIME

Angus's `allow8bitmime` can convert eligible base64 or quoted-printable text parts to 8-bit when the server advertises 8BITMIME. That changes MIME content and may call `saveChanges()`. Like `sendpartial`, the `SMTPMessage` false value does not override a true session property. Simple Java Mail nevertheless blocks conversion for exact or protected content by making those provider wrappers ineligible for Angus's text/multipart conversion path and suppressing provider `saveChanges()`.[^14]

The 2.0.5 tagged source audit did not find Angus adding the RFC 6152 `BODY=8BITMIME` MAIL parameter when it performs that conversion.[^21] RFC 6152 requires the extended MAIL command when the submitted content uses the 8-bit transport form.[^22]

The appropriate sequence is therefore:

1. create a focused upstream Angus issue/reproducer for `BODY=8BITMIME` and strict SMTPUTF8 behavior;
2. agree on provider behavior and a stable observation/control seam;
3. add wire tests covering ASCII envelopes, internationalized local parts, UTF-8 headers, 7-bit-safe bodies, true 8-bit bodies, exact EML, DKIM, S/MIME, OpenPGP, and pooling;
4. only then expose Simple Java Mail policies such as require, use-if-available, and preserve-exact-content.

Until that work is complete, documentation may explain the raw switches but should not claim standards-complete SMTPUTF8 or automatic 8BITMIME negotiation.

## 11. CHUNKING, BINARYMIME, and PIPELINING

Angus 2.0.5 can use BDAT when `mail.smtp.chunksize` is positive and the server advertises CHUNKING. Its own documentation states that it does not pipeline the chunks and does not support BINARYMIME.[^23]

There is also a known correctness issue in the 2.0.5 BDAT path: data is passed through DATA-style dot transparency even though BDAT is length-delimited. Angus issue 209 and its proposed fix PR 210 remain open in this research snapshot.[^24]

SMTP PIPELINING is not implemented by the provider. RFC 2920 requires careful command grouping and ordered reply processing; it is not a session property that Simple Java Mail should emulate outside Angus's state machine.[^25]

Recommended disposition:

- contribute, test, and release the CHUNKING correctness fix upstream;
- add BINARYMIME only as an Angus/provider project if a compelling use case exists;
- implement PIPELINING upstream with failure-injection and ambiguous-outcome tests;
- expose typed Simple Java Mail throughput policy only after released provider behavior is correct;
- continue to use bounded concurrency and connection pooling as the reliable current throughput strategy.

This is a clear example where “bubble up more Angus” is the wrong framing. The work is to improve Angus first and then expose a stable high-level policy.

## 12. Connection liveness and shutdown policy

Angus provides several low-level lifecycle controls:

- `userset` chooses RSET instead of NOOP for `isConnected()`;
- `noop.strict` decides whether only a 250 reply counts as a healthy connection;
- `quitwait` decides whether close waits for the server's QUIT response;
- `quitonsessionreject` controls QUIT behavior after a rejected session greeting;
- `getLastReturnCode()` and `getLastServerResponse()` expose the provider's latest response.[^2]

These are relevant to a pooled mailer, but most callers should not configure four flags.

Simple Java Mail should instead define and test a **connection-health contract**:

- what validates an idle leased connection;
- whether RSET is needed to establish both liveness and clean transaction state;
- which replies mark a connection unhealthy;
- when a failure destroys rather than returns a transport;
- how close and shutdown are bounded by read/deadline policy;
- which last-response facts belong to a connection-health event versus a message receipt.

If operational experience reveals meaningful alternatives, expose a small `ConnectionValidationPolicy`. Otherwise internalize the best behavior and leave the raw Angus switches expert-only.

The current Angus adapter already demonstrates the required pool discipline by restoring `reportSuccess` in a `finally` block before the transport lease can be released.[^14] New per-message controls should prefer `SMTPMessage` fields; any temporary transport mutation must receive the same restoration treatment.

## 13. Observability: normalize facts, do not expose debug mode

Jakarta Mail offers connection and transport listeners plus configurable event queues/executors. Angus also has debug properties that can include authentication commands, usernames, and even passwords.[^26]

Simple Java Mail should not promote these as its primary observability API:

- Jakarta listeners expose provider lifecycle events, not the library's complete submission semantics;
- debug output is text, unstable for parsing, difficult to correlate, and capable of disclosing secrets;
- event executor knobs introduce another lifecycle that the high-level library would have to explain.

The better facade is structured and redacted:

- mailer lifecycle and pool metrics;
- connection attempt, TLS, authentication, and capability facts where truly observable;
- submission receipt, per-recipient outcomes, retries, and ambiguous-outcome classification;
- timeout phase and elapsed duration;
- proxy route type but never proxy credentials;
- stable reason codes plus provider exception chains for expert diagnosis.

Raw Jakarta Mail debugging can remain an explicitly unsafe diagnostic escape hatch. Password-bearing debug flags should not receive convenience methods.

## 14. RFC 5322 address groups and originator fields

The first version of this report concentrated too heavily on Angus's SMTP provider surface. Jakarta Mail also exposes message-format capabilities that directly affect outbound submission, and RFC address groups are the clearest omission from that first pass.

### 14.1 What an RFC group is

RFC 5322 destination fields contain an `address-list`, whose entries may be individual mailboxes or named groups. A group has a display name, a colon, zero or more member mailboxes, and a terminating semicolon—for example `Release team: alice@example.org, bob@example.org;`. Groups cannot contain other groups. An empty group such as `undisclosed-recipients:;` is valid and is commonly useful when the actual envelope recipients are blind copies.[^28]

This is a message-header construct, not a server-side distribution list. Its non-empty members normally remain visible in the header. SMTP itself receives individual mailbox paths through RCPT TO; there is no SMTP “group recipient.”

Jakarta Mail models this directly. `InternetAddress.isGroup()` detects the construct and `getGroup(strict)` returns zero or more member addresses.[^29] Angus calls its own group-expansion step before the RCPT phase, replacing each group with its member mailboxes for the SMTP envelope.[^30]

### 14.2 What Simple Java Mail does today

Simple Java Mail's public composed-message model assumes a flat `List<Recipient>`, where each `Recipient.address` is treated as one mailbox. The normal producer turns every entry back into one `InternetAddress` and adds it to To, Cc, or Bcc. The default JMail strict validator validates the entire `Recipient.address` as a mailbox; a direct check against the currently used JMail 2.2.1 rejects both a non-empty RFC group and `undisclosed-recipients:;`.[^31]

There is nevertheless partial, latent support below that API:

- a caller who bypasses the default validator and preserves a group-form address can reach Jakarta Mail/Angus;
- the Angus adapter already mirrors Angus's group expansion when it constructs the envelope used for structured recipient reporting;
- Angus itself expands the group before issuing RCPT TO.

That does not amount to supported parity. String-list parsing is not group-aware by contract, validation rejects the construct, the model cannot distinguish a mailbox from a group, conversion cannot preserve group provenance, and no tests establish composed-message round trips, empty groups, duplicate members, reply behavior, overrides, SMTPUTF8, or protected-message behavior.

### 14.3 What should bubble up

Do not make this another permissive string overload. Model the grammar:

- a `Mailbox` value containing display name and addr-spec;
- a destination-address abstraction whose variants are `Mailbox` and `RecipientGroup`;
- `RecipientGroup` with a display name, an ordered list of mailbox recipients, and a To/Cc/Bcc role;
- zero-member groups as a deliberate supported case;
- no nested groups, because RFC 5322's group list is a mailbox list;
- explicit flattening from header addresses to the SMTP envelope;
- optional group provenance on each `MailRecipientResult`, while retaining mailbox-level retry decisions.

Validation should parse the group structure, validate every member mailbox under the selected mailbox policy, validate/encode the display name, and reject malformed or nested groups. It should not require callers to disable all address validation.

The header/envelope distinction matters. A group may be shown in To or Cc while envelope overrides redirect a test message elsewhere; an empty To group may accompany actual Bcc envelope recipients; and duplicate mailboxes may occur in more than one visible group. The API must define whether envelope duplicates are retained or coalesced and how results map back to header occurrences.

Security modules also need an explicit rule. Per-recipient S/MIME certificates belong to the flattened member mailbox, not the display group. DKIM signs the rendered header. SMTPUTF8 requirements must be derived from both group display/header content and the flattened envelope rather than from a raw group string.

This is a **10.0 API-shape decision** even if implementation is scheduled for 10.x. If `Recipient` is formally declared mailbox-only now, groups can be added as a separate destination type without pretending an arbitrary address string is both models at once.

### 14.4 The adjacent omission: multiple authors and `Sender`

RFC 5322 distinguishes authorship from transmission. `From` is a list of one or more author mailboxes. If it contains multiple mailboxes, a single `Sender` mailbox is mandatory; `Sender` is also appropriate when the transmitting agent differs from the author.[^28] Jakarta Mail exposes both `addFrom(Address[])` and `setSender(Address)`.[^32]

Simple Java Mail currently has one `fromRecipient`; its composed-message producer calls singular `MimeMessage.setFrom`, and its parser keeps only the first address returned by `MimeMessage.getFrom()`. There is no typed `Sender` field. A raw custom header is not equivalent to a validated, round-trippable originator model and can interact badly with already generated structured headers.[^33]

A complete model would use:

- `authors: List<Mailbox>` with at least one entry;
- optional `sender: Mailbox`;
- validation that requires `sender` when there is more than one author;
- a separately named SMTP envelope sender, preserving the existing bounce-address distinction;
- documented DKIM identity and deliverability implications.

This is less commonly needed than recipient groups, so it is a later implementation candidate. The model decision still belongs in the same 10.0 review because changing singular `from` semantics after the major release would be harder.

### 14.5 Lower-priority RFC message structures

Jakarta Mail can also retain or set resent-field blocks, comments, keywords, content-language, and other structured headers. Most do not merit dedicated Simple Java Mail methods. Exact EML already serves byte-preserving retransmission; carefully validated generic headers serve uncommon composition cases. Resent blocks should become typed only if Simple Java Mail deliberately adds a reintroduction/remailing workflow, because their ordering and block semantics are more than independent header strings.

## 15. Send-relevant MIME and Session capabilities

Angus and Jakarta Mail also contain configuration below SMTP: provider selection, event dispatch, address parsing strictness, UTF-8 parsing, filename and parameter encoding, malformed Base64/UUEncode tolerance, multipart-boundary tolerance, content-type cleanup hooks, and global/default charset behavior.[^27]

Most of these should **not** bubble into the mailer builder.

| Capability family | Outbound relevance | Recommended treatment |
|---|---|---|
| Provider registry and protocol-class selection | Replacing Angus or selecting another provider | Provider-module/SPI concern; not ordinary mailer configuration |
| Event scope and executor | Dispatching Jakarta listener callbacks | Internalize or replace with Simple Java Mail observers; expert properties only |
| Address parser strictness | Parsing caller-provided header strings | Keep validation explicit in Simple Java Mail; do not silently relax globally |
| Charset, encoded-word, filename, and parameter serialization | Can affect wire bytes and interoperability | Prefer explicit content APIs and tested serialization profiles; avoid process-global switches |
| Malformed Base64, UUEncode, or multipart tolerance | Primarily inbound/repair behavior | Out of outbound SMTP scope; useful only in a separate parsing/repair API |
| `mail.mime.allowutf8` | Affects headers, address parsing, and SMTP command behavior | Replace with a content/capability policy after upstream verification |
| Content-type handler hooks | Specialized MIME repair/normalization | Expert conversion SPI, not a mailer convenience option |
| Native-image support | Packaging/runtime compatibility | Test and document; it is not a public mail-sending capability |

The principle is especially important for system properties. A per-message library should not expose a setting that silently changes MIME parsing or serialization for the entire JVM unless the scope and initialization timing are unavoidable and loudly documented.

The Angus DSN module can create and parse DSN/MDN MIME structures. Parsing a supplied `.eml` report could someday be useful without Simple Java Mail implementing POP3/IMAP, but it is a separate message-processing feature, not SMTP submission parity.

## 16. Holistic surfacing model: abstraction, convenience, or escape hatch?

The second pass widened the unit of comparison from “documented SMTP property” to “useful capability available somewhere below Simple Java Mail.” That exposes two different kinds of parity:

- **semantic parity:** the application can express a real goal such as rotating credentials, mutual TLS, group recipients, or requiring all recipients;
- **ergonomic parity:** an expert can reach a provider control without memorizing a property key, value type, scope, precedence rule, and pooling hazard.

Simple Java Mail needs both, but not in one flat builder. The right shape is four lanes:

| Surfacing lane | Stability promise | Appropriate examples |
|---|---|---|
| Provider-neutral Simple Java Mail abstraction | Long-lived application intent independent of Angus mechanics | recipient acceptance, dynamic credentials, TLS material, groups/originators, reply identity, submission outcome |
| Typed provider-scoped options | Discoverable access with an explicitly Angus-sized compatibility promise | strict `NOOP`, QUIT behavior, SASL realm/canonical-host controls, selected interoperability switches |
| Library-owned internal policy | Safer behavior for which callers should not have to know the Angus implementation detail | shared write-timeout executor, fail-closed custom socket factories, pooled-state reset, redacted diagnostics |
| Expert escape hatch or upstream work | No normal compatibility promise, or behavior that only Angus can correctly implement | raw properties, provider SPI, arbitrary SMTP commands, complete extension snapshot, PIPELINING, strict SMTPUTF8 work |

An illustrative API boundary would let the portable builder accept a provider-options marker while the Angus module supplies something like `AngusSmtpOptions`. The name is not the important decision. The important constraints are that `core-module` contains no Angus type, the adapter rejects options meant for another provider, object-valued settings remain objects, secrets never enter `toString()` or diagnostics, and typed Simple Java Mail policy has documented precedence over provider options and raw properties. The Angus options object should be selective, not a generated wrapper around all 61 properties.

This lane solves a real problem. `mail.smtp.quitwait`, `mail.smtp.quitonsessionreject`, SASL realm selection, and strict `NOOP` handling can be useful to an operator without deserving permanent methods on `MailerGenericBuilder`. Conversely, `mail.smtp.sendpartial`, TLS material, and authentication-over-plaintext are too semantically important to be labelled Angus trivia; they deserve provider-neutral policy.

### 16.1 Credentials should be dynamic beyond OAuth2

Simple Java Mail already has the right pattern in `OAuth2AccessTokenProvider`: resolve a current secret when a physical connection opens or reconnects rather than freezing it when the mailer is built. Fixed username/password authentication still comes from the static server configuration and Jakarta `Authenticator`.[^36]

A provider-neutral `CredentialProvider` would generalize that pattern for rotating passwords, secret managers, temporary credentials, and future authentication forms. Its context can identify the target host, port, transport security mode, and requested authentication mode. It must be thread-safe, secret-bearing, redacted, and invoked at a precisely documented lifecycle point. A pooled connection remains authenticated with the credential used to create it; rotation affects a new or re-established physical connection rather than silently changing an existing SMTP session.

OAuth2 should remain as a convenience specialization, not be replaced with a vague callback returning arbitrary strings. Authentication-mechanism allow-lists and credential acquisition are separate policies: one decides *how* the client may authenticate; the other supplies the secret for a connection attempt.

There is also a wider identity model that should be named before adding more setters:

| Identity | Protocol role | Current direction |
|---|---|---|
| Header author(s), `From` | Who authored the message | Expand from one mailbox only if multiple-author semantics are accepted |
| Header `Sender` | Agent that transmitted a multi-author message | Add as a typed originator field |
| SMTP envelope sender | Bounce/return-path identity | Already typed; keep distinct from header `From` |
| Authentication identity | Account proving its credentials | Existing username/OAuth configuration; feed through credential policy |
| SASL authorization identity | Identity on whose behalf the authenticated principal acts | Type only with a real delegated-authentication use case |
| SMTP `AUTH=` submitter | Per-message submitter asserted on `MAIL FROM` | Model with delegated submission and capability evidence |

RFC 4954 deliberately distinguishes authenticated identity from the optional per-message submitter parameter. Collapsing these identities into one “sender” abstraction would make the API easier to misuse, not easier to use.[^42]

### 16.2 Transport TLS should model material, not factories

Angus can mechanically support custom key and trust behavior because it accepts an `SSLSocketFactory`; Simple Java Mail exposes factory class/instance hooks. That is enough for an expert, but it is not an enterprise-grade configuration story.[^37]

A transport `TlsPolicy` should keep four decisions separate:

1. **client identity material** for mutual TLS, such as a selected private key/certificate chain from a `KeyStore` or PKCS #12 source;
2. **server trust anchors**, including additive or replacement trust-store policy;
3. **endpoint identity verification**, which verifies the SMTP hostname and is not the same as trusting a certificate chain;
4. **protocol and cipher constraints**, plus observable negotiated evidence when Angus exposes the necessary seam.

The existing S/MIME PKCS #12 model is not itself this API: an S/MIME signing identity and a transport client certificate have different scopes and lifecycles. Loading primitives may be shared internally, but their domain types should remain distinct.

Whenever Simple Java Mail installs a custom socket factory, it should also make Angus's factory behavior fail closed. The 2.0.5 socket path defaults fallback to `true`, catches most custom-factory failures, and may create a socket with the default factory instead.[^34] An explicit low-level compatibility override could remain in Angus options, but the normal high-level TLS contract must never silently abandon caller-supplied trust, pinning, or client identity.

Angus also accepts a preconnected socket. If real demand emerges for custom DNS, service-mesh tunnels, or instrumented transports, expose a provider-neutral connector SPI with explicit socket ownership, connect deadline, route metadata, TLS responsibilities, and cancellation rules. Do not expose `SMTPTransport.connect(Socket)` directly through the public mailer.[^12]

### 16.3 Submission failures should identify where and why

Angus exceptions preserve more structured SMTP context than Simple Java Mail currently normalizes: connection host/port/timeout; rejected or accepted address; failed SMTP command; and three-digit return code. Simple Java Mail's receipt model is already stronger at the transaction level and parses a valid RFC 3463 enhanced status code, but it currently returns that enhanced code as a string and has no stable failure-phase vocabulary.[^38]

Add provider-neutral facts such as:

- a `SubmissionPhase` with at least `POOL_ACQUIRE`, `CONNECT`, `GREETING`, `STARTTLS`, `AUTHENTICATE`, `MAIL_FROM`, `RCPT_TO`, `DATA_COMMAND`, `CONTENT_WRITE`, `FINAL_REPLY`, `QUIT`, and `UNKNOWN`;
- a parsed `EnhancedStatusCode` value containing class, subject, and detail while preserving the original reply;
- a timeout kind and elapsed/deadline context where known;
- a stable failure category, with the original exception retained as cause rather than exposed as the primary API.

The provider cannot truthfully distinguish every transport break, so `UNKNOWN` and coarse phases are essential. This is still much more useful for retry, metrics, incident analysis, and support than asking applications to inspect an Angus exception chain. RFC 3463 defines the class/subject/detail shape, and IANA maintains the registered enhanced-status-code values; parsing the syntax does not mean the library should hard-code every future registry entry.[^38]

### 16.4 Replying and MIME construction contain useful Jakarta-level abstractions

The EE4J Jakarta Mail implementation's `MimeMessage.reply` recognizes session-level alternate identities and a policy controlling whether reply-all recipients stay in their original `To`/`Cc` roles or are collected into `Cc`. These are explicitly implementation properties rather than requirements of the Jakarta Mail specification. Simple Java Mail delegates reply construction to that method but does not expose an explicit set of the user's own aliases or the recipient-placement rule.[^39] A small provider-neutral `ReplyPolicy` can make both deterministic and group-aware without inheriting those properties as public API.

Jakarta Mail also has many serialization switches for strict address parsing, UTF-8, encoded words, RFC 2231 parameters and filenames, folding, and compatibility with malformed MIME. Simple Java Mail currently makes an opinionated UTF-8 choice and treats values passed through its generic header API as unstructured encoded text.[^40] The useful surface is not a boolean for every Jakarta system property. It is a narrow, testable `MessageEncodingPolicy` with a modern default and named compatibility profiles only where real interoperability evidence exists.

Two related extensions are worth keeping on the 10.x watch list:

- distinguish typed structured fields from an explicitly unstructured or raw custom-header escape hatch; address lists, groups, dates, message IDs, and MIME parameters should not all pass through the same text encoder;
- permit additional body alternatives or a controlled `MimePartSpec` extension point before finalization, signing, and encryption, rather than exposing a mutable `MimeMessage` callback that can invalidate exact EML, DKIM, S/MIME, or OpenPGP guarantees.

These are composition capabilities, not wire-protocol features, but they determine the bytes Angus submits. They therefore belong in a holistic outbound parity review.

### 16.5 Capabilities that should deliberately remain below the facade

The implementation also consumes controls outside the published SMTP property table, including source-only `mail.<protocol>.usesocketchannels`, `mail.<protocol>.auth.ntlm.v2`, a deprecated SASL-realm alias, and both documented and source-only fine-grained debug switches.[^41] Their existence does not make them API candidates.

In particular, `usesocketchannels` merely changes how Angus creates an otherwise ordinary socket when compatible with the selected route. Simple Java Mail has no provider-neutral behavioral contract to attach to it, and no reason to imply performance or cancellation benefits without dedicated evidence. It should remain an expert source-level detail. The same restraint applies to provider class selection, malformed-input repair switches, debug output capable of revealing authentication material, raw SMTP commands, mutable provider transports/messages, and individual negative AUTH-mechanism flags superseded by an affirmative allow-list.

The holistic result is therefore not “surface everything.” It is **surface every valuable intent at the narrowest honest layer**.

## 17. Complete capability disposition matrix

The table groups aliases and closely related properties so that the recommendation reflects capabilities rather than counting `smtp` and `smtps` spellings twice.

| Angus capability | Current Simple Java Mail position | Disposition | Horizon |
|---|---|---|---|
| Host, port, user, password | Typed | Keep typed | Existing |
| SMTP vs SMTPS vs STARTTLS | Typed transport strategies | Keep; make security semantics more explicit | 10.0 |
| Connect/read/write timeout | One combined timeout, always applied for built-in sessions | Split and keep convenience aggregate | 10.0 |
| External write-timeout executor | Reachable only with object-valued `Properties`; not lifecycle-managed | Internalize shared scheduler; optional advanced injection | 10.0 |
| Envelope sender | Typed bounce address, mapped per message | Keep typed | Existing |
| RFC 5322 recipient groups | Latently passable only around normal validation; no typed model | Add `RecipientGroup`, group-aware validation, header preservation, and envelope flattening | 10.0 API decision; 10.x delivery |
| Empty privacy group | Default validator rejects it; actual Bcc recipients can exist separately | Support as the zero-member case of `RecipientGroup` | With group support |
| Multiple `From` authors | Composed model and importer retain one author | Add author list only with RFC-required validation | Later; decide model in 10.0 |
| Distinct `Sender` header | Only possible as an untyped custom header | Add typed transmitting mailbox alongside authors | Later; decide model in 10.0 |
| Header-to-envelope group provenance | Angus and SJM adapter flatten to mailboxes; receipt loses group identity | Optionally retain source group on mailbox-level results | With group support |
| Resent-field block | Exact EML or generic headers only | Keep expert/exact unless a remailing workflow is accepted | Watch |
| Reply-all alternate/self identities | Jakarta session property not represented as SJM policy | Add explicit own-address/alias set to `ReplyPolicy` | 10.x |
| Reply-all `To`/`Cc` placement | Delegated implicitly to Jakarta defaults | Add deterministic `ReplyPolicy` choice | 10.x |
| EHLO client hostname | Typed | Keep typed | Existing |
| Local bind address and port | Typed | Keep typed | Existing |
| Disable EHLO/use HELO | Raw property | Expert-only legacy interoperability switch | No normal API |
| Authentication enabled | Implied by credentials/strategy | Fold into authentication policy | 10.0/10.x |
| Ordered AUTH mechanisms | Raw property except XOAUTH2 strategy | Type as ordered allow-list | 10.0/10.x |
| Per-mechanism disable flags | Raw property | Do not mirror; allow-list supersedes them | No separate API |
| Dynamic OAuth2 token | Higher-level provider exists | Keep; integrate into auth policy | Existing/10.0 cleanup |
| Dynamic password/general credentials | Fixed server credential only | Add provider-neutral per-connection `CredentialProvider` | 10.0 shape/10.x |
| NTLM domain/flags/compatibility | Raw provider details | Expert-only | No normal API |
| Source-only NTLM v2 selector | Undocumented Angus implementation control | Do not promote; upstream/document only if a supported use case emerges | Expert/upstream |
| Java SASL enable/mechanisms | Raw properties | Advanced auth policy or provider config | Later |
| SASL authorization ID | Not typed | Type only with delegated-submission use case | Later |
| SASL realm/canonical host | Raw properties | Expert-only | Later/no normal API |
| SMTP submitter/AUTH parameter | Not typed | Model with delegated submission, not as lone string | Later/watch |
| DSN `NOTIFY` and `RET` | Typed and mapped per message | Keep typed | Existing |
| DSN `ENVID` and `ORCPT` | Not supported | Upstream per-envelope/per-recipient seam first | Upstream |
| Partial recipient sending | Raw session property; receipt already explains result | Type per message | 10.0 |
| `reportSuccess` | Internally forced and normalized into receipt | Continue to internalize | Existing |
| Last SMTP code/response | Captured around sends | Continue structured capture; define connection-vs-message scope | Existing/10.x |
| SMTP command/failure phase | Partly recoverable from provider exceptions, not normalized | Add provider-neutral `SubmissionPhase` with `UNKNOWN` fallback | 10.x |
| RFC 3463 enhanced status | Validated but returned as a string | Add parsed class/subject/detail value while preserving raw reply | 10.x |
| STARTTLS enabled/required | Typed strategies | Keep; make credential fallback explicit | 10.0 |
| Server identity verification | Typed, secure default | Keep typed | Existing |
| Trusted host/all-host trust | Typed | Keep but retain strong warnings | Existing |
| SSL socket factories | Typed class/instance support | Keep advanced | Existing |
| Socket-factory fallback | Angus defaults to fallback after most custom-factory failures | Disable internally whenever SJM installs a custom factory; optional Angus-only override | 10.0 |
| Client certificate/private key for mutual TLS | Achievable only through caller-built socket factory | Add transport TLS identity material distinct from S/MIME keys | 10.x |
| Custom trust anchors/trust-store material | Achievable through factory or host-trust shortcuts | Add explicit trust material; keep chain trust distinct from hostname verification | 10.x |
| Custom hostname verifier | Raw object property | Advanced TLS customization only | Later |
| TLS protocols | Raw property | Type as allow-list | 10.x |
| TLS cipher suites | Raw property | Type as expert allow-list | 10.x |
| Negotiated TLS session facts | Mostly unavailable from public Angus SMTP API | Upstream/provider instrumentation | Upstream |
| SOCKS proxy | Typed, including authenticated bridge | Keep | Existing |
| HTTP CONNECT proxy | Angus native properties, not typed in SJM | Add typed proxy variant | 10.x |
| Non-TLS socket factory and factory port | Raw provider properties only | Angus options for direct access; prefer a connector SPI for a durable behavior contract | Expert/later |
| Preconnected socket | Angus transport API only | Provider-neutral connector SPI, if demanded | Later |
| SocketChannel-backed socket creation | Undocumented Angus source switch | Keep expert-only; do not imply benefits without measured contract | No normal API |
| Arbitrary MAIL FROM extension | Raw property/Angus message field | Expert-only; do not expose injection-like string in normal API | Existing escape hatch |
| Post-EHLO named extension lookup | Provider API only | Provider-neutral known-capability facts | 10.x |
| Complete capability map | Private provider state | Upstream snapshot API | Upstream |
| Pre-/post-TLS capability history | Pre-TLS state intentionally discarded | Model post-TLS truth; upstream only if history is diagnostically required | 10.x/upstream |
| REQUIRETLS | Possible through generic per-message extension | Type after capability seam and wire tests | 10.x |
| SIZE | Capability value plus generic MAIL extension possible | Type only against finalized provider-equivalent bytes | 10.x |
| SMTPUTF8 | Permissive `mail.mime.allowutf8` provider behavior | Upstream strict policy/control first | Upstream |
| 8BITMIME conversion | Provider can rewrite eligible content | Upstream verification/fix; preserve exact content | Upstream |
| CHUNKING/BDAT | Provider supports `chunksize`; known open correctness fix | Angus first | Upstream |
| BINARYMIME | Not supported by Angus | Provider work only if demand warrants it | Upstream/watch |
| PIPELINING | Not implemented by Angus | Angus state-machine work | Upstream |
| RSET vs NOOP validation | Raw property/provider method | Internalize into pool health contract | 10.x |
| Strict NOOP reply | Raw property/provider method | Internalize; expose only as compact validation policy if needed | 10.x |
| Wait for QUIT reply | Raw property | Usually internal lifecycle policy | No normal API |
| QUIT after greeting rejection | Raw property | Internal/provider policy | No normal API |
| Raw SMTP commands | Public/provider methods | Provider SPI or test tooling only | No core API |
| Jakarta transport/connection listeners | Lower-level events | Normalize into SJM observers/metrics | Later |
| Jakarta event executor/scope | Object-valued session properties | Internalize if Jakarta listeners are used | No normal API |
| Debug including auth material | Raw session properties | Redacted diagnostics instead | Never promote |
| Provider registry/class selection | Jakarta Session feature | Module/SPI boundary | Existing architecture |
| Structured standard header values | Generic SJM header path treats values as unstructured text | Add typed fields where semantics matter; retain explicit raw escape hatch | 10.x/watch |
| Additional MIME body alternatives | Opinionated plain/HTML/calendar composition only | Add controlled alternative/part specification before content finalization if demanded | 10.x/watch |
| MIME parser laxness | Primarily inbound | Out of scope | No mailer API |
| MIME serialization compatibility switches | Can alter bytes, often globally | Explicit tested serialization policy only where justified | Later/watch |

## 18. Proposed 10.0 cut

### Commit to for 10.0

1. **Correct timeout resource ownership.** Split timeout settings and share the write-timeout scheduler across physical connections owned by a mailer/pool.
2. **Make custom transport security fail closed.** When Simple Java Mail selects a custom socket factory, explicitly disable Angus's default fallback to another factory.
3. **Add per-message recipient acceptance policy.** Map it through the existing provider-specific message wrapper and receipt semantics.
4. **Settle authenticated plaintext behavior.** Prefer verified TLS before credentials; make compatibility fallback explicit and migration-documented.
5. **Define the layered provider-configuration contract.** Typed SJM policy wins on collisions; selective Angus options sit below it; raw properties remain visibly expert-level; object values remain objects; secret values are redacted; unknown properties can be diagnosed.
6. **Decide the address-model boundary.** Declare whether `Recipient` is mailbox-only and reserve a distinct RFC group/originator model before another major cycle cements the flat representation.
7. **Freeze only the provider-neutral seams needed by the accepted roadmap.** In particular, consider capability facts, rotating credentials, and advanced TLS policy before the major-version API freeze even if every implementation lands later.

### Take if capacity remains

- ordered authentication mechanism allow-list;
- a general per-connection credential provider;
- TLS protocol/cipher constraints;
- the first known-extension capability snapshot;
- native HTTP CONNECT proxy;
- the selective Angus-options carrier if its module boundary is settled.

### Defer to 10.x

- REQUIRETLS;
- exact SIZE policy;
- richer connection-health controls;
- delegated SASL/submitter support;
- TLS client identity and trust-material convenience;
- TLS evidence once the provider seam exists;
- structured failure phase and enhanced-status values;
- reply identity policy and controlled MIME-composition extensions.

### Coordinate upstream before promising

- complete capability enumeration and selected AUTH/TLS facts;
- DSN `ENVID`/`ORCPT`;
- strict, standards-complete SMTPUTF8 and 8BITMIME behavior;
- the CHUNKING fix, BINARYMIME if justified, and PIPELINING;
- a physical abort/cancellation seam that preserves SMTP outcome truth.

This cut gives 10.0 a coherent theme: **safe policy, bounded resources, and truthful results over a mature provider**. It avoids filling the release with thin wrappers around obscure strings.

## 19. API design rules for anything promoted from Angus

1. **No Angus types in `core-module`.** Angus mapping stays in the provider module.
2. **Prefer affirmative allow-lists.** `allowedAuthMechanisms(...)` is clearer than five `disableX` flags.
3. **Make insecure states difficult to enter accidentally.** Authentication over unverified plaintext requires a deliberate opt-in.
4. **Put message behavior on the message.** Partial recipient policy, REQUIRETLS, SIZE, DSN, and future envelope extensions must not leak through pooled transport state.
5. **Represent unknown separately from false.** Custom transports may be opaque; lack of evidence is not evidence of lack.
6. **Separate request, observation, action, and outcome.** “Required REQUIRETLS,” “server advertised REQUIRETLS,” “client emitted REQUIRETLS,” and “MAIL FROM accepted” are four facts.
7. **Preserve finalized bytes.** No provider optimization may rewrite exact EML or cryptographically protected content without an explicit, tested contract.
8. **Own every executor and socket lifecycle.** If Simple Java Mail creates it, `Mailer.close()` must deterministically release it. If the caller supplies it, the API must say the caller owns it.
9. **Keep expert access, but label the compatibility boundary.** Raw properties are valuable for new provider features; they are not part of the same stability and portability promise as typed API.
10. **Wire-test every SMTP claim.** Assert commands, parameters, reply ordering, sent bytes, connection reuse, failure phase, and result semantics.
11. **Give provider options a visibly narrower contract.** Typed Angus convenience may be discoverable without pretending it is portable to every Jakarta Mail provider.
12. **Fail closed when replacing a security component.** A requested trust manager, verifier, socket factory, client identity, or credential source must not be silently abandoned for a default.
13. **Separate identities by protocol role.** Header author, `Sender`, envelope sender, authenticated principal, authorization identity, and submitter are not aliases for one field.
14. **Do not type structured protocol data as arbitrary strings.** Groups, enhanced status codes, header address lists, TLS facts, and failure phases deserve validated values or an explicitly raw escape hatch.

## 20. Evidence required before calling a capability robust

Each promoted capability should have more than a unit test that inspects `Properties`:

- scripted SMTP servers covering supported, unsupported, malformed, delayed, disconnecting, and contradictory replies;
- STARTTLS tests where capabilities change or disappear after the upgrade;
- implicit TLS and mandatory/opportunistic STARTTLS variants;
- pooled reuse tests proving per-message state does not bleed;
- resource tests counting live sockets, scheduler threads, queued timeout tasks, and executor shutdown;
- proxy tests for authentication, refusal, timeout, tunnel TLS, and secret redaction;
- exact-byte cases for CRLF normalization, leading dots, multibyte UTF-8, attachments, DKIM, S/MIME, OpenPGP, and imported EML;
- authentication downgrade tests and mechanism allow-list tests;
- credential-rotation tests across first connect, pooled reuse, reconnect, concurrent refresh, and provider failure;
- mutual-TLS tests separating key selection, trust-chain validation, hostname verification, and custom-factory failure;
- recipient matrices combining accepted, temporary rejection, permanent rejection, connection loss, and DATA failure;
- normalized phase/status tests for every provider exception shape plus deliberately ambiguous disconnects;
- tests against a small real-server matrix in addition to deterministic fault-injection servers;
- documented unsupported/unknown behavior for custom transport adapters.

This evidence also turns the comparison into a defensible product story. Simple Java Mail need not claim that it reimplements every SMTP mechanism. It can claim that it converts provider capabilities into safer policies and more truthful outcomes—and show the tests that make the statement verifiable.

## 21. Final assessment

Angus has breadth because it is a protocol provider. Simple Java Mail can have depth because it knows the caller's intent, the message model, the provider lifecycle, pooling, security modules, and the result the application needs.

The most valuable parity work is therefore not a long row of builder methods. It is a smaller control plane over Angus:

- resource-safe timeout execution;
- explicit transport and authentication policy;
- rotating credentials and transport TLS material;
- per-message envelope decisions;
- a complete address/originator identity model;
- provider-neutral capability and connection facts;
- exact-content-aware extension negotiation;
- phase-aware structured submission outcomes;
- selective, typed Angus convenience below the portable API;
- upstream provider improvements where the wire state machine belongs.

If 10.0 gets those boundaries right, later Angus capabilities can be added without exposing provider internals or accumulating permanent API debt. The strategic goal is not to hide Angus and not to clone it: it is to make common intent safer, advanced configuration discoverable, and actual outcomes observable. That is a stronger route to “most robust SMTP framework” than raw feature-count parity.

## Sources

[^1]: Jakarta Mail 2.1 specification, especially the architecture and `Transport` abstraction: [Jakarta Mail 2.1](https://jakarta.ee/specifications/mail/2.1/jakarta-mail-spec-2.1).

[^2]: Eclipse Angus Mail SMTP provider package documentation, including the full property table and warning about provider-specific APIs: [Angus SMTP package summary](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html). See also the tagged [2.0.5 package source](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/package-info.java).

[^3]: Simple Java Mail working-tree dependency management: `pom.xml:235-252`. Angus 2.0.5 release record: [Eclipse Angus Mail 2.0.5](https://github.com/eclipse-ee4j/angus-mail/releases/tag/2.0.5).

[^4]: Angus documents `mail.smtp.writetimeout` and `mail.smtp.executor.writetimeout`, including the remove-on-cancel recommendation, in the [SMTP package summary](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html).

[^5]: Angus 2.0.5 implementation: [WriteTimeoutSocket.java](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/core/src/main/java/org/eclipse/angus/mail/util/WriteTimeoutSocket.java) and [SocketFetcher.java](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/core/src/main/java/org/eclipse/angus/mail/util/SocketFetcher.java).

[^6]: Simple Java Mail working-tree sources: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailerGenericBuilder.java:49-52,283-296,681-705`; `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java:244-269`; and `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerGenericBuilderImpl.java:723-754`.

[^7]: Angus per-message controls: [SMTPMessage Javadoc](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/SMTPMessage.html) and [tagged source](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPMessage.java).

[^8]: Simple Java Mail working-tree behavior tests: `modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/SmtpRecipientRepliesTest.java:53-90,279-285`.

[^9]: Simple Java Mail working-tree transport semantics: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/config/TransportStrategy.java:30-59,395-425,574-594`.

[^10]: Authentication and STARTTLS requirements: [RFC 4954, SMTP Service Extension for Authentication](https://www.rfc-editor.org/rfc/rfc4954.html).

[^11]: Angus HTTP CONNECT and SOCKS properties are documented in the [SMTP package summary](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html); implementation is in [SocketFetcher.java](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/core/src/main/java/org/eclipse/angus/mail/util/SocketFetcher.java). Current SJM SOCKS mapping: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java:333-370`.

[^12]: Angus transport queries and commands: [SMTPTransport Javadoc](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/SMTPTransport.html) and [tagged source](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java).

[^13]: STARTTLS state reset and fresh EHLO requirements: [RFC 3207](https://www.rfc-editor.org/rfc/rfc3207.html).

[^14]: Simple Java Mail working-tree provider mapping, state restoration, and exact-content defenses: `modules/angus-mail-provider-module/src/main/java/org/simplejavamail/internal/mailprovider/angus/AngusMailTransportAdapter.java:52-87,112-117,154-252`.

[^15]: Delivery Status Notification envelope parameters: [RFC 3461](https://www.rfc-editor.org/rfc/rfc3461.html).

[^16]: REQUIRETLS advertisement and MAIL FROM parameter: [RFC 8689](https://www.rfc-editor.org/rfc/rfc8689.html).

[^17]: SMTP SIZE extension: [RFC 1870](https://www.rfc-editor.org/rfc/rfc1870.html).

[^18]: Extension registry: [IANA SMTP Service Extensions](https://www.iana.org/assignments/smtp). Examples: [DELIVERBY, RFC 2852](https://www.rfc-editor.org/info/rfc2852/), [FUTURERELEASE, RFC 4865](https://www.rfc-editor.org/info/rfc4865/), and [MT-PRIORITY, RFC 6710](https://www.rfc-editor.org/info/rfc6710/).

[^19]: Angus 2.0.5 SMTPUTF8 decision path: [SMTPTransport.java](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java).

[^20]: Internationalized SMTP requirements: [RFC 6531](https://www.rfc-editor.org/rfc/rfc6531.html).

[^21]: Angus 2.0.5 `allow8bitmime` conversion path and MAIL FROM construction: [SMTPTransport.java](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java). This is an absence finding from the tagged-source audit, not a claim about unreleased Angus code.

[^22]: 8BITMIME and the `BODY=8BITMIME` MAIL parameter: [RFC 6152](https://www.rfc-editor.org/info/rfc6152/).

[^23]: Angus CHUNKING behavior and limitations: [Angus SMTP package summary](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html); protocol definition: [RFC 3030](https://www.rfc-editor.org/info/rfc3030/).

[^24]: Current upstream tracking: [Angus issue 209](https://github.com/eclipse-ee4j/angus-mail/issues/209) and [pull request 210](https://github.com/eclipse-ee4j/angus-mail/pull/210).

[^25]: SMTP PIPELINING semantics: [RFC 2920](https://www.rfc-editor.org/rfc/rfc2920.html).

[^26]: Jakarta/Angus session debug and event properties: [Angus 2.0.5 core package source](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/core/src/main/java/org/eclipse/angus/mail/package-info.java).

[^27]: Angus/Jakarta Mail MIME and session behavior is distributed across the [Angus 2.0.5 core package source](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/core/src/main/java/org/eclipse/angus/mail/package-info.java), [Angus utility package](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/util/package-summary.html), and Jakarta Mail's [MimeMessage](https://jakarta.ee/specifications/mail/2.1/apidocs/jakarta.mail/jakarta/mail/internet/mimemessage) and [MimeUtility](https://jakarta.ee/specifications/mail/2.1/apidocs/jakarta.mail/jakarta/mail/internet/mimeutility) APIs. The disposition here is limited to their effect on outbound submission.

[^28]: RFC 5322 group grammar, destination fields, and originator fields: [RFC 5322](https://www.rfc-editor.org/rfc/rfc5322.html), sections 3.4, 3.6.2, and 3.6.3.

[^29]: Jakarta Mail group support: [InternetAddress Javadoc](https://jakarta.ee/specifications/mail/2.1/apidocs/jakarta.mail/jakarta/mail/internet/internetaddress), especially `isGroup()` and `getGroup(boolean)`.

[^30]: Angus 2.0.5 group expansion: [SMTPTransport.java](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java), in `expandGroups()` around lines 1502-1532.

[^31]: Simple Java Mail working-tree group boundary: `modules/core-module/src/main/java/org/simplejavamail/internal/util/MiscUtil.java:55-57,155-185,654-665`; `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerGenericBuilderImpl.java:321`; `modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageHelper.java:67-78`; and `modules/angus-mail-provider-module/src/main/java/org/simplejavamail/internal/mailprovider/angus/AngusMailTransportAdapter.java:90-108`. JMail 2.2.1 strict-validator behavior was directly checked on this snapshot.

[^32]: Jakarta Mail originator methods: [MimeMessage Javadoc](https://jakarta.ee/specifications/mail/2.1/apidocs/jakarta.mail/jakarta/mail/internet/mimemessage), including `addFrom(Address[])`, `getSender()`, and `setSender(Address)`.

[^33]: Simple Java Mail working-tree originator behavior: `modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageHelper.java:59-63`; `modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageParser.java:490-498`; and `modules/core-module/src/main/java/org/simplejavamail/api/internal/general/MessageHeader.java:12-24`.

[^34]: Angus's factory-selection and fallback path: [SocketFetcher.java at tag 2.0.5](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/core/src/main/java/org/eclipse/angus/mail/util/SocketFetcher.java), especially lines 150-254; the default is also documented in the [SMTP package summary](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html). Simple Java Mail currently installs a custom factory without setting the fallback policy in `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java:217-241`.

[^35]: The inventory source is the tagged [Angus SMTP package documentation](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/package-info.java), cross-checked against the rendered [SMTP package summary](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html), [SMTPMessage](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/SMTPMessage.html), and [SMTPTransport](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/SMTPTransport.html). The 61 count is the number of distinct `mail.smtp.*` property rows in that 2.0.5 source table; `mail.smtp.chunksize` is documented separately in prose.

[^36]: Simple Java Mail credential paths: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/config/OAuth2AccessTokenProvider.java`; `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/util/SmtpAuthenticator.java`; and `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/util/TransportConnectionHelper.java`. Jakarta authentication callbacks and transport connection are defined by the [Jakarta Mail 2.1 specification](https://jakarta.ee/specifications/mail/2.1/jakarta-mail-spec-2.1).

[^37]: Angus socket-factory properties: [SMTP package summary](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html). The mutual-TLS conclusion is an architectural inference from that seam and JSSE's documented initialization with key managers and trust managers: [Java `SSLContext`](https://docs.oracle.com/en/java/javase/11/docs/api/java.base/javax/net/ssl/SSLContext.html). Current SJM factory mapping is in `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java:217-241`.

[^38]: Angus exposes command, return-code, address, host, port, and timeout fields through its SMTP and connection exception types; see the [Angus serialized forms](https://eclipse-ee4j.github.io/angus-mail/docs/api/serialized-form.html) and [SMTP package API](https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html). Current SJM result types are `MailSubmissionReceipt`, `MailRecipientResult`, and `SmtpServerResponse` under `modules/core-module/src/main/java/org/simplejavamail/api/mailer/`. Enhanced-code semantics and registry: [RFC 3463](https://www.rfc-editor.org/info/rfc3463/) and [IANA SMTP Enhanced Status Codes](https://www.iana.org/assignments/smtp-enhanced-status-codes).

[^39]: Jakarta Mail documents `mail.alternates` and `mail.replyallcc` in the [Jakarta Mail internet package](https://jakarta.ee/specifications/mail/2.1/apidocs/jakarta.mail/jakarta/mail/internet/package-summary). Simple Java Mail delegates reply construction to `MimeMessage.reply` in `modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/EmailStartingBuilderImpl.java:135-162`.

[^40]: Jakarta MIME serialization and compatibility properties: [Jakarta Mail internet package](https://jakarta.ee/specifications/mail/2.1/apidocs/jakarta.mail/jakarta/mail/internet/package-summary). Current SJM UTF-8 and generic-header serialization: `modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageHelper.java:50-78,106-166,223-257`; parser exclusions include structured `Sender`, `Resent-*`, `Comments`, `Keywords`, and `Content-MD5` fields in `modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/HeadersToIgnoreWhenParsingExternalEmails.java`.

[^41]: Controls outside the SMTP table were found in Angus 2.0.5 [SocketFetcher.java](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/core/src/main/java/org/eclipse/angus/mail/util/SocketFetcher.java), [SMTPTransport.java](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java), and the [core package property table](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/core/src/main/java/org/eclipse/angus/mail/package-info.java). This report does not treat undocumented provider controls as compatibility commitments.

[^42]: Authentication identity, authorization identity, and the per-message `AUTH` MAIL parameter are distinct in [RFC 4954](https://www.rfc-editor.org/rfc/rfc4954.html).

The related Simple Java Mail robustness work is tracked by [#722](https://github.com/bbottema/simple-java-mail/issues/722), with current decisions in the [architecture topic index](../adr/README.md#find-documentation-by-topic) and ongoing [SMTP conformance work](../plans/smtp-conformance.md). PIPELINING and CHUNKING remain separate under [#699](https://github.com/bbottema/simple-java-mail/issues/699).
