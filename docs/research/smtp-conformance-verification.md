# SMTP conformance verification — 29 September 2026

This records the first hosted verification of [#747](https://github.com/bbottema/simple-java-mail/issues/747), the final step of [#722](https://github.com/bbottema/simple-java-mail/issues/722).
For current commands, fixture versions, scenario coverage and evidence handling, use the [conformance runner guide](../../tools/smtp-conformance/README.md).
These results describe the tested configurations, not SMTP certification or a downstream-delivery guarantee.

## Hosted result

[CircleCI pipeline 1287](https://app.circleci.com/pipelines/github/bbottema/simple-java-mail/1287), job 1333, passed the test-only branch workflow against clean commit
[`6109abf69e6ca7f4d3562ea750e4d1af826b8b02`](https://github.com/bbottema/simple-java-mail/commit/6109abf69e6ca7f4d3562ea750e4d1af826b8b02).
The evidence run is `20260929T082001Z-a69311e2`; its [machine-readable summary](https://output.circle-artifacts.com/output/job/1ef1ec7c-93d8-4c30-b3e6-1ddac78503b2/artifacts/0/artifacts/smtp-conformance/20260929T082001Z-a69311e2/summary.json)
records source/configuration hashes and the actual runtime identities.

| Layer | Result |
| --- | --- |
| Embedded SMTP, fault-boundary, queue, pool, cancellation and observer selection | 481 passed, no skips |
| Postfix and Exim interoperability | 24 passed, no skips; 12 scenarios per server |
| Independent DKIM, S/MIME and OpenPGP checks | 10 passed, including negative controls |
| Python runner self-tests | 8 passed, separate from the 515 conformance results |

The hosted tests used ordinary non-root Maven/Failsafe on Ubuntu with JDK `21.0.12.1+1-1-22.04.4-Ubuntu`, not the optional container JVM.
The server packages were Postfix `3.10.13-0+deb13u1` and Exim `4.98.2-1+deb13u5`.
Independent tools were dkimpy `1.1.8-2`, OpenSSL `3.5.7` and GnuPG `2.4.7`.
Cleanup and artifact publication succeeded. Only the sanitized evidence directory was uploaded; captured EML, fixture private keys and raw SMTP logs were not published.
No deployment or release job ran.

## What the hosted runs caught

1. The OpenPGP fixture depended on checkout line endings. Preparing the fixture in SMTP CRLF form made its bytes portable without weakening signature or exact-byte assertions.
2. Eight competing test JVMs could starve a probe fixture's startup on the two-core CI machine. The conformance runner now uses two forks. Ordinary Maven defaults, scenario concurrency and timeout assertions are unchanged.
3. Postfix's umask prevented the host test user from reading synthetic captures. Captures now have explicit read permissions across fixture users, with a restrictive-umask regression that failed before the fix and passed afterward. Private keys remain separate.

Failed-result evidence also gained bounded project stack-frame locations, without exception messages, credentials or MIME content.
The battle-test corrections changed test infrastructure only, not production Java, public APIs or dependency versions.

## Local compatibility checks

Before the hosted run, clean Java 11 library verification reported 1,573 tests, with one existing Windows symbolic-link assumption skipped.
Clean Java 21 verification reported 1,746 tests, with only the opt-in CLI daemon benchmark skipped.
The applicable runs included Javadocs, classpath/JPMS consumers, Spring and the Boot starter; Java 21 also covered CLI metadata, packaging and daemon/probe processes.

Local embedded run `20260929T065609Z-c8420866` passed all 481 cases. Real-server run `20260929T070420Z-c5f76986` passed all 24 scenarios and ten independent content checks.
Norton intercepted the Windows host's loopback SMTPS certificate, so that real-server run used the documented digest-pinned Linux test JVM on the fixture network.
The same compiled tests ran without weakening certificate verification. The hosted run subsequently verified the ordinary host-JVM path.

These dated checks are not evidence for an eventual release commit. Rerun the current [verification workflow](../MAINTAINER_WORKFLOW.md#6-verify) for that commit.
