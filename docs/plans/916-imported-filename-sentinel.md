# Preserve a literal fallback-shaped filename

The maintainer approved the narrow filename correction and delegated its complete review workflow on 29 October 2026. Design, implementation and delivery on `codex/10.0.0` and applicable `codex/10.0.0-content` changes are authorized after lead review. No release, deployment, history rewriting or unrelated cleanup is included.

Tracking: [#916](https://github.com/bbottema/simple-java-mail/issues/916), governed by ADR 0035's literal imported filename decision, ADR 0036 and ADR 0008. Baseline main `073cd6a3272f4a33cceffb7e4eaf2a3dceb5d013`, website `5435c38b09995cca7bfb87a6749042e5198cfc4f`. Preserve existing index/worktree/website work; baseline patches and isolated verification source are retained under `.local/filename-sentinel-916-20261029`.

The worker owns MIME filename interpretation, focused conversion regressions, the corresponding ADR/release/OpenRewrite/website guidance and exact cleanup entries. The lead reviews the combined diff and actual execution evidence before commits, publication and issue closure. Other review findings remain outside this packet.

Keep supplied decoded names even when they equal `UnknownAttachment`; distinguish genuine missing naming metadata before CID fallback. Preserve empty-name behavior, genuinely unnamed resources, forwarded-message naming, resource classification/content IDs/bytes/order, eager/lazy ownership, copying/serialization and authoritative exact/protected bytes. No public API or property/CLI expansion is needed.

Start with the public conversion regression before production edits. Then run focused converter controls in an isolated fresh reactor on a modern JDK and a real JDK 11. Inspect the full workflow surface matrix and verify changed documentation. Syntax-check and dry-run bounded cleanup additions while preserving the older reparse-point guard; no deletion is authorized. Retire this plan after durable decisions and delivery are recorded.

Delivered in main ea31b5217a454ed4493332cc5318dcad86b85edd and website 5b9d6c53bdeb1e4e427abe578146fd14f9dd05f3 after lead acceptance. Issue #916 is closed/completed and Done. This completed plan and owned scratch remain on their exact cleanup allowlists; cleanup is deferred at the pre-existing node_modules reparse-point guard, with no deletion performed.
