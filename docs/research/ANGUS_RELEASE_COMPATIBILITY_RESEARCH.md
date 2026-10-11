# Angus release compatibility research for Simple Java Mail 10+

Status: **parked, awaiting a newer final Angus Mail release**. Initial source/runtime audit: **10 October 2026**.
Last release-status check and document update: **11 October 2026**. Tracking issue: [#758](https://github.com/bbottema/simple-java-mail/issues/758).

This is a living research record. It preserves the tested milestone findings and the questions to revisit when Angus releases.
It does not approve an upgrade or turn the experimental compatibility proposals into accepted SJM changes.

## Activation and ownership

Pick up the tracking issue when a **final, non-SNAPSHOT Angus Mail version newer than SJM's then-effective dependency** is published
in [Maven Central](https://repo.maven.apache.org/maven2/org/eclipse/angus/angus-mail/maven-metadata.xml).
Cross-check the published POM/source/binary artifacts with the [upstream releases](https://github.com/eclipse-ee4j/angus-mail/releases)
and tags. Milestones, betas and release candidates provide early warning; they do not activate implementation work.

Use Maven version ordering and check every release line. A later 2.0.x final still needs attention even after auditing 2.1.0-M1.
Re-evaluation should establish whether each milestone change survived, was backtracked, changed again or does not apply to that release,
and identify additional changes before choosing an SJM mitigation. Keep the issue without a milestone until the final-release review
establishes concrete SJM work and a release target.

GitHub owns current task status. This document owns the dated evidence, compatibility reasoning and re-evaluation record.
Related maintained contracts: [Angus transport ownership](../concurrency/07-angus-transport-abort.md),
[connection failover](../concurrency/10-connection-failover.md), [finalized MIME boundary](../adr/0007-provider-neutral-mime-boundary.md)
and [maintainer workflow](../MAINTAINER_WORKFLOW.md).

## Dated baseline and artifact provenance

The 10 October audit used the unreleased SJM 10 development checkout on `codex/10.0.0`, based on
HEAD `0fba80c1fb7581ed94a3b52f70534071132b42c8`, **including staged, unstaged and nonignored local production/test/POM files**.
The root project label `9.3.5` was not evidence of an SJM 9 baseline.
The same 844 captured executable/POM/test/tool paths were rechecked on 11 October and retain this fingerprint:

```text
a2903b88552211d77a77a837809aaf735de7eb95e3e2f6f53d5534a86aea5799
```

The fingerprint is SHA-256 of sorted repository-relative path/content-SHA256/byte-size JSON. A future checkout or dependency change
requires a fresh baseline; the HEAD alone does not identify the uncommitted code covered by the audit.

| Artifact | Effective SJM baseline | Coordinated milestone graph tested |
| --- | --- | --- |
| org.eclipse.angus:angus-mail | 2.0.5, final | 2.1.0-M1, milestone |
| jakarta.mail:jakarta.mail-api | 2.1.5 | 2.2.0-M1 |
| jakarta.activation:jakarta.activation-api | 2.1.4 | 2.2.0-M1 |
| org.eclipse.angus:angus-activation | 2.0.3 | 2.1.0-M1 |

The captured batch-module override used local `smtp-connection-pool:4.4.0-SNAPSHOT`,
`clustered-object-pool:4.3.0-SNAPSHOT` and `generic-object-pool:2.6.0-SNAPSHOT` binaries. Their exact hashes were retained with test evidence;
they must be resolved and fingerprinted afresh during the final-release review.

| Angus version | Verified tag commit | Published binary SHA-256 | Published source JAR SHA-256 |
| --- | --- | --- | --- |
| 2.0.5 | `a7a4a37844717d3967418b1640456e49153a7e7c` | `b4d8c30d35f455def6c7a05fe595a1e62ea2b80cac3efec1e9ccf4118b23168a` | `1b5608273dfc8dd94b2d6b6e8e07c0724987b2544267cd092a51155b2575dd33` |
| 2.1.0-M1 | `74a38ceeca9b9ee9d23d1b65df793f27815c9c0a` | `5a993bec402bc831428732a598a9f1d152ff7f6445c1a030c8ff7e4c2f073f4f` | `2359ee2846c8dc75d62c4dbc1dd889b39a30996e192b2613aaf44234ed3484a0` |

The full 554-file Angus tag comparison covered all 22 changed files; all 148 Java files in each published source JAR matched its tag.
There were **no Angus runtime Java, resource, service-registration or module-descriptor source changes** in this comparison.
Java-11 compilation, raised dependencies and published bundle metadata still produced compatibility failures.
The affected Jakarta Mail/Activation and Angus Activation sources, published artifacts and matching tags were also examined.
This source conclusion is specific to M1 and must be re-established for a final release.

Upstream evidence: [complete Angus compare](https://github.com/eclipse-ee4j/angus-mail/compare/2.0.5...2.1.0-M1),
[baseline parent POM](https://github.com/eclipse-ee4j/angus-mail/blob/a7a4a37844717d3967418b1640456e49153a7e7c/pom.xml),
[milestone parent POM](https://github.com/eclipse-ee4j/angus-mail/blob/74a38ceeca9b9ee9d23d1b65df793f27815c9c0a/pom.xml).
As checked on 11 October, 2.0.5 remains the latest published final; 2.1.0-M1 is the only published version newer than the pin.

## Findings to revalidate against the final release

All four findings below have runtime/build proof for their stated configurations. They are conditional upgrade regressions;
they do not mean that the current pinned SJM stack has these failures.

| ID | Severity | Tested trigger and consequence | Final-release status |
| --- | --- | --- | --- |
| A1 | P1 | Replace Angus alone in OSGi: new `[2.2,3)` Mail/Activation imports cannot resolve SJM's pinned API bundles; SMTP provider is unavailable. | Awaiting final release |
| A2 | P2 | Replace Angus alone in the SJM reactor: raised transitives conflict with managed pins and fail `RequireUpperBoundDeps`. | Awaiting final release |
| A3 | P2 | Promote Activation to 2.2.0-M1 with an old custom FileTypeMap binary: attachment conversion throws `AbstractMethodError`. | Awaiting final release |
| A4 | P2 | Promote Mail API to 2.2.0-M1 with old custom MIME subclass binaries: SJM EML serialization throws `NoSuchFieldError`. | Awaiting final release |

### A1/A2: coordinate dependency requirements

The actual published M1 OSGi bundle raises Jakarta Mail/Activation package import bounds from `[2.1,3)` to `[2.2,3)`.
Its published Maven graph also raises Mail API 2.1.5 to 2.2.0-M1, Activation API 2.1.4 to 2.2.0-M1 and Angus Activation 2.0.3 to 2.1.0-M1.
These affect the [root dependency management](../../pom.xml), [Angus provider module](../../modules/angus-mail-provider-module/pom.xml)
and [Karaf feature graph](../../modules/karaf-module/pom.xml).

Apache Felix 7.0.5 resolves the pinned graph, rejects Angus-only M1 with pinned APIs, and resolves the coordinated graph on both JDK 11 and 21.
Aligned SJM packaging also passes dependency convergence and upper-bound checks and the classpath/JPMS fixtures on both JDKs.
SJM can change its managed dependency graph together if the final artifacts still require it. Re-derive actual final requirements;
the milestone version table above is evidence, not the dependency set to hardcode for a later release.

Preserve the dependency guards. The local pool SNAPSHOT `requireReleaseDeps` failure is a separate pre-existing release gate.
Skipping the upper-bound rule in Angus-only diagnostic comparisons does not establish a valid production upgrade graph.

### A3: retain legacy FileTypeMap semantics

Activation 2.2.0-M1 adds abstract `getContentType(Path)` and changes `FileDataSource.getContentType()` to invoke it.
A map compiled against 2.1.4 implements File/String methods but lacks that new method.
SJM's `EmailConverter.emailToMimeMessage` attachment path reaches
[MimeMessageHelper](../../modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageHelper.java)
and fails before submission.

For existing File-backed sources, a concrete upstream default preserves the old dispatch:

```java
public String getContentType(Path path) {
    return getContentType(path.toFile());
}
```

Delegating to String would select a different mapper overload. Non-default-filesystem Paths without a File representation need explicit
Path support; the proposed bridge preserves existing File behavior rather than defining semantics for those new cases.

An explicit SJM/application wrapper can compile against the old API, delegate File/String unchanged and add the Path method without
`@Override`. The application supplies that wrapper when configuring the map. An already configured FileDataSource has a private typeMap
and no public getter, so SJM cannot transparently recover and wrap every per-source map. A guessed default MIME type, reflection or JVM-wide
default-map mutation would compromise the intended contract. Prefer upstream binary compatibility for unchanged consumer behavior.

Eighteen JDK 11/21 characterizations compared pinned, published-aligned and experimental-API graphs with per-source, global and wrapped maps.
Published-aligned raw/global old maps fail; the concrete default and explicit wrapper preserve File-overload dispatch,
`application/x-original-file` and all 29 original attachment bytes, including NUL and 0xff.

Sources: [old FileTypeMap](https://github.com/jakartaee/jaf-api/blob/3dad341ad2639a223e31b41f28fb5cdc7c3e1e95/api/src/main/java/jakarta/activation/FileTypeMap.java),
[new FileTypeMap](https://github.com/jakartaee/jaf-api/blob/735b17ddfa1a1a54b1944d1d1ddb2c2ad133d52f/api/src/main/java/jakarta/activation/FileTypeMap.java#L63),
[new FileDataSource call](https://github.com/jakartaee/jaf-api/blob/735b17ddfa1a1a54b1944d1d1ddb2c2ad133d52f/api/src/main/java/jakarta/activation/FileDataSource.java#L127).

### A4: retain the MIME subclass binary contract

Mail API 2.1.5 exposes a protected final streamProvider field in BodyPart and Multipart.
Mail API 2.2.0-M1 removes both fields and introduces provider accessors. Existing MimeBodyPart/MimeMultipart subclasses can still contain
valid old field references, which fail during the public
[EmailConverter.mimeMessageToEMLByteArray](../../modules/simple-java-mail/src/main/java/org/simplejavamail/converter/EmailConverter.java)
serialization path. No built-in SJM subclass was found to read those removed fields.

A compatibility prototype restores deprecated fields with their original name, descriptor, visibility and initializer:

```java
@Deprecated
protected final StreamProvider streamProvider = StreamProvider.provider();
```

This restores old constructor-time global lookup. Upstream must reconcile that compatibility contract with the new parent/Session-aware
accessor design. SJM cannot add fields to a Jakarta superclass for existing third-party bytecode.
Applications can deliberately rebuild extensions with their own cached provider field, or adopt the new getter and review its different
provider-selection semantics. Using the getter alone is not proof of equivalent old behavior.

Sixteen JDK 11/21 runs used subclasses compiled once against 2.1.5: pinned and Angus-only graphs serialize successfully,
published-aligned graphs fail with NoSuchFieldError, and the two-field prototype restores serialization and parsed payload.
All successful fixed-header/fixed-boundary cases produce the same EML SHA-256:
`14f138be83f02cad06298cd7bb8c5bc817b86e503618157a5aa3010bf1ffecab`.

Sources: [old BodyPart field](https://github.com/jakartaee/mail-api/blob/e1873219fe587d5c2b5bb4196e859ae71319a45c/api/src/main/java/jakarta/mail/BodyPart.java#L47),
[new BodyPart](https://github.com/jakartaee/mail-api/blob/c17249e5d7a8d1b9ff3d276a7ed773094c0383b8/api/src/main/java/jakarta/mail/BodyPart.java),
[old Multipart field](https://github.com/jakartaee/mail-api/blob/e1873219fe587d5c2b5bb4196e859ae71319a45c/api/src/main/java/jakarta/mail/Multipart.java#L69),
[new Multipart](https://github.com/jakartaee/mail-api/blob/c17249e5d7a8d1b9ff3d276a7ed773094c0383b8/api/src/main/java/jakarta/mail/Multipart.java).

## Semantics that an eventual SJM change must preserve

M1 provided no source evidence requiring an adapter change. A final release may do so; re-check the actual implementation and callees.
The [capability research](ANGUS_CAPABILITY_PARITY_RESEARCH.md) and
[transport ownership characterization](SMTP_TRANSPORT_OWNERSHIP_CHARACTERIZATION.md) are supporting maps, not substitutes for code review.

- Keep subclass hook ordering, synchronized lifecycle assumptions, constructor-time properties, protected APIs and xtext handling.
  Trace protocolConnect, ehlo/helo, startTLS, mailFrom, sendCommand and readServerResponse, including alternative overloads and pipelining paths.
- Distinguish return-code -1 with exact `[EOF]` from malformed replies; retain physical-setup-only SMTP 421/empty-EOF recognition.
  Authentication/certificate errors, submission failures, pool waits and lease timeouts keep their existing failover/retry boundaries.
- Preserve RCPT occurrence ordering, partial-send/reportSuccess accounting, envelope rewriting, DSN/ENVID/ORCPT/REQUIRETLS and SMTPUTF8,
  including capability refresh after STARTTLS.
- Retain DATA final-dot and BDAT LAST detection before writes, final replies, commit-possible/unknown-outcome classification and pooled reset.
  Check framing, dot stuffing, 8BITMIME conversion, SIZE and exact serialized/protected bytes before changing retry behavior.
- Re-check socket/proxy/factory selection, TLS trust hooks, capture-before-connect, latched abort, failed-wrapper cleanup,
  reconnect/close and write-timeout scheduler ownership. Preserve provider discovery and classpath/JPMS/OSGi behavior.

## Verification record and environmental limits

The 10 October audit compared pinned and Angus-only graphs on real JDK 11 and 21 with 858 selected cases per diagnostic lane;
the full aligned graph also ran 858 selected cases per JDK. These were selected compatibility suites, not an unrestricted release build.
All Windows lanes retain the same positive implicit-TLS PKIX failure. The aligned JDK 11 run additionally had a five-second failover-fixture timeout.

Windows ROOT was propagated to Maven and forked/subprocess JVMs. The fixture combines its own certificate with configured system roots.
Handshake diagnostics show Norton replacing the localhost certificate with one signed by **Norton Web/Mail Shield Self-signed Root**;
that differs from Norton's regular scanning root present in Windows Store. The identical positive TLS test passes on Linux JDK 11/21
with pinned, Angus-only and aligned graphs: six passes with certificate validation intact.
See the [existing Windows verification boundary](726-managed-angus-resource-verification.md#windows-verification-boundary).
Use controlled loopback/container fixtures for comparison, preserving certificate and hostname validation.

The failover timing limitation also reproduces on pinned cold-JVM single-method runs using ControlledTransport, rather than Angus SMTPTransport.
The complete 26-case ConnectionFailoverTest passes on all three graphs on JDK 11. The exact scheduler cause was not established;
retain that limitation separately from candidate regressions.

The three isolated Postfix/Exim runs each pass **34/34 scenarios**: 24 server tests and 10 independent DKIM/S/MIME/OpenPGP checks,
102 passed comparisons total. Packaged classpath/JPMS checks pass on both JDKs, including the coordinated graph with convergence and
upper-bound rules enabled. No external mail server or real email was used.

These results bound confidence; they do not prove every behavior safe. API prototype jars received focused characterizations, not an upstream
TCK/release process. The initial Docker-unavailable conclusion was corrected after verifying Docker and completing the actual matrix.
No dependency upgrade or compatibility patch was applied to the user's checkout during the audit.

Full source caches, commands, JDK/image identities, artifact/runtime hashes, XML results, fixtures, patches and dated reports are retained
outside the repository in `$CODEX_HOME/automation-state/sjm-angus-compatibility/`. The starting records are
`reports/2026-10-10-angus-2.1.0-M1.md`, `reports/2026-10-10-angus-compatibility-remediation.md`,
`runs/2026-10-10/` and `runs/2026-10-10-followup/`. These local operational files are not public repository links.
The tested API proposals remain experimental. Future maintainers without that cache should recreate the old-binary fixtures and commands
against pinned published artifacts rather than treating this summary as new runtime proof.

## Final-release pickup checklist

- [ ] Confirm a newer final Angus artifact is published in Central; enumerate skipped/parallel lines with Maven version ordering.
- [ ] Resolve the maintained SJM 10+ baseline, local changes, effective dependency management/overrides, supported JDK lanes and runtime hashes afresh.
- [ ] Download final POM/source/binary artifacts and matching tag; verify provenance and source/artifact consistency.
- [ ] Review complete pinned-to-final and M1-to-final trees, including all implementation hunks, resources/services, manifests/modules,
      build and dependencies. Trace changed callees back into current SJM; expand the inventory when new assumptions appear.
- [ ] Mark A1-A4 as **still present**, **backtracked**, **changed**, **not applicable** or **not reverified**, with exact versions/source evidence.
      Add newly discovered findings; a 2.0.x final omitting the 2.2 APIs is not proof those APIs were fixed.
- [ ] Reproduce relevant existing suites on pinned/final graphs in disposable copies, using documented JDK lanes and controlled SMTP peers.
      Compile legacy map/MIME extension fixture binaries against old APIs once, then run them against both graphs.
- [ ] Preserve MIME type, overload dispatch, attachment bytes and deterministic EML checks; repeat OSGi/classpath/JPMS and real-MTA/protected-content checks.
      Keep baseline environment/timing failures distinct; record incomplete coverage and concrete remaining work.
- [ ] Choose only mitigations still required by the final artifacts. Verify their behavioral semantics, document any explicit consumer migration,
      and decide whether adoption is safe or should remain deferred. Preserve build guards, trust checks and submission/failover boundaries.
- [ ] Update this document and the tracking issue with dated conclusions, commands/evidence, removed or added risks and the adoption decision.
      Assign a milestone only when there is approved concrete SJM release work.

## Re-evaluation ledger

Keep the initial milestone evidence above intact. Append a dated entry for each actual release review; refresh the finding status table
and link the new evidence. An untested reversal in release notes is not sufficient to retire a reproduced finding.

| Date | Version/check | Outcome | Next action |
| --- | --- | --- | --- |
| 2026-10-10 | Angus 2.1.0-M1 and affected API graphs | Four conditional upgrade incompatibilities reproduced; focused compatibility proposals validated. | Await a final release and re-evaluate actual artifacts. |
| 2026-10-11 | Central versions and upstream releases checked | No newer final; M1 remains the only newer published version. Research registered for deferred maintenance. | Keep release gate closed; retain current pins. |
