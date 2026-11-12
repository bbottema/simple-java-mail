# Pooled OAuth protocol username correction

Retired after delivery. SMTP Connection Pool 4.4.1 is published; SJM #966 is delivered on codex/10.0.0, closed/completed and Done. Main source/documentation is a0dd339b62eb8a9824798c2f8063940ff49ef1ed; website is86ce310498495ee9cadc408bcb20f2c4351d1867. ADR0038 owns the durable decision and the retained evidence directory holds the execution records.

Automatic approval review rejected deletion of this old plan and the task-owned rehearsal-repository/tools leaves, stating only 'blocked by policy'. No deletion ran and no bypass was attempted. Their exact registrations and individual manual commands remain in cleanup-api-review-20261021.ps1. All three task worktrees are retired; the parent task directory and retained evidence are excluded from cleanup. The work packet below is historical context, not pending implementation or publication.

Accepted on 5 November 2026. The maintainer approved finding 1's upstream correction, then explicitly requested a subagent to carry the review workflow to completion while finding 2 is discussed.

Authorization includes implementing and verifying the SMTP Connection Pool correction, publishing its compatible patch through the repository's release workflow, adopting the published dependency in SJM, synchronizing applicable documentation, selective delivery to existing branches, and completing the owning issues and project/milestone bookkeeping after lead acceptance. No SJM release, unrelated dependency update, history rewrite, or change to finding 2 is authorized. Routine implementation choices inside this contract do not require another design approval.

Baselines: SJM `a4c28a8345190c20f31b1d190545c59d5f87db76` on `codex/10.0.0`; website `b6699d55dab4d8173a280a8a0a98799cac90aa51` on `codex/10.0.0-content`; SMTP Connection Pool `a097c2a4377729056c195c1fa2daa2c290b9424a`, released 4.4.0, on `master`. Recheck live remote and issue state before mutation. Preserve the root/nested dirty work, staged Angus research and all other linked worktrees.

