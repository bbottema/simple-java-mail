# Angus PIPELINING refresh after #218

Local preparation, 2026-10-18. The user authorized local updates and checks,
with no PRs, pushes or replies. This follows the
[contribution guide](699-angus-contribution-conventions.md) and
[prototype plan](../plans/699-angus-pipelining-prototype.md).

## Base and recovery

Upstream `master` was fetched at
`a1d842dd46debe84fb05f7343151974ba78ca5b4`, the merge of #218. Its only change
from the previous prototype base is the reviewed capability-cache fix, its ten
regression cases, shared test-context helper and release notes.

The reader branch `213-pipelining-prototype` was rebased onto that commit and
its test README extended with an independent reproduction command. Its local
feature commit is `abc829046006366dcc78f07e302dc7d73e49c0ee`.
The engine branch `213-sslengine-prototype` was advanced to the same base and
its working diff reapplied. The engine additions remain intent-to-add so they
appear in the ordinary unstaged diff, matching the user's review workflow.
No feature branch was pushed.

Rebase conflicts concerned the shared release-note insertion and the EHLO
invalidation comment. The upstream cache implementation, regression class and
shared test-context helper are retained. Duplicate capability notes were
removed from the PIPELINING section; the upstream compatibility note remains.

Local recovery material is retained under
`.local/angus-pipelining/upstream-refresh-20261018/`: both original working
diffs, the engine index, the SJM staged/working diffs and copies of the edited
planning files. The original reader commit is retained at
`backup/213-reader-before-upstream-20261018`. The complete original engine
snapshot is retained at `refs/backup/213-sslengine-before-upstream-20261018`
and in its named stash. These are recovery copies, not inputs to the new tests.

The old baseline worktree remains at `a7bd3fe`. A separate current baseline,
`C:\workstation\angus-mail-pipelining-merged-baseline`, uses `a1d842d`.
SJM's unrelated working changes and production dependency pin are preserved.

## Verification

The local `run-refresh.ps1` entry point and logs live under the recovery
directory above. Each variant uses its own worktree and build output. Builds
use the matching reactor and a private Maven cache; nothing is installed over
cached provider artifacts. The command uses `clean verify` with the upstream
release/staging/build-only profiles and a focused test selection, not the full
CI suite:

```text
-Dtest=SMTP*Test,!SMTPIOExceptionTest,IMAPIdleManagerTest
-Dangus.pipelining.contract.starttls=true
```

The existing local reverse-DNS timeout in `SMTPIOExceptionTest` is excluded
explicitly, as in the earlier investigation. It was not rerun or fixed here.
The engine additionally sets `angus.sslengine.prototype=true` and
`angus.sslengine.modes=transport` to run its real SMTPTransport in isolated child
JVMs. Both main variants use the same Java 11 target and dependency graph.

| Variant | JDK | Reported cases | Executed | Skipped | Failures/errors |
| --- | --- | ---: | ---: | ---: | --- |
| Upstream control | 11 | 41 | 41 | 0 | 0 / 0 |
| Reader | 11 | 78 | 71 | 7 | 0 / 0 |
| Reader | 17 | 78 | 71 | 7 | 0 / 0 |
| Reader | 21 | 78 | 71 | 7 | 0 / 0 |
| Engine | 11 | 94 | 87 | 7 | 0 / 0 |
| Engine | 17 | 94 | 87 | 7 | 0 / 0 |
| Engine | 21 | 94 | 87 | 7 | 0 / 0 |

The seven optional skips are the latency benchmark and six additional TLS
stress/pressure methods. The engine's separate 16-case process-isolated matrix
runs explicitly and is included in its reported totals. Counts come from the
saved Surefire XML, with logs and a separate report directory per JDK.

The engine matrix completed six pressure profiles per JDK, covering STARTTLS
and implicit TLS with large MAIL or RCPT replies. All 18 profiles finished two
messages each on their original connection: 36 acknowledged messages, no
recorded stalls and no reconnects. Its ten negative cases per JDK produced the
expected failures: 30 cases covering disconnect, silent peer, lost final
acknowledgment, wrong peer and untrusted certificate. These use the existing
loopback fixture, restricted buffers and frequent TLS 1.3 key updates.

