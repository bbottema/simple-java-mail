Retired after ticketless development-branch delivery: main `880aae8b74cc76909b7d9f9cbde9e3805c1dde1e`, cleanup/head `bdbf3bafd0d10fa91f9f42e6c3a380b3b5264358`, website `3370c000e3caa46fc7c349393fcfb746f4f955c9`. No GitHub issue was created or changed. Exact owned scratch and this plan remain queued in the guarded cleanup script. Runtime implementation and copy/import defaults remain unchanged.

# S/MIME helper Message-ID contract, 3 November 2026

The maintainer accepted review finding 4 as a clarification of existing behavior, with focused regression coverage, and asked for a dedicated subagent. No new GitHub issue is needed; this ticketless work makes no issue lifecycle mutations. Standing workflow authorization permits reviewed selective delivery to main `codex/10.0.0` and website `codex/10.0.0-content`. No release, deployment, history rewrite, copy/import policy change or calendar-merge repair is authorized. Baselines: main `b1fab1b3d9fdefbd6f53efff026c7a594f1630e7`, website `345ed8627a2797547a4fc8bfb001dda58c408b87`.

For active S/MIME signing or encryption, the Email argument's configured Message-ID wins when nonempty; without one the helper generates a new ID. An existing supplied MIME Message-ID alone does not request reuse. Repeated writes of the finalized result retain its selected ID. With no requested S/MIME operation the helper returns the original message unchanged, without reading/finalizing content or loading the optional module.

The supplied MIME message remains the content and applicable-header input. Message-ID is an explicit exception governed by the Email's optional fixed-ID choice. The configured value can already have been carried by conversion/copying; its presence does not establish that the application directly called `fixingMessageId`. Keep copy/import behavior unchanged, document `clearId()` for fresh copied mail and deliberate `fixingMessageId(message.getMessageID())` for reuse.

## Ownership and surfaces

The dedicated worker owns MailerHelper helper Javadocs, meaningful public-helper cases in MailerHelperSmimeTest, ADR 0008 and its index, relevant website helper/migration paragraphs, both OpenRewrite inventories and exact cleanup additions. Runtime code, public signatures, configuration, Spring, CLI syntax, serialization and general copy/import defaults stay unchanged. Root discusses finding 6 read-only and reviews the combined result before delivery.

Capture and preserve all unrelated staged/unstaged/untracked work, the dirty cleanup/review guide, Angus research, website narratives/styles/tests and linked worktrees. Scratch is bounded to `.local/review-smime-id-contract-20261103`. Builds use isolated source exports and actual Java 11 and 21, with Windows license formatting disabled, at most two Maven processes. This is characterization of the chosen existing contract, not a failing defect repair; do not invent a red/green narrative.

Exercise real S/MIME signing, encryption and combined protection with competing configured/source IDs, absent or empty configured ID, missing source ID, stable repeated writes and the no-op boundary. Inspect current public usage, generated/packaged Javadocs, source and rendered website examples, Journal/case studies/downloaded bundles and both migration inventories. Record applicable updates or why no change is needed. No runtime bug-fix release entry or automatic migration recipe is warranted.

## Delivery

Return the actual combined diff, contract, file inventory, checks/results, documentation matrix and limitations to the lead. After acceptance, publish the selected website commit before the main gitlink. Use selective temporary indexes and preserve unrelated real-index entries and dirty content. Commit prose describes behavior and architecture (ADRs 0008/0031/0036 as applicable), never verification evidence or sign-offs. No issue suffix is required for this ticketless clarification, and no GitHub issue writes are performed.

Retire this plan after delivery and register exact owned scratch/plan paths in the cleanup script. Preserve all guards and WhatIf support, validate syntax and a bounded selected-target dry run, and leave historical cleanup backlog untouched. No broad deletion or registered-worktree cleanup is authorized.
