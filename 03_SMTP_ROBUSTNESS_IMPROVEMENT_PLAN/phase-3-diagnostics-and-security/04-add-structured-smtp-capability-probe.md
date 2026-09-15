# Step 4: Add a structured SMTP capability probe

- Status: Complete and accepted on 15 September 2026, including Java/CLI integration, the third-party adapter fixture and holistic review. Unreleased 10.0.0 work; the separate authentication-policy step remains open.
- Depends on: Existing configuration diagnostics; coordinate the capability model with Steps 1 and 5 through 9
- Child issue: [#733](https://github.com/bbottema/simple-java-mail/issues/733), under [#722](https://github.com/bbottema/simple-java-mail/issues/722), milestone 10.0.0
- Classification: `enhancement`, never also `major feature`; provider/report characterization is complete, so remove `needs-research` at closure
- Release sensitivity: Additive; suitable for 10.0.0 or a later 10.x release after its provider-neutral shape is reviewed
- Primary modules: `core-module`, `simple-java-mail`, `angus-mail-provider-module`, website diagnostics

## Goal

The [accepted explicit execution views and review bookmark](04a-explicit-mailer-execution-views.md) establish the entry-point shape for this probe. The migration is delivered independently under #734; probe implementation and review remain under #733.

Give developers a read-only diagnostic that explains what an SMTP connection negotiated instead of reducing connection testing to success or failure.

The existing `testConnection()` API remains useful as a simple health check. The probe should complement it with an immutable, redacted report that can answer why a feature or security policy is available in one environment but not another.

## Work started

The [provider characterization](04-provider-characterization.md) records the supported Angus 2.0.5 hooks and 13 executable local SMTP/TLS cases. The implementation now applies those findings through the public Mailer API, rather than stopping at provider experiments. In particular, failed post-TLS EHLO cannot expose Angus's stale pre-TLS extension map as current capabilities or continue into AUTH using that map.

Implemented and accepted:

- `mailer.sync().probeConnection()` and `mailer.async().probeConnection()` default to no authentication; their boolean overloads opt in to configured password/Authenticator/OAuth2 credentials for that invocation only.
- Immutable `SmtpConnectionReport`, `SmtpCapabilities` and `SmtpTlsDetails` retain separate before/after snapshots, configured endpoint, actual authentication, safe phase-specific failures and optional TLS metadata. The configured transport strategy stays on `Mailer.getTransportStrategy()`, not on the report. `SmtpConnectionPhase` classifies the first failure; it is not a send-status enum.
- `SmtpConnectionProbeAdapter` is a narrow optional SPI. Known Angus SMTP/SMTPS providers use a dedicated inspection transport; arbitrary providers and CustomMailer are explicitly unsupported unless an appropriate adapter is supplied.
- Original Session properties/provider registration, caller security hooks, proxy routing and pool leases are preserved. Default no-auth probes do not call authenticators or token providers. The probe uses existing provider timeouts, not total mail-send deadlines, and has no observer/cancellation-pipeline integration.
- Public report tests, real Mailer/local SMTP/TLS tests, provider-neutral classpath/JPMS compilation and managed-provider JPMS discovery cover the implementation. The manual `SmtpConnectionProbeDemoApp` starts a loopback peer by default and prints a real report without credentials or mail.
- README, release entries, mechanisms catalogue and website Diagnostics explain usage and limits. The send infographic was reviewed and remains unchanged because this path is outside email sending.

The CLI now exposes `probe`, with a per-invocation `--authenticate` flag and the same safe report on stdout. Successful probes return 0, failed/unsupported reports return 3, and invalid arguments return 2. One-shot and daemon execution share the synchronous probe and request-owned output; authentication does not affect Mailer reuse identity. Builder options and their generated help still follow the normal metadata workflow. The process tests also found that logging-only regular builders discarded their SMTP endpoint and credentials; supplied settings are now retained for explicit probes without requiring a host for logging-only sending.

CLI help and documentation distinguish dedicated-probe authentication from ordinary send-pool warm-up: a nonzero pool core size can open and authenticate pooled connections when the Mailer is built. Core size 0 (the default), including per-cluster overrides, avoids that warm-up; probing does not stop pools already running in a daemon. Existing pool initialization and ownership are unchanged.

The third-party adapter fixture now packages a separate public-SPI implementation and synthetic Jakarta Mail provider into a test-only JAR. Maven verify runs that JAR on both the classpath and module path with Angus absent. It covers successful connections with unavailable or partial metadata, explicit authentication, caller Session isolation, dedicated cleanup, first-failure preservation, recovery and unsupported-provider behavior in both execution views. It does not claim interoperability with an untested real third-party provider.

The Java/CLI production diffs, logging-only correction and final holistic result were accepted on 15 September 2026. Existing configuration properties and Spring Mailers already configure the Java probe; there is no new persistent setting or Email-governance field because authentication is an invocation choice. The following authentication-policy step remains separate.

## Information to model

Where the provider exposes it safely, report:

- endpoint and transport protocol;
- server greeting;
- pre-TLS and post-TLS EHLO capabilities, clearly separated;
- STARTTLS availability and whether an upgrade occurred;
- TLS protocol, cipher, public peer certificate identities, configured identity checking and an observed custom-verifier result when available; never infer a single verified-trust verdict;
- advertised authentication mechanisms and the selected mechanism when authentication is requested;
- SIZE support and advertised maximum;
- DSN, SMTPUTF8, 8BITMIME, PIPELINING, CHUNKING, BINARYMIME, and REQUIRETLS capability flags;
- the terminal connection/probe phase and sanitized failure when probing fails;
- timestamps or phase durations useful for diagnosis.

Do not expose credentials, authentication exchanges, certificate private material, message content, or a raw transcript by default.

## Accepted probe semantics

1. Stop after EHLO/STARTTLS by default; authentication is explicit via `mailer.sync().probeConnection(true)`, `mailer.async().probeConnection(true)`, or CLI `probe --authenticate`.
2. Put the entry points on `Mailer` to reuse the endpoint and policy being diagnosed.
3. Keep `testConnection()` unchanged, without delegation to the new probe.
4. Explicit probes connect even in logging-only mode. CustomMailer and unsupported providers return an unsupported report without connecting. Copy Session properties and preserve caller-owned hooks rather than changing their policy.
5. Open and close one fresh dedicated transport. Do not borrow or mutate a pooled send transport. Report only the configured endpoint, not every cluster member.
6. Advertised support is not feature execution, message acceptance or delivery. TLS metadata is not a replacement trust decision.
7. Retain pre-TLS capabilities only as history after an upgrade. Effective capabilities require successful discovery on the final connection; rejected encrypted EHLO cannot fall back to stale plaintext advertisements.
8. Probes are serialized per Mailer with the existing connection-test/shutdown monitor; async admission uses the configured executor. Futures complete after cleanup attempts. Cancelling one does not abort socket I/O.

## Tests first

1. Script different pre-TLS and post-TLS EHLO responses and prove only the post-TLS snapshot drives effective capabilities.
2. Cover multiline replies, duplicate extensions, parameters, mixed case, unknown extensions, and malformed capability lines.
3. Cover STARTTLS unavailable, refused, successfully negotiated, certificate-untrusted, and identity-mismatch cases.
4. Cover authenticated and non-authenticated probe modes without recording credentials or challenges.
5. Cover SIZE with and without a maximum, all capabilities named above, and no-EHLO fallback behavior.
6. Prove deterministic immutable output and safe `toString()` escaping/redaction.
7. Cover caller-owned Sessions, custom adapters with partial detail, custom mailers, proxies, and pooled Mailers.
8. Verify sync/async behavior and cleanup ordering.
9. Compile the public API on the classpath and module path; exclude callback-only or object-only values from CLI where necessary.
10. Add a manual demo using the same style as the existing connection and configuration-diagnostics demos.

## Documentation and release work

- Add a Diagnostics section showing a successful and a policy-mismatch report.
- Compare `testConnection()`, validation, rehearsal, configuration provenance, and the SMTP probe directly.
- Explain that capabilities can differ after STARTTLS and across endpoints in a cluster.
- Make clear that a successful probe does not send an email and does not prove later delivery.

## Acceptance criteria

- [x] The [shared architecture overview](../../docs/concurrency/inside-a-mail-send.md#phase-completion-check) has a recorded updated-or-unchanged review, including capability discovery and provider boundaries.
- [x] One immutable report exposes safely obtainable provider facts without raw provider types; unavailable TLS metadata is explicit.
- [x] Pre-TLS and post-TLS capabilities cannot be confused.
- [x] Probe output is safe for ordinary logs and contains no authentication exchange or message secrets.
- [x] `testConnection()` retains its existing simple contract.
- [x] Dedicated connection cleanup and independence from pooled send leases are covered by local integration tests.
- [x] Partial-capability custom adapters degrade explicitly rather than fabricating support.
- [x] The website and demo explain the difference between configured policy and negotiated capability.
- [x] CLI command integration and one-shot/daemon verification are complete.
- [x] The production diffs and final holistic review are accepted before committing or closing #733.

## Verification of the Java slice (11 September 2026)

- The normal non-live Java 21 reactor verification, including the CLI profile, passed: 954 tests, no failures/errors, one excluded benchmark. The final local probe corrections were then reverified with the focused suite below.
- Final focused verification on Java 11 and Java 21: 67 cases each (13 report-model, 13 provider-characterization, 41 actual Mailer probe cases), all passing. Both runs also package the public API/Javadocs and compile/run the provider-neutral classpath and JPMS consumers plus the managed-Angus JPMS probe.
- Ran the default `SmtpConnectionProbeDemoApp` against its own loopback peer: successful report, DSN advertised, SIZE 10485760 bytes, no authentication, no warning, no email submitted.
- Website check and clean build passed. Internal-link verification reports five broken links in the untouched Journal content, none in the changed Diagnostics page. Journal files remain outside this work.
- `mvn license:remove -Ppublish-cli` passed. No generated headers remain in the changed Java files. Four unrelated test files already have headers in HEAD and fall outside the plugin's main-source include; they were left untouched.
- The root and website architecture PNG hashes still match. The diagram remains unchanged for this dedicated, non-send path.

## Coding-guide audit of the Java slice

The public Mailer contract, report/capability/TLS value types, failure-phase enum, probe SPI, orchestration, provider adapter, inspection transport, TLS observer and diagnostic formatters were checked against the root guide. The new methods in `MailerImpl` defer to the interface Javadocs. The implementation follows a direct select-provider, isolate-Session, connect, record, close path rather than introducing configurable steps or another execution-control framework.

The SPI is the real core/provider-module boundary. `AngusProbeTransport` uses inheritance only for Angus's supported observation hooks; `AngusProbeTlsObserver` keeps certificate extraction and the caller-verifier contract separate from SMTP state. Proxy cleanup-report assembly was extracted to keep the main flow readable. The public value objects copy their collections and reconstruct their display guarantees on Java deserialization; their getters do not resolve configuration defaults.

Review corrected phase attribution when no AUTH mechanisms overlap, exact EHLO line-limit handling and its terminal newline, implicit-TLS HELO fallback, and configured endpoint defaults. The negative TLS tests retain real trust/endpoint checks; missing metadata is not repaired by weakening them. Demo and consumer-probe changes remain test sources and are not automatic live-email demos.

## Verification of the CLI slice (15 September 2026)

- Java 11 non-live library `clean verify` passed: 917 cases, no failures/errors, one existing `MiscUtilTest` case skipped. This includes Spring/starter checks and provider-neutral classpath/JPMS consumers; Javadoc generation was skipped in this lane.
- Full Java 21 non-live `clean verify -Ppublish-cli` passed: 1,042 cases, no failures/errors, the opt-in daemon benchmark skipped. Javadocs, consumer verification, CLI metadata generation and standalone packaging passed. The first run encountered a socket error during teardown of the unchanged pooled-recipient test; its send-result assertions had passed, and the full rerun passed without modifying that test.
- After the final help clarification, all 22 CLI probe cases passed again with standalone packaging and metadata regeneration. They cover real one-shot/daemon SMTP conversations, authentication failure/recovery, concurrent daemon requests, report status/output, partial failures, cleanup and bounded output. Four additional regular-builder cases cover logging-only probes across sync/async and authentication choices.
- The assembled `sjm.bat` successfully served probe help, authentication help and the updated generated logging-only builder help from the packaged resources.
- Website checks and clean build passed. Internal-link verification reports 12 broken links, all in unrelated Journal content; no CLI or Diagnostics links failed. Journal work remains untouched.
- `mvn license:remove -Ppublish-cli` passed; no generated license headers remain in changed Java sources. Diff whitespace checks passed. No SpotBugs, benchmarks or live-email demos were run.
- This CLI checkpoint preceded the adapter fixture and holistic review recorded below. The complete story was accepted on 15 September 2026.

## CLI coding-guide audit (15 September 2026)

Reviewed the command enum/model, parser, command/help construction, execution dispatcher, CLI entry point, regular Mailer builder and its public logging-only contract against the root API expansion workflow and coding guide. The implementation adds one invocation flag and one direct probe path, reusing existing request-owned output and Mailer leases. It adds no configuration default, Spring property, daemon protocol field, pool abstraction or exception-based report conversion. Generated builder documentation remains the source of truth; the invocation-only authentication help is explicit. Existing mixed formatting outside touched lines was left alone.

## Holistic and coding-guide review (15 September 2026)

Revisited the public contracts and reports, provider selection and Session isolation, dedicated transport/TLS observation, Mailer ownership and async admission, CLI dispatch/output, and logging-only builder correction. No further blocking runtime defects were identified. The process remains a direct select, isolate, connect, record, close flow; probing does not acquire a send lease or introduce another send state machine. The existing Mailer monitor still owns probe/shutdown coordination, and missing provider facts remain distinct from unsuccessful connection setup.

The external fixture follows the same separation: one consumer describes the assertions, one adapter owns report construction and cleanup, and one synthetic transport supplies controlled connection results. Its small StreamProvider exists only because Jakarta Mail requires one to create a Session; MIME operations deliberately fail rather than importing Angus to make the fixture run. Both registrations and all fixture classes are test-only. The Maven target bounds each fork to 30 seconds, and the fixture also bounds its async waits. No generic test framework, production fallback or new runtime dependency was added.

The SPI Javadoc and website now state that unavailable EHLO data is an absent snapshot, not an empty list of advertised extensions. The SPI also makes the adapter's responsibility clear: report escaping cannot identify arbitrary secrets, so raw provider exceptions and AUTH exchanges must stay out. This is a documentation clarification, not a change to the public method signatures. The concurrency ledger records the unchanged architecture image and provider boundary.

## Final verification of the probe work (15 September 2026)

- Java 11 non-live library `clean verify` passed: 917 cases, no failures/errors, one existing `MiscUtilTest` case skipped. Spring/starter and provider-neutral consumers are included; Javadoc generation was skipped in this lane.
- Full Java 21 non-live `clean verify -Ppublish-cli` passed: 1,042 cases, no failures/errors, the opt-in daemon benchmark skipped. Javadocs, metadata generation and standalone CLI packaging passed, including all 22 CLI probe cases.
- The external adapter fixture ran successfully on the classpath and module path in both JDK lanes, with Angus absent. These separate forked checks are additional to the JUnit counts above. The fixture is absent from both the library JAR and its sources JAR.
- Website checks and clean build passed. Internal-link verification reports 12 broken links, all in unrelated Journal content; no changed Diagnostics or CLI links failed. Journal work remains untouched.
- Delivery check: the selectively committed website was also exported into a clean directory and checked/built independently of the dirty checkout. Its link check reports only five pre-existing Journal links; all probe links pass. Only the four probe-related documentation pages are included in the website commit.
- `mvn license:remove -Ppublish-cli` passed, with no license headers remaining in the 41 changed Java files. Changed-path whitespace checks passed. No SpotBugs, benchmarks or live-email demos were run.
- The root and website architecture PNGs remain unchanged and identical. The accepted probe changes are delivered in separate semantic commits; unrelated staged research/delivery-index files and website Journal work stay out.

## Stop condition

If the needed Angus state is unavailable through supported APIs, stop and choose between a narrow adapter-owned subclass, an upstream provider seam, or a smaller truthful report. Do not use fragile reflection or parse debug-log text as the public diagnostic source.