The shared envelope, ordinary TLS and capability-cache tests, and `TestServer`,
match between the two alternatives after line-ending normalization. Their
Transport differences concern socket selection, TLS state reporting and reply
collection. Comparing each refreshed tree with its original snapshot shows no
production method-body changes, only the upstream EHLO comment. No additional
capability invalidation is proposed in either feature diff.

The earlier latency figures in both PR descriptions are retained. This round
does not rerun the benchmark or the full SJM suite. The reader's known TLS stall
and test-side recovery evidence remain in their original reports; rebasing has
not implemented recovery or changed that reader policy.

## Hygiene and patch review

Explicit SpotBugs analysis uses version 4.8.3.1, `spotbugs.skip=false`, High
threshold and XML output. Baseline and reader each report the same two findings:
`DMI_RANDOM_USED_ONLY_ONCE` in `Ntlm`, and `DM_DEFAULT_ENCODING` in
`LogOutputStream`. Baseline scans 205 classes, reader scans 210 and engine scans
214. All three report the same two findings, zero analysis errors and zero
missing classes.

The copyright checker is run separately on core, SMTP and the combined provider,
with year comparison ignored and updating disabled. Baseline, reader and final
engine each fail on the same 7, 5 and 7 pre-existing resource/data findings,
including the two wrong-copyright data files in the combined provider. No
contribution file is flagged after the correction below. The checker is not
green.

The initial engine check additionally flagged `sslengine-keyupdate.security`.
The checker treats contiguous hash comments as one license block; the fixture's
diagnostic explanation needed a blank line after the license. Its header now
matches the hash-comment layout used in the repository. The final check,
`engine-copyright-providers-angus-mail-final.log`, returns to the baseline seven
findings. Only comment formatting changed: the resource name, Java lookup,
license terms and diagnostic key-limit value remain unchanged. No exclusion or
configuration change was added. The test matrix was not repeated for comments.

Fresh compiled output contains `SMTPReplyReader` only in the reader variant,
and `DuplexSocket` / `SMTPEnvelopeExchange` only in the engine variant. Clean
reactor builds avoid source-JAR and generated-class leftovers from the other
alternative. Generated package Javadoc is checked for properties, defaults,
fallbacks, BDAT wording and the protected-hook links; the reader page also
describes its helper thread, and the engine page its factory hook and resources.
Existing Javadoc warnings remain.

| Patch | Changed files | Implementation files | Other content |
| --- | ---: | --- | --- |
| Reader | 12 | SMTPTransport, SMTPReplyReader | Package Javadoc, release notes, three JUnit classes, shared TestServer extension, fixture and running instructions |
| Engine | 18 | SMTPTransport, SMTPEnvelopeExchange, DuplexSocket, SocketFetcher, MailSSLSocketFactory | Package Javadoc, release notes, four JUnit classes, TLS probe, shared TestServer extension, fixture and running instructions |

Both patches pass `git diff --check` and `git apply --check --whitespace=error`
against the clean merged baseline. Neither changes a POM or adds a runtime
dependency. Neither proposes `SMTPCapabilitiesTest` or cache-invalidation edits;
those now belong to upstream. New helper visibility, protocol defaults,
callback ownership, failure handling and cleanup retain the reviewed prototype
contract. No release tag or `@since` version is invented.

Exports under `.local/angus-pipelining/upstream-refresh-20261018/`:

- `reader.patch`, SHA-256 `79171EBEE17004981FED8C4B28C80A0AFFA317713EB3551E64B3B8137CB10E04`.
- `engine.patch`, SHA-256 `DB19AC6C56C6D15DE93CB3477C22F4341F77401412D3A8DA1105F336456B03BC`.

The reader commit retains its one-line semantic message and no sign-off. The
engine diff remains uncommitted and unstaged, with new files intent-to-add.
Research, communication drafts, logs, recovery copies and these exported patches
are outside both Angus diffs. No branch was pushed, and no PR or reply created.

## Review state

Both PR descriptions remain separate Markdown files in `docs/plans`. Their
stale-capability blocker text is removed and both describe the optional, more
isolated sequential implementation clearly. The reader's TLS progress and
recovery-policy questions remain; the engine still needs broader I/O review.
These are discussion prototypes, not release-ready changes.

Research, logs and communication drafts remain outside the Angus patches.
The user-edited progress-comment draft has not been changed or posted.
