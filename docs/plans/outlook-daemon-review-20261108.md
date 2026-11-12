# Completed Outlook and daemon review repairs, 8 November 2026

The maintainer accepted all three findings and recommendations and assigned one subagent per finding to complete the review workflow. Implementation, documentation, selective publication and issue closure were authorized. The maintainer separately explicitly approved the reviewed Outlook Message Parser 1.16.3 patch release. No SJM release, production website deployment, history rewrite or unrelated cleanup was authorized.

| Issue | Delivered behavior | Governing decisions |
| --- | --- | --- |
| [#974](https://github.com/bbottema/simple-java-mail/issues/974) | Missing Outlook dates remain absent, readable content stays available, client-submit time precedes lazy source-date fallback, and SJM consumes published parser 1.16.3. | ADRs 0035, 0036 and 0041. |
| [#975](https://github.com/bbottema/simple-java-mail/issues/975) | Missing Outlook senders remain null, native evidence is retained, and deliberate outgoing sender/default/override choices keep their owners. The released #201 dummy-address policy is retired with migration guidance. | ADRs 0035 and 0041. |
| [#488](https://github.com/bbottema/simple-java-mail/issues/488) | Daemon cache eviction tries eligible idle profiles even when older candidates are pending or become active, preserving lease/shutdown/close-once ownership. | ADR 0019. |

All three issues are delivered, closed/completed and project Done. Final main head is `5fb5a62c5c305d40b15dc3e7e6db968b1844006d`; website head is `7e2de48e857804be09c11094126e0ccbae222bdd`. Website publication preceded each main gitlink. Date source repair is `4d41d3f39bb8ef0ac51d30681ada6145d134ebf4`, with the published dependency and shared guidance in `7b980b88e896d0c8e38e72108a31904e151e858a`.

Parser [1.16.3](https://github.com/bbottema/outlook-message-parser/releases/tag/1.16.3) is published on Maven Central. Parser master and the dereferenced release tag are `cf47dfc9a531467bfdcf400f625000ee1e286fbe`. Upstream #103 and milestone 1.16.3 (#54) are closed; the milestone records the actual UTC release date. The canonical parser checkout is clean and aligned.

Source/runtime checks, generated and packaged getter Javadocs, website output and links, rendered captures, release artifacts and preservation manifests are retained under `.local/review-outlook-daemon-20261108-evidence`. The final library checks use the actual published parser 1.16.3 artifact, not the earlier isolated patched 1.16.2 dependency. Both OpenRewrite inventories retain the existing R028/F27 boundary without inventing a runtime-content rewrite or new report identity. The daemon correction remains an unreleased feature refinement.

Canonical main/website branches are aligned with the delivered heads. Original staged patches and unrelated dirty-file bytes are preserved; the original cleanup-script edits remain alongside the task's additions. No owned helper processes remain.

Cleanup remains deferred: automatic approval review rejected removal of the task checkouts and private dependency cache before execution, stating only "blocked by policy". No deletion ran or was retried. All exact paths and manual retirement statements remain in `cleanup-api-review-20261021.ps1`, including this retained plan, and the task-owned directories remain under `C:/workstation/sjm-review-20261108-outlook-daemon`. The existing full cleanup dry run also retains its older reparse-point guard at `.local/decrypted-merge-contract-20261103/repair/website-source/node_modules`; it was not bypassed. Preserve the evidence bundle and unrelated work during any later manual retirement.
