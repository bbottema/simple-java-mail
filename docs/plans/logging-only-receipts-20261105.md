# Finding 6: receipts use the prepared recipient view

Accepted and authorized on 5 November 2026. After agreeing to the recipient-projection correction, the maintainer explicitly requested a subagent to carry it through the complete review workflow and warned about the active Session-integration session. This authorizes implementation, meaningful verification, applicable architecture/documentation/migration updates, reviewed selective commits/publication to the existing development branches, issue/project completion and bounded task retirement. No SJM release, history rewrite, unrelated cleanup or Session-overhaul implementation is authorized.

This was the last finding in the current six-item wide/deep review. There is no queued finding 7 from this round. S1-S4 are the separate Session follow-up under #969 and its dedicated Codex session.

## Tracking and provenance

Use [#723 - Expose per-recipient SMTP replies and retry guidance](https://github.com/bbottema/simple-java-mail/issues/723) for this completion of unreleased 10.0.0 work. The incorrect typed-Email fallback was introduced in `f79c66c667e8c02a54b9804848cbb9f3fc59ae7d` with that feature. Published 9.3.9 has a receipt with server-response details but no recipient-results API; do not file or count this as a released 9.x recipient-receipt defect. #710 is the transport-neutral parent and #654 the original receipt feature. Recheck live metadata/history before mutation, preserve substantive issue context, and add a dated concrete problem/before example. Reopen #723 with its existing major-feature classification, milestone 10.0.0 and existing project/In Progress until accepted delivery.

Follow `docs/API_REVIEW_WORKFLOW.md`, `API_EXPANSION_WORKFLOW.md`, `DEVELOPMENT.md`, `MAINTAINER_WORKFLOW.md`, `CODING_STYLE_GUIDE.md` and applicable AGENTS instructions. Governing decisions include ADR 0011 (transport-neutral outcomes), ADR 0014 (rehearsal/planned recipients), ADR 0015 (execution/Session ownership) and ADR 0031 (public/internal boundaries). Clarify the existing owners rather than creating a new configuration architecture.

## Agreed behavior

Logging-only prepares a MIME message but currently discards that recipient view when constructing its receipt. `TransportRunner.buildReceipt(Email, null)` projects typed `Email.getRecipients()` or override receivers instead. Raw To/Cc/Bcc headers are omitted and interleaved typed recipient categories need not match the prepared message's order.

```java
Email email = mail.emailBuilder().startingBlank()
    .from("sender@example.org")
    .to("alice@example.org")
    .withHeader("Cc", "bob@example.org")
    .withSubject("Example")
    .withPlainText("Hello")
    .buildEmail();

try (Mailer mailer = mail.mailerBuilder()
        .withTransportModeLoggingOnly(true).buildMailer()) {
    MailSubmissionReceipt receipt = mailer.sync().sendMail(email);
    // The current recipient results omit Bob, although the prepared message includes him.
}
```

Build the simulated receipt from the already prepared delivery-recipient view or the existing lightweight shared recipient projection. Honor effective override receivers, raw recipient headers, native MIME ordering and duplicate occurrences. Preserve the agreed group boundary: ordinary convenience APIs already expand their groups; a named group retained in a raw header can remain one planned recipient entry, as in ADR 0014 and rehearsal. Do not emulate Angus-specific connection-time group expansion or add #727's separate group-preservation capability.

Keep submission acceptance `UNKNOWN`, normal retry guidance and absent SMTP/provider facts. Do not fabricate SMTP replies, RCPT observations, SIZE/DSN/REQUIRETLS usage or callback delivery facts. Logging-only outcome/observer/scheduling/cancellation behavior remains the existing contract.

The same typed fallback is used for CustomMailer receipts. Any shared correction describes SJM's prepared recipient intent only: CustomMailer owns its external mapping and may choose different recipients. Do not claim an observed external envelope or acceptance. Preserve the original callback API, arguments, invocation/exception behavior and ownership. Capture planned recipient facts before handing a mutable MimeMessage to the callback, rather than interpreting callback mutations as proof of its delivery. Return a genuinely different public meaning to root before implementation.

Reuse the single completed MIME preparation. No second rendering, attachment/content traversal, cryptographic pass, temporary transport, SMTP/pool acquisition or extra network action is authorized for recipient inspection. Retain normal address validation, governance, raw-header and lock boundaries; do not replace lightweight recipient extraction with unrelated full-provider validation or universal raw mailbox normalization. Preserve original Message-ID/content, borrowed resource identities and exact-email behavior.

## Ownership and isolation

Worker `receipt_recipients_delivery` owns isolated checkouts and bounded tools/evidence under `C:/workstation/sjm-logging-receipts-20261105/{sjm,website,evidence,tools}`. It owns the receipt construction/recipient handoff in `SendMailClosure`, `TransportRunner` and minimal shared preparation/recipient helpers if needed; focused regression coverage; applicable core receipt Javadocs; ADR 0011/0014 clarification; relevant documentation, website and migration inventory entries. It must not edit the Session owner's capture/resolution/protocol/provider work, CLI daemon, crypto attachment repair or OAuth registration implementation.

You are not alone in the codebase. Preserve canonical dirty/staged/untracked main/nested files, every other worktree/branch and all existing evidence. No broad staging, reset, stash, clean, force push, shared repository deletion or installation of unapproved prototype artifacts into shared Maven storage.

Observed canonical baseline is main `e458b76c7f99c3a6a27a909098c803b54869cf50`, website `86ce310498495ee9cadc408bcb20f2c4351d1867`. The separately accepted review-delivery tree is main `1fca3e34909f9538015cab9382c1f566f7c3f163`, website `b3418ff95d90c2f478eede19445fd68fe1b7f202`, still undergoing final verification/publication. Start from a clean recorded approved baseline and explicitly reconcile its eventual publication before final delivery. Do not import uncommitted peer code or edit peer checkouts.

The dedicated #969 session is `01a1ad80-beeb-7ec1-a864-7d1dc23fddcc`, title `SJM Session integration — #969`. The maintainer has subsequently authorized its implementation there. Its owned paths are `C:/workstation/sjm-session-integration-20261105/{sjm,website,combined}` and its broad checks are active. This finding is outside #969. Read its current state and Git/worktree status as needed; do not stop its processes, change its files/plan/issue, publish its work or infer that its implementation is still merely awaiting approval.

## Verification and integration queue

The existing coordinator `oauth_registration_delivery` owns the current Java11 confirmation and subsequent packaged probes for findings 2-5. The #969 session also has independent verification in progress. Before starting any Maven/JVM/build/probe, obtain a clear window through root/coordinator and read-only process/session state. Prepare source history, issue, documentation and isolated fixtures while waiting. Do not overlap a broad reactor with either workflow, terminate their processes or presume an old quiet snapshot still applies. Run focused work at fork count 1 with original cases/timeouts and failure-ignore disabled, using the Maven reactor and fresh optional modules.

Establish a meaningful baseline failure before the repair. Cover raw To/Cc/Bcc individually and together; interleaved typed categories; duplicate occurrences; header groups versus existing convenience expansion; overrides replacing header delivery recipients in caller order; immutable receipt results; logging-only sync/async/observer/scoped sends where applicable; and exact/prepared message behavior. Assert normal unknown/absent SMTP facts and no additional preparation/content reads or transport acquisition. For the shared CustomMailer path, cover prepared intent, callback mutation, exceptions and its opaque external delivery boundary without claiming actual delivery.

Use existing helper/source contracts to avoid a Cartesian matrix or tests mirroring the implementation. Inspect signature/configuration/CLI inventories even when no new application API is added; update required consumers rather than weakening discovery gates. Preserve raw baseline failures and later focused/full outcomes separately. Any source/protocol/ownership or public-contract expansion returns to root.

Freeze the source and provide root with the actual diff, source hashes, owning issue/ADR decisions, focused regression evidence, final usage and applicable documentation inventory. Continue independent permitted verification/documentation while root reviews. Root reviews the actual combined change, not just a completion message. Before publication, integrate against the then-current delivered branches and the relevant #969 result, resolve shared documentation additively, and perform appropriate final checks on that exact integration. Do not silently publish over an independently advancing Session branch or reuse stale artifact results.

## Delivery and retirement

Complete public API/Javadocs, implementations, relevant ADRs/index, root release history/docs, website, applicable Journal/case-study examples and OpenRewrite classification/manual-review records. Document current 10.0.0 behavior; do not invent a 9.x rewrite for a target-only recipient-results correction.

Public issue/comment/commit/release prose contains behavior, compatibility, examples and architecture references only. Never post verification commands, counts, pass/fail/build/package claims, hashes or execution diaries there. Return evidence directly to root/maintainer and retain it under the bounded task evidence root.

After root acceptance, selectively publish the website first, then the main changes with the matching gitlink on the existing development branches. Use semantic owning-issue commit subjects ending in `(#723)` and architecture references in bodies. Preserve all unrelated staged/dirty content and advancing remote history. Add an AFTER-first completion example to #723, close completed and set its existing project item Done, and read back issue/labels/milestone/project/remote heads. No SJM release is authorized.

Coordinate canonical alignment and cleanup with root and the current coordinator. Register only exact owned paths/parents and the retired task plan in the guarded cleanup script, with selective own-hunk handling of its already-dirty content. Keep evidence and all other active plans/checkouts, especially #969. Preserve path/reparse/worktree guards and WhatIf; do not execute the whole cleanup script or retry prior automatically rejected removals. Retire only accepted task-owned worktrees by ordinary Git operations. A local patch or green focus is not workflow completion.
