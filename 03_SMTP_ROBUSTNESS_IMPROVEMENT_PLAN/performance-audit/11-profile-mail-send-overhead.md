# Mail-send performance audit

Part of [#722](https://github.com/bbottema/simple-java-mail/issues/722), tracked in
[#749](https://github.com/bbottema/simple-java-mail/issues/749), planned for 10.0.0.
This is an additional investigation, not an eleventh original robustness step.

Status: complete and accepted on 28 September 2026. The [initial audit](initial-audit-results.md),
[content-inspection optimization](inspection-optimization-results.md) and
[provider-discovery follow-up](provider-discovery-results.md) are retained with their measured
results. Keep the current SIZE behavior and all content/security checks. No opt-in, opt-out or
further fine-tuning is justified by a known application workload at this point.

## Accepted follow-up

- Scan finalized body bytes directly, avoiding synchronized per-byte stream calls and the
  redundant `toByteArray()` copy. Keep the serialization buffer private to one inspection.
- Read ordinary unencoded body streams in blocks, preserving line state and first-failure
  behavior. Encoded attachments retain their existing streaming path.
- Preserve every content requirement and error, exact/protected bytes, Session/provider
  ownership, SIZE counting, and all security checks. Add boundary regressions before judging
  the optimization by its timings.
- Remeasure against the committed pre-optimization class in fresh JVMs. Record source and
  compiled-class fingerprints because both runs share the current, uncommitted checkout.
- Check directory-versus-JAR classpath sensitivity before attributing provider-discovery
  overhead to deployed applications. Do not pin a provider or set JVM-wide properties as a fix.
- Revisit SIZE independently with the optimized inspection path. The final decision below
  retains the existing behavior without a public switch.

## Investigation question and final decision

Measure the cost of the current send path before choosing an opt-in, opt-out or no new API.
Separate SIZE measurement from SMTP content inspection, MIME encoding, optional signing,
application size limits, queue/pool waits and SMTP I/O. Look for safe internal savings first.

The investigation found worthwhile internal savings, implemented without relaxing checks.
The remaining SIZE cost does not establish a real application need for another configuration
option. Retain measurement, automatic declaration where supported, early oversize rejection and
receipt facts exactly as implemented in #748, including the existing non-advertising-server
behavior. Do not add an automatic threshold or a broad performance mode. These measurements
provide a baseline if a concrete workload warrants revisiting the decision later.

## Method

- Use the existing SubEthaSMTP test dependency as a loopback-only sink. Discard DATA with a
  bounded buffer, retain counts rather than whole emails, disable TLS/authentication and
  Received-header insertion. This isolates content costs; it does not model Internet latency.
- Reuse one Mailer and warm connections for each scenario. Compare the streaming simple batch
  with eight pooled asynchronous workers and a bounded number of outstanding sends.
- Use small composed emails, binary attachments from memory and a warm local file, exact EML,
  DKIM signing, and an optional application maximum-size check. Include SIZE present/absent.
- Warm each scenario before measuring. Run variants in separate JVMs in rotated order, with
  multiple measured batches; retain raw CSV rows and report spread, not just one best time.
- Record batch wall time, per-email latency distributions, observer queue time, sender and
  fixture CPU, process CPU, sender allocation, GC time, attachment reads and received bytes.
- Profile separately with JDK Flight Recorder. Profiled wall times are not the unprofiled
  benchmark. Keep fixture stacks separate, distinguish samples from exact accounting, and
  note sampling/threshold limits.

## Experimental variants

The runner builds private classpath overlays under the ignored `target` directory. It never
modifies production sources, jars, installed artifacts, Session defaults or the public API.

1. Baseline: the production implementation in the current checkout.
2. No SIZE pass: omit the managed pre-MAIL SIZE measurement/declaration/facts only.
3. No content inspection: use empty body/header requirements for known-safe synthetic ASCII
   content; keep envelope checks and the remainder of negotiation.
4. Both omissions: establish the combined upper bound and expose interactions.
5. After profiling, compare a buffer-based inspection loop with the unchanged baseline. Keep
   every existing byte check and SIZE behavior; isolate this prototype in the same way as the
   bypasses. A speedup here would not establish correctness for arbitrary messages: production
   adoption still needs the existing malformed/binary/line-boundary regression suite.
6. Reference: the inspection class from commit `0d100a2051fe4e35c83a66e8c90d8354801adbe5`,
   before optimization, with all other current classes unchanged. The historical bulk prototype
   also uses that source so its reproduction does not depend on the current loop's shape.

The bypasses are deliberately unsafe for arbitrary input and are **not candidate production
implementations**. They are reachable only in the explicitly launched audit JVM. Normal tests
and library artifacts must never use these overlays. Compare experimental receipt expectations
explicitly; omitting SIZE must produce absent measurements, not a fabricated zero.

## Interpretation and delivery

Report milliseconds per email, CPU separately from waiting, allocation and read amplification.
Do not equate local fixture acceptance with delivery or turn synthetic throughput into a
production promise. Note warm filesystem caching, no TLS/network latency, fixed payload reuse,
JIT/GC noise and simultaneous machine activity.

If substantial costs remain, explain what each potential switch would save and which checks
or receipt facts it would remove. API design needs separate maintainer acceptance and the API
expansion workflow. The internal inspection optimization and loader-scoped provider-discovery
follow-up have been approved. The maintainer accepted no new performance API and authorized
selective semantic commits and issue closure. This completes #749, not the parent plan or
#747's separate conformance suite.

The discovery follow-up caches service factories for submission, lifecycle and probe adapters
per thread-context class loader, preserving per-operation construction and actual-transport
matching. It also removes redundant availability checks when MIME already has a constructed
Session/message. See [provider-discovery results](provider-discovery-results.md) for boundaries,
verification and the matched comparison. Jakarta Mail's own static MIME lookups remain upstream.

The send architecture infographic remains unchanged: inspection remains attempt-local, while
discovery metadata is scoped to the application class loader. The cache synchronizes initial
discovery only, outside adapter construction/calls; it owns no send state, worker or transport
resource and adds no send-lifecycle coordination. This does not expand the public API, so the
API expansion guide's builder, property, Spring and CLI additions do not apply.