Owning issues are [SMTP Connection Pool #38](https://github.com/simple-java-mail/smtp-connection-pool/issues/38), targeting compatible patch 4.4.1, and [SJM #966](https://github.com/bbottema/simple-java-mail/issues/966), targeting 10.0.0. Published pool 4.4.0 and the fixed-token path in published SJM 9.3.9 are affected. The numbers in this review are finding numbers, not GitHub issue numbers.

The lead has accepted the actual upstream correction and release preparation for publication. The fix delegates username resolution to the selected provider with `connect(null, currentToken)`; it adds no live-account-rotation promise for already-registered Sessions. Upstream implementation `132a5f5` and release preparation `3e4fce6` are pushed; CircleCI owns the patch release, after which the worker must adopt and review the published artifact in SJM. Finding 2's proposed explicit authentication resolver is still unapproved and must remain outside this correction.

## Accepted caller contract

```java
properties.setProperty("mail.transport.protocol", "smtps");
properties.setProperty("mail.smtps.user", "current@example.org");
properties.setProperty("mail.smtp.user", "old@example.org");
properties.setProperty("mail.smtps.auth.mechanisms", "XOAUTH2");
Mailer mailer = mail.mailerBuilder(Session.getInstance(properties))
    .withOAuth2AccessTokenProvider(() -> currentAccountToken)
    .buildMailer();
```

Before: pooled OAuth explicitly supplies the inactive SMTP username; direct SJM selects the active SMTPS username. After: both select the username belonging to the selected connection protocol. SMTP/STARTTLS selects `mail.smtp.user`; SMTPS selects `mail.smtps.user`. Retain normal provider fallback when the selected username is absent. Do not copy the SMTPS username into unrelated SMTP properties, reject valid inactive settings, or introduce public settings. Preserve callback laziness, reconnection behavior, authentication failure causes, TLS choices, exclusive leases, cancellation and cleanup.

Governing decisions: ADRs 0015, 0038 and 0039. Clarify the existing protocol applicability decision rather than inventing a separate policy. The separate stale pooled OAuth alias finding remains design-only.

## Ownership and execution paths

The `pooled_oauth_protocol_delivery` worker owns the correction end to end in isolated checkouts beneath `C:/workstation/sjm-oauth-protocol-20261105/`: `pool`, `sjm`, and `website`. It may create bounded evidence/scratch directories there. It must not build in the dirty shared checkouts, install local-only release replacements into the shared Maven repository, or overwrite other agents' files. Root owns this plan and final cleanup-script integration.

The worker owns pool `TransportAllocator.java`, focused allocator/integration tests, applicable README/module docs, RELEASE.txt and release bookkeeping. In its SJM checkout it owns the dependency declaration, focused actual pooled/direct SMTP/SMTPS regression, an ADR 0038 clarification, relevant release history/README/OpenRewrite documentation and website guidance. Coordinate shared files before integration. Root retains combined review and publication clearance; request lead acceptance with the actual diff and evidence before each publication stage, while continuing independent approved work.

Trace `Session.getTransport()` -> pool allocation/reconnection -> OAuth token resolution -> explicit transport authentication, including actual provider protocol selection and provider URL/default behavior. SJM routes include supplied and owned Sessions, direct acquisition, batch-backed ordinary sends, connection checks/probes, and standalone batch integration where affected. No generic-pool implementation change is planned.

## Verification and documentation

Establish release provenance and owning issues first. Use published-behavior bug tracking for the pool if confirmed; reuse the owning unreleased SJM feature issue for a target-only follow-up, as the review guide requires. Resolve live labels, numeric milestones and existing SJM project identifiers. Keep before usage and behavioral acceptance criteria in issues; never publish test/build/verification evidence in issue or commit prose in either repository.

Start with meaningful failing regressions on the pristine baseline, then fix and run focused controls: active SMTP vs SMTPS, both usernames present and distinct, selected username absent/provider fallback, fixed token vs callback, initial allocation vs disconnected reuse, connected reuse without another callback, unchanged Session properties and failure cleanup. Include a real SJM pooled TLS/OAuth authentication path that asserts the username observed by the server, plus direct-path controls; helper-only or mock-only evidence is insufficient for integration acceptance.

Follow pool MAINTAINER_WORKFLOW.md and RELEASING.md, including final JDK 21 verification, actual Java 8 core/provider lane, compatible published artifact set and CircleCI patch approval. Automation owns POM versioning and tags. Do not broaden into unrelated dependency PR merges. After lead acceptance publish/verify the pool patch, exact tag, Central parent and three modules, GitHub release and milestone/issue completion. Only then adopt the released artifact in SJM. Verify SJM through the Maven reactor on modern Java and real JDK 11, checking XML outcomes rather than Maven success alone. Read docs/DEVELOPMENT.md and honor configuration gate/generated metadata implications.

Reconcile each review-workflow surface: public Javadocs and implementation, optional integrations, configuration/locks, copies/serialization, CLI/help/cache where applicable, ADR/index, README/release history, website feature/migration pages, Journal, case studies/downloadable examples and both OpenRewrite inventories. Record precise no-change reasons for unaffected surfaces. Validate final rendered website sources and relevant examples/links when changed. Source-only controls are not runtime evidence, and a research fixture is not a published migration recipe.

## Delivery and retirement

After root reviews the actual combined result, selectively publish website before SJM gitlink to their existing development branches. Keep issue-associated subjects suffixed with their owning issue numbers, and bodies limited to behavior/compatibility/architecture. Read back remote heads, AFTER-first completion comments, closed/completed issues and Done project state. Report evidence directly to the maintainer and retain bounded execution artifacts.

Return exact changed files, owning issue links, commits, releases, final evidence paths, disposable worktrees/scratch and any genuine external blocker. Do not stop at a local patch. Root registers exact retirement paths in the existing guarded cleanup script, preserves all old guards and unrelated targets, validates syntax/dry run, and reports bounded cleanup without retrying previously rejected broad cleanup.
