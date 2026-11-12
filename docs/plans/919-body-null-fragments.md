# Completed: reject missing body fragments before changing the draft

Delivered on `codex/10.0.0` at `af977596759d723a0a17d6110b85d538e50fa15c`, with the website guidance in `45310b20200203f124594d5e084fdb184b3737e3` retained by ordinary merge `addde74ef5070555ea2c3b182f7411312f4a2965`. Issue #919 is closed/completed and Done. Parallel S/MIME and fixture changes remain included; the original staged patch and unrelated working-file contents are preserved. The plan is retired and retained untracked only for guarded cleanup, together with its owned scratch directory.

The maintainer approved this narrow repair and delegated its complete review workflow on 29 October 2026. Design, implementation, independent combined-diff review and delivery on `codex/10.0.0` and applicable `codex/10.0.0-content` changes are authorized. No release, deployment, history rewriting or unrelated cleanup is included.

Tracking: [#919](https://github.com/bbottema/simple-java-mail/issues/919). ADR 0031 owns required-input rejection; ADR 0009 records that default null instrumentation is disabled. Baseline main `c239fda20c435b9843d2438b387e0124b3babc55`, website `086da085647ebd6d9678f6f12cecd9910550aee9`. Preserve the captured original index, root/website worktree patches and hashes under `.local/body-null-fragments-20261029`.

The issue lead owns the four String append/prepend bodies in `EmailPopulatingBuilderImpl`, public body regressions, the scoped ADR/release/OpenRewrite/website guidance and exact cleanup entries. Other findings remain outside this packet.

Reject null before mutating either body. Retain previous text or absence, empty-string behavior, valid ordering, file/path content, other body/calendar choices, snapshots, nullable replacement clearing and named clears. No signatures, body names, configuration, CLI options, serialization fields or resource ownership change. This is a separate validation correction from the earlier body-vocabulary rename.

Start with a public failing regression, then the four guards and focused controls on JDK 21 and real JDK 11 in the isolated source snapshot. Inspect every workflow surface; use the normal CLI metadata generator only if a real builder/Javadoc input changes. An independent reviewer examines the actual combined diff and evidence before issue-lead acceptance, selective website-first publication and closure/Done.

Cleanup additions name only the owned scratch and this plan. Preserve the existing syntax, Git/path/reparse-point guards and WhatIf support; no deletion is authorized. Retain any existing cleanup blocker in the final report.
