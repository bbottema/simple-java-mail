# SMTP conformance evidence

This is the reproducible test suite for [#747](https://github.com/bbottema/simple-java-mail/issues/747), part of [#722](https://github.com/bbottema/simple-java-mail/issues/722).
It exercises the existing send contracts. It adds no Mailer option, transport behavior or performance threshold.

## Run it

From the repository root, with Python 3.10+, Maven and a JDK on PATH:

```shell
python tools/smtp-conformance/run.py --mode embedded
python tools/smtp-conformance/run.py --mode real
python tools/smtp-conformance/run.py --mode all
```

`embedded` needs no Docker or external account. It runs the Wiser/SOCKS tests and the selected scripted, queue, pool, deadline and observer regressions.
These JUnit tests also run in ordinary Maven verification. `real` builds the pinned fixtures and invokes the explicit Maven `smtp-conformance` Failsafe profile.
The runner limits Maven to two test JVMs for the two-core CI machine. Each scenario still exercises its own concurrency; socket and assertion timeouts are unchanged.
It requires Docker Engine 28+ with Linux containers and Compose v2+. CI uses a Linux amd64 VM and JDK 21; Windows Docker Desktop is also supported.
Published library compatibility remains checked separately on JDK 11.

The first real-server run downloads a pinned Debian base and signed packages from the dated Debian snapshot. Maven may download dependencies too.
The tests do not use a live mail account, public DNS key, external SMTP service, Docker-in-Docker or host-wide Docker cleanup.
If a local TLS-inspection product intercepts Maven downloads, use the normal trust-store setup in [DEVELOPMENT.md](../../DEVELOPMENT.md); do not disable certificate validation.

If endpoint security replaces the loopback SMTPS certificate, the certificate-pinned tests should fail, not trust the replacement.
An optional container JVM runs the **same compiled tests** on the fixtures' Docker network, without changing TLS verification:

```shell
python tools/smtp-conformance/run.py --mode real --test-jvm-image eclipse-temurin@sha256:9d8dcf999b0bce2453e913823595a5ff2a4e8e9e5d5241b45280d0ff069818ec
```

This path first packages the reactor on the host, copies only its resolved test JARs, then uses JUnit Console Launcher 1.14.4 (matching the project's JUnit Platform).
It mounts the checkout read-only, records the image identity and actual test JVM, and keeps generated results in the same run directory. No application trust store is changed.
CI uses the ordinary host-Maven/Failsafe path. The optional image must be digest-pinned; update the launcher version when updating the project's JUnit Platform.

Check the runner itself without Docker:

```shell
python -m unittest discover -s tools/smtp-conformance -p 'test_*.py'
```

Exit status is `0` for a complete pass, `1` for an assertion failure or unexpected skip, and `2` for missing infrastructure, missing scenarios, a timeout or cleanup failure.
Missing Docker, failed container startup, zero Maven test results and skipped scenarios are never treated as green skips.

## What each layer proves

| Boundary | Evidence |
| --- | --- |
| Greeting, EHLO/HELO, STARTTLS and AUTH | `SmtpCapabilityProbeCharacterizationTest`, `SmtpConnectionProbeTest`, `SmtpAuthenticationTlsCharacterizationTest` and `SmtpProtocolConformanceTest`: successful and failed setup, post-TLS capabilities, malformed/fragmented replies and dedicated cleanup. |
| MAIL, RCPT, DATA invitation/content/final reply | `SmtpSubmissionFaultBoundaryTest`, `SmtpProtocolConformanceTest`, receipt and pooling tests: explicit rejection versus unknown final acceptance, partial recipients and no invented successful delivery. |
| RSET, QUIT and reuse | Failed reset cannot replace the original rejection; failed QUIT cannot undo acceptance. Existing pool/deadline tests check invalidation, lease cleanup and fresh facts on later attempts. |
| DSN, REQUIRETLS, SMTPUTF8, 8BITMIME and SIZE | Existing exact-command and content-negotiation suites retain both supported and unsupported cases, legacy opt-in, post-TLS changes and connection-local state. |
| Queues, cancellation, deadlines and observers | Existing controlled-interleaving tests run unchanged in the embedded selection. This is bounded functional verification, not a throughput benchmark. |
| MIME and envelope interoperability | Wiser and the two real MTAs share Message-ID/envelope assertions. The real matrix additionally checks attachments, Unicode, partial recipients, pooled concurrent sends, streaming batches, open connections and exact content. |
| Protected content | Newly produced messages pass through each MTA and are independently verified/decrypted by dkimpy, OpenSSL and GnuPG, with negative controls. |

The real matrix contains 12 named parameterized scenarios for each server. The runner checks that all 24 execute, and separately includes ten independent content results.
The embedded selection is explicit in `run.py`; a missing or renamed selected class fails the runner rather than silently shrinking the matrix.

## Fixture choices and limits

- Debian 13, linux/amd64, is pinned by image digest. Signed package inputs come from the 2026-09-28 Debian snapshot.
- Postfix is `3.10.13-0+deb13u1`; Exim is `4.98.2-1+deb13u5`. The evidence also records the actual image IDs and complete installed package lists.
- Each MTA exposes authenticated mandatory STARTTLS, implicit TLS, and a deliberately limited plaintext endpoint. Certificates are generated for the run; the Mailer trusts only that fixture certificate and still checks the hostname. The custom SSL factory is deliberate; physical-abort tests use the managed scripted fixtures, not this factory.
- Both accept only `conformance.test` recipients and route accepted messages to the capture hook. Postfix has no external delivery transport; Exim has no external router. Host bindings are loopback-only, outbound masquerading is disabled, and the independent verifier has no network. The bridge is not `internal: true`, because some Docker engines do not publish host ports from internal-only networks.
- Both reject `reject@conformance.test`. Both advertise a 1 MiB maximum on the normal endpoint. Neither pinned configuration advertises REQUIRETLS: the real suite checks its rejection, while scripted peers check the supported command path.
- The limited endpoint hides SMTPUTF8 and DSN. Other extensions differ by server; it is not presented as an identical capability set.
- Postfix's [pipe transport](https://www.postfix.org/pipe.8.html) and Exim's [pipe transport](https://www.exim.org/exim-html-current/doc/html/spec_html/ch-the_pipe_transport.html) provide the message and envelope directly. CRLF output is selected at those delivery hooks. We do not scrape log text to recover messages or rewrite captures to make signatures pass.
- A capture is **one recipient delivery**. Queue IDs identify accepted submissions. The peer address/port identifies the connection that submitted that message. Six deliveries need not mean six submissions or six connections; the concurrent and mixed-recipient tests assert those distinctions.
- Synthetic capture files are readable by the host test user, independently of the MTA's umask. Fixture private keys remain separate and are never published with the evidence.
- Real MTAs may add transport headers. Complete exact wire-byte assertions remain in the scripted peer tests; real-server checks compare the exact body and independently verify protected content.
- DKIM reuses the project's existing DNS-free `supersecret-testing-domain.com` signing fixture identity. dkimpy receives a local TXT record derived from the run's public key. This tests the emitted signature, not public DNS publication or DNSSEC.
- Signed-content checks reject changed bytes. S/MIME encryption-only uses a wrong-key negative control; encryption alone is not claimed to authenticate content. The OpenPGP encrypted fixture is also signed and verifies the expected signer fingerprint.

OpenSMTPD, live accounts, PIPELINING/CHUNKING, benchmarks and automatic retry are outside this matrix.
Passing this suite is evidence for these configurations, not SMTP certification, proof of every provider's behavior, or a downstream-delivery guarantee.

## Results and cleanup

Each run gets one directory under `target/smtp-conformance/<UTC-time>-<id>/` and one uniquely named Compose project.
The runner bounds startup, Maven, crypto checks and teardown. Its `finally` path removes only that project's containers/network/volumes.
It does not stop unrelated services. On interruption it also terminates its own Maven process tree.
An uncatchable process or machine termination can prevent cleanup; recover using the exact project name from that run, never `docker system prune`.

Only the `evidence/` directory is suitable for CI publication:

- `summary.json`: schema version, source commit/dirty flag/source-tree hash, configuration hashes, actual host/Maven/container versions and named outcomes;
- `junit.xml`: sanitized scenario results and project stack-frame source locations, without raw test output, environment properties or exception messages;
- `summary.md`: a short human-readable result table;
- `runtime.properties`, probe reports and `content-verification.json`: the loaded JDK, Angus and crypto versions, capabilities and independent checks.

Raw Maven/server logs, captured EML files, delivery manifests and generated fixture private keys stay outside `evidence/`.
They contain synthetic data only, but are not release attachments. Retain or remove old run directories deliberately; no automatic deletion of another run's evidence occurs.
Dirty-tree evidence identifies the local source hash; release evidence should come from the exact clean commit being released.

## CircleCI and release handling

The machine-executor job runs alongside the ordinary build on `master`. Both are required by every release approval.
On a development branch, start a pipeline with `run-smtp-conformance: true`; this enables the test-only workflow, not deployment.
CI stores only sanitized evidence, including failed-run evidence. See [the maintainer workflow](../../MAINTAINER_WORKFLOW.md#6-verify).

Changing CI configuration locally is not proof that the CircleCI job ran. Before closing #747, run the branch job after the separately approved push and record its result.
