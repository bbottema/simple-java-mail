# Standalone Angus SMTP capability-cache fix

Published, 2026-10-16. The user approved the separate bug issue, PR and update
before presenting the two PIPELINING alternatives. The posted texts match the
approved local drafts, with their issue and PR placeholders resolved:

- [New bug issue](../plans/699-angus-stale-capabilities-issue-draft.md)
- [Fix PR](../plans/699-angus-stale-capabilities-pr-draft.md)
- [Comment on #213](../plans/699-angus-stale-capabilities-213-comment-draft.md)

Published links: [bug #217](https://github.com/eclipse-ee4j/angus-mail/issues/217),
[PR #218](https://github.com/eclipse-ee4j/angus-mail/pull/218), and
[update on #213](https://github.com/eclipse-ee4j/angus-mail/issues/213#issuecomment-6212651191).
Commit: `8f9657c7364bd904713003261f00caf5eba368a9`,
`fix(smtp): discard stale EHLO capabilities`. One commit, six changed files,
targeting upstream `master` from `bbottema:fix-smtp-capability-cache`.

## Isolation and scope

Worktree: `C:\workstation\angus-mail-smtp-capabilities`.
Branch: `fix-smtp-capability-cache`.
Base: upstream `master`, `a7bd3fed964d9c9bc2981d4b2c9867358453c571`,
confirmed by fetching upstream on 2026-10-16.

The production change is five `extMap = null` assignments in canonical
`SMTPTransport`: before connecting, in connection cleanup, before HELO and
EHLO, and after STARTTLS receives 220. The latter deliberately does not clear
capabilities when STARTTLS is rejected with 454. There are no PIPELINING,
concurrency, socket-implementation, dependency or public-API changes.

The existing policy of continuing after a failed post-TLS EHLO is unchanged.
The fix removes the old capabilities rather than making that policy stricter.
After a successful TLS handshake, discarding pre-TLS knowledge is required by
[RFC 3207 section 4.2](https://www.rfc-editor.org/rfc/rfc3207.html#section-4.2).

Both prototype worktrees are untouched and still contain their original copy
of this fix. No rebase or removal has been performed. Once the standalone fix's
upstream direction is clear, adjust their bases/diffs so
reviewers do not need to review this bug fix again in both alternatives.

## Regression evidence

`SMTPCapabilitiesTest` adds ten JUnit 4 tests using Angus's `SMTPHandler` and
`TestServer`, loopback sockets and actual STARTTLS. No mocking dependencies.

| Test case | Unmodified master, JDK 11 | Fixed, JDK 11/17/21 |
| --- | --- | --- |
| Failed EHLO discards capabilities | Fails: old SIZE remains | Pass |
| Successful EHLO replaces capabilities | Pass | Pass |
| HELO discards capabilities | Fails: old SIZE remains | Pass |
| Close discards capabilities | Fails: old SIZE remains | Pass |
| Reconnect with HELO fallback | Error: unwanted AUTH | Pass |
| Reconnect with EHLO disabled | Error: unwanted AUTH | Pass |
| Accepted STARTTLS discards capabilities before another EHLO | Fails: old SIZE remains | Pass |
| Failed post-TLS EHLO does not use old AUTH | Error: unwanted AUTH | Pass |
| Successful post-TLS EHLO replaces capabilities | Pass | Pass |
| Rejected STARTTLS preserves capabilities | Pass | Pass |

The baseline therefore has four assertion failures and three authentication
errors, with three passing controls. The authentication errors are the
reproduced bug: the fixture rejects AUTH because it was not advertised in the
current state.

With the fix, each of the three JDK lanes passes 17 tests with no failures,
errors or skips: the ten new cases plus `SMTPAuthDebugTest` (3), `SMTPBdatTest`
(2), `SMTPCloseTest` (1) and `SMTPConnectFailureTest` (1). These are focused
checks, not a claim that the full upstream CI suite passed.

A further JDK 11 run passes 22 tests with no failures, errors or skips: the ten
new cases plus `SMTPSaslLoginTest` (6), `SMTPUknownCodeTest` (1, upstream's
spelling), `SMTPUtf8Test` (4) and `SMTPWriteTimeoutTest` (1). The two fixed JDK 11
selections therefore cover 29 distinct tests; the 17-test selection is the one
also run on JDK 17 and 21.

Explicit SpotBugs 4.8.3.1 analysis ran with `spotbugs.skip=false`, High threshold
and JDK 11, against all 205 combined-provider production classes. Baseline and
fixed builds have the same two findings, including identical instance hashes:
`DMI_RANDOM_USED_ONLY_ONCE` in `Ntlm.generateType3Msg` and `DM_DEFAULT_ENCODING`
in `LogOutputStream.logBuf`. Neither touches this change. There are no analysis
errors or missing classes in the fixed report. This is no new finding, not
zero total findings. The earlier JDK 17 report was not used as a matched control.

Raw logs are under the ignored SJM `.local/angus-pipelining` directory:

- `capabilities-before-jdk11.log`
- `capabilities-fixed-jdk11.log`
- `capabilities-fixed-jdk17.log`
- `capabilities-fixed-jdk21.log`
- `capabilities-adjacent-jdk11.log`
- `capabilities-spotbugs-baseline-jdk11.log`
- `capabilities-spotbugs-jdk11.log`
- `capabilities-publication-jdk11.log`

Immediately before publication, the ten capability tests were rerun on JDK 11
against the final source and passed with no failures, errors or skips. Only the
standalone fix branch was pushed; both prototype branches remain unpublished.

## Reproduction

From the standalone Angus worktree, set `JAVA_HOME` to the desired JDK and run
the matching reactor so the combined provider uses these sources, not cached
prototype source JARs:

```powershell
mvn -B -pl providers/angus-mail -am '-Poss-release,staging,build-only' `
  '-Dmaven.repo.local=C:/workstation/simple-java-mail/.local/angus-pipelining/m2' `
  '-Dgpg.skip=true' '-Denforcer.skip' `
  '-Dtest=SMTPCapabilitiesTest,SMTPAuthDebugTest,SMTPBdatTest,SMTPCloseTest,SMTPConnectFailureTest' `
  '-Dsurefire.failIfNoSpecifiedTests=false' package
```

Maven 3.9.10 was used with JDK 11.0.16.1, Microsoft JDK 17.0.20+8 and
JDK 21.0.12.1+1. The local Maven process used
`MAVEN_OPTS=-Djavax.net.ssl.trustStoreType=WINDOWS-ROOT` for this workstation's
repository trust setup; the original runs used their own explicit fixture trust
store. The review follow-up below records the later shared-fixture setup.

The baseline run used the same new fixture before applying the production
change. The executed matrix selector also contained two nonexistent test names,
which contributed no tests; the counts and corrected reproduction selector
above deliberately list only tests that actually ran.

For the additional JDK 11 selection, use the same command with
`-Dtest=SMTPCapabilitiesTest,SMTPSaslLoginTest,SMTPUknownCodeTest,SMTPUtf8Test,SMTPWriteTimeoutTest`.

For SpotBugs after building each checkout, run:

```powershell
mvn -B -pl providers/angus-mail `
  '-Dmaven.repo.local=C:/workstation/simple-java-mail/.local/angus-pipelining/m2' `
  '-Dspotbugs.skip=false' '-Dspotbugs.threshold=High' `
  '-Dspotbugs.xmlOutput=true' '-Denforcer.skip' `
  com.github.spotbugs:spotbugs-maven-plugin:4.8.3.1:spotbugs
```

Compare `providers/angus-mail/target/spotbugsXml.xml` from both checkouts.

## Original fixture and patch hygiene

The new `capabilities.jks` is a public localhost test fixture, not a deployment
credential. Its known store/key password is `changeit`. It was generated with
JDK 11 keytool using RSA 2048, alias `capabilities-test`,
`CN=localhost, O=Angus Mail test fixture`, SAN `localhost`/`127.0.0.1`, start date
2024-01-01 and validity 7305 days. Both peers use the fixture's explicit trust
configuration; server identity checking remains enabled. This avoids depending
on the unrelated expired upstream test certificate. The binary is excluded
from the copyright-header check, not from Git.

The patch also contains ordinary CHANGES/COMPAT notes. Its new test preserves
the upstream license/SPDX format and has accurate attribution. Protocol waits
and socket cleanup are bounded. Research, raw logs and communication drafts
remain outside Angus. Neither prototype, SJM's dependency pin, nor existing
unrelated SJM changes are altered by this standalone work.

Initial publication review: the six-file Angus patch contained only the production fix,
its JUnit fixture and test key, the key's copyright exclusion, and the two
plain-text release/compatibility notes. `git diff --check` passes; the new test
also has no trailing whitespace. No public/protected API or Javadoc declaration
changed. The full repository copyright check and full CI matrix are not claimed
as run for this standalone patch. The original `SMTPTransport` copyright header
is unchanged; the new test uses the user's approved
`Copyright (c) 2026 Benny Bottema (ai-assisted). All rights reserved.` header.
The standalone fix is committed and published as linked above. Research and
communication files remain in SJM, outside the Angus PR.

## Review follow-up, 2026-10-18

Following [Jason Mehrens's test-setup suggestion](https://github.com/eclipse-ee4j/angus-mail/pull/218#discussion_r4304441659),
commit `69a45b6100327908de051282eae195db9ff2df5f` reuses
`TestServer.createSSLContext()` and the existing test keystore. The SMTP client
trusts `localhost` and disables server-identity checking for this test fixture.
The separate `capabilities.jks` and its copyright exclusion are removed. This
supersedes the dedicated-fixture choice above; the production fix, real STARTTLS
exchange and all ten capability regression cases are unchanged.

Clean reactor builds pass on JDK 11, 17 and 21 with 29 focused tests each: the
17-test reproduction selection above plus `IMAPIdleManagerTest` (12 tests),
which exercises the existing shared TLS setup. Logs are
`.local/angus-pipelining/capabilities-shared-tls-jdk{11,17,21}.log`.
The follow-up was pushed to PR #218; its net patch contains five files.
The [approved reply](https://github.com/eclipse-ee4j/angus-mail/pull/218#discussion_r4305541189)
was posted, Jason Mehrens approved the change, and #218 was merged on 2026-10-18
as `a1d842dd46debe84fb05f7343151974ba78ca5b4`, closing #217.
Both PIPELINING prototypes have since been brought onto that upstream base;
the [refresh report](699-angus-upstream-refresh.md) records the separate work.
