# Selected review repairs, 1 November 2026

Retired after delivery: #946, #947 and #948 are closed/completed and Done in milestone 10.0.0. Main publication is
`bb27aa6522780f95e0624263dec85fa5f1432351`, including source commits `5cce584c7`, `baafb2029` and `3081e8c2e` plus shared documentation
`86c6f6ffd`. Website publication is `ca2ed8d9eb7a637fa5d3a44fa56960775de52b60`. No accepted implementation, documentation, review or issue
work remains. Required checks and the additional content-handler ownership controls passed on actual Java 11 and Java 21; execution logs remain
in the exact task-owned scratch directory and the direct maintainer conversation, not GitHub issues or commits.

Only bounded disposal remains. Cleanup syntax is valid; its full dry run stops at the older
`.local/tls-inspection-20261030/source/simplejavamail.org/node_modules` reparse point. No deletion was attempted. The selected scratch root
and this retired plan are on the existing guarded allowlists with exact manual deletion statements. Preserve the guards and all unrelated work.

The maintainer accepted findings 3, 4 and 7 and requested autonomous delegated completion of the review workflow. This authorizes implementation, focused verification, required documentation, reviewed selective commits and publication to `codex/10.0.0` and applicable `codex/10.0.0-content`, followed by issue closure and Done status. It does not authorize a release, deployment or history rewrite. Lead review and serialized delivery are coordination checkpoints, not additional permission requests.

Baseline: main `0ff7ccc9565a5e1fd41b8757c23b8da25f4e3655`, website `bf3c7602a610afafb80df259f19d2d726747a739`. Both remote heads match. Preserve pre-existing staged/unstaged Angus research, workflow and cleanup changes, nested website narratives/styles/tests, untracked plans, scratch and registered worktrees. Status, binary patches, index copies and worktree/untracked inventories are under `.local/review-selected-20261101/preservation`.

| Finding / issue | Ownership | Accepted behavior |
| --- | --- | --- |
| 3 / [#946](https://github.com/bbottema/simple-java-mail/issues/946) | `fix_msg_type`: isolated `msg-type/source`, Outlook attachment mapping and a unique public-route regression class | Missing MSG MIME type and both filenames must not discard readable binary content. Use `application/octet-stream` only when no type is available; retain supplied/inferred types, attachment/CID classification, naming, bytes and ownership. ADRs 0035/0036. |
| 4 / [#947](https://github.com/bbottema/simple-java-mail/issues/947) | `fix_smime_encoding`: isolated `smime-encoding/source`, S/MIME finalized entity extraction and a unique cryptographic regression class | Preserve selected inner-body transfer encodings through signing, encryption and combined protection for single-part and multipart content. Retain defaults, relevant MIME headers, protection order and source authority. ADRs 0007/0008/0036. |
| 7 / [#948](https://github.com/bbottema/simple-java-mail/issues/948) | `fix_msg_receipts`: isolated `msg-receipts/source`, Outlook receipt-header interpretation and a unique public-route regression class | Interpret both receipt header families in raw source order across interleaved capitalization, retaining duplicates/names and malformed/empty/null-path behavior. Leave broader generic-header ordering unchanged. ADRs 0035/0036. |

Workers are not alone in the codebase and must preserve and accommodate others' edits. They implement and build in their separate owned exports of the frozen baseline; no shared root targets or installed development artifacts. Main-source integration and commits are serialized by the lead, because the two MSG changes touch separate methods in the same file. Workers return their exact changed files, behavior, focused failure/fix evidence, controls, limitations and documentation applicability. After lead acceptance they receive a serialized delivery turn for final issue bookkeeping. No worker independently broadens the contract or publishes unreviewed work.

## Impact map and verification order

1. MSG bytes -> specialist parser with absent type/name metadata -> SJM attachment/CID mapping -> Email -> readable/exported bytes. Exercise missing/empty values, filename inference and supplied-type controls, inline/ordinary classification, neighboring content, supported public entry routes and untouched strict outgoing validation.
2. Composed Email -> ordinary finalized MIME -> S/MIME root extraction -> signed/encrypted content -> verification/decryption and exported encoding. Start with explicit base64 ASCII single-body signing, then encryption/combined protection and multipart/default controls. Preserve selected encodings, decoded content, relevant headers and repeated writes; do not claim arbitrary byte identity where existing canonicalization applies.
3. Raw MSG headers -> Outlook grouped map/raw source -> ordered typed receipt lists -> copy/serialization/export. Start with interleaved capitalization, then duplicates/names, both receipt families, missing/raw-header fallback, malformed/empty/null-path controls and shared static/fluent/file/path/stream routes. Broader custom-header ordering is excluded.

Use focused red-first behavioral regressions and real Java 11/Java 21 library execution; skip the Windows license formatter as DEVELOPMENT.md directs. No external email. Root reviews actual combined patches and interactions before sufficient integrated checks. Do not repeatedly rerun unrelated suites. Generate Javadocs/CLI metadata only if their relevant inputs change.

The lead owns shared ADRs/index, release history, both OpenRewrite inventories, applicable website guidance and the cleanup script. For README, website guides, Journal, case studies and downloadable examples, update affected current claims or record why no change is needed. No new public signature or CLI option is intended. Migration notes must distinguish longstanding released behavior from unreleased-only API corrections.

GitHub issues belong to milestone 10.0.0 and the existing Simple Java Mail 10.0.0 board, In Progress while execution is pending. Issue and commit prose contain behavior, usage, architecture and delivered references only; never test/verification evidence. Report evidence directly to the maintainer. Website companions publish before the parent gitlink. Completion comments begin with after usage, then delivered behavior and commit/ADR links; close only delivered issues and set Done. Track exact task-owned scratch/retired-plan paths in the guarded cleanup script without deleting unrelated artifacts.

## Delivery coordination

The reviewed source commits are delivered: #946 `5cce584c7f22e52775b0895e3146950ff673366b`, #948 `baafb2029e1c2e2d88ad807cdffecd6e9f409d79`,
and #947 `3081e8c2e490f664b46e92a6b6aedfb3ecc7311d`. The pre-existing staged research patch remains byte-identical.
Website checks/build/link inspection used an isolated export beneath the website's `.local` so existing installed dependencies could resolve without a junction.
After those checks, that export moved into `.local/review-selected-20261101/website`; cleanup retains the exact task-owned round root and retired-plan paths.

## Findings still under discussion

- Findings 1, 2, 5, 8 and 9 have no implementation authorization. Discuss them one at a time, beginning with 1, in plain English with illustrative code.
- Finding 6: the maintainer rejected runtime regeneration as the remedy and clarified that CLI metadata is generated during build and packaged with published artifacts. Runtime generation cost is unacceptable. Investigate the existing fallback/build/package boundary only; do not implement runtime recovery or a broader metadata redesign without agreement on concrete behavior.
