# Missing reply-body alternatives (#894)

Delivered main `a7c3ed14760c752f6da529e5c2ac8bdd8e4666a1` and website `bcf140c23f16a12deac40743ad9e5b93593788e6`.
Issue #894 is closed/completed and its project item is Done. Implementation, documentation and delivery are complete. Automatic approval review
rejected deletion of the exact guarded task scratch and this plan as "blocked by policy" before execution. Both remain as pending cleanup,
with their exact allowlist entries retained; no alternate deletion route was attempted. Prior #893 cleanup remains deferred and untouched.

Accepted 28 October 2026: generate a missing plain/HTML representation once while priming replies, keeping conversion small and local.
The maintainer authorized implementation/delegation and complete workflow delivery on existing `codex/10.0.0` and `codex/10.0.0-content`
after lead review. Issue: https://github.com/bbottema/simple-java-mail/issues/894. Architecture: ADR 0008, ADR 0031 and ADR 0036.

## Impact map

Before: replying to a single-format original creates an empty quote in the missing alternative, even when callers prepend both answer formats.
After: plain-only originals get escaped HTML with line breaks; HTML-only originals get readable plain text with block/list breaks, link destinations
and image alt text. Script/style text is omitted. Both supplied originals retain independent content, and the custom HTML quote template runs once.

Paths: every Email/MimeMessage reply and reply-all overload -> existing shared `EmailStartingBuilderImpl.replyingTo` -> private reply-body helpers
-> ordinary populating builder. Later set/clear/append/prepend, templates, routing/thread headers, images and content ownership stay independent.
Use existing jsoup parsing/escaping/text helpers; no fetching, new dependency, public conversion API or rendering framework.

| Scenario | Intended result |
| --- | --- |
| Plain-only / HTML-only through all reply overloads | Both quoted alternatives contain original context. |
| Plain metacharacters/CRLF/LF and custom quote template | Escape source safely, preserve breaks, apply the template once. |
| HTML entities/blocks/lists/links/alt/script/style | Readable inert plain text without changing original HTML or fetching resources. |
| Both bodies, later edits/clears, defaults/overrides and empty/no body | Keep independent existing behavior outside initial missing-alternative generation. |
| Headers/recipients/images and source content | Preserve established reply routing/thread/resource contracts. |

## Ownership and affected surfaces

Worker owns reply-local implementation/helpers, new focused public `ReplyBodyAlternativesTest`, `EmailStartingBuilder` Javadocs,
applicable website reply/migration paragraphs, focused CLI cache/help cases and genuine CLI cache regeneration.
Preserve all concurrent root/nested/index changes, #893 leftovers and rejected cleanup paths. No issue 1 changes belong here.

Root owns its accepted ADR 0008 clarification, ADR index, release history and both OpenRewrite documents. Worker leaves those files untouched
and includes their accepted final content in combined delivery.

Public reply Javadocs feed CLI help, so both caches and real help readback require regeneration even though signatures/fingerprint are unchanged.
Inspect README, Journal, case studies/downloadables, properties/Spring, serialization and other public converters and retain no-change rationale
for unaffected surfaces. This longstanding behavior affects released 9.3.6 callers: add behavior/manual-review guidance beside existing starter
mapping and frozen-target controls, without inventing another automatic source rewrite or implying recipes are implemented.

| Surface | Reconciliation |
| --- | --- |
| Public API/Javadocs | Existing signatures remain; reply method Javadocs explain missing-only, initial generation and independent later editing. |
| Implementation | Two private helpers in `EmailStartingBuilderImpl` reuse jsoup. No new dependency, conversion setting, parser or renderer framework. |
| Integration/configuration | Properties, Spring mapping, serialization, other converter entry points and resource ownership are unchanged. |
| CLI | Regenerate both startup caches from real sources and check bundled reply Javadoc plus generated option help; option shape/names remain. |
| Architecture/root docs | Accepted ADR 0008 and index, unreleased history and both OpenRewrite documents; README has no affected reply examples. |
| Website | Reply paragraph in `features.hbs`; new `reply-body-alternatives` behavior/migration anchor with links. |
| Journal/cases/downloadables | No reply starter/priming usages found in maintained prose/source or the downloadable ZIP's Java/Markdown/text entries. |

Own scratch is `.local/api-review-894`. The selected clean website export starts at the website's `.local/api-review-894` and will relocate
inside the root task scratch after checks, without a dependency junction. No #893 target is eligible for this task's cleanup.

## Verification and delivery

1. Capture live root/nested/index state; observe public plain-only and HTML-only regression failures before changing production code.
2. Implement only small reply-local conversion helpers and run focused JDK 11 regression/neighbor controls.
3. Reconcile Javadocs/docs; use JDK 17 current reactor classes for packaging/Javadocs, genuine CLI cache generation and focused cache/help behavior.
4. Validate selected clean website sources with checks/build, generated content/links; present combined diff and actual evidence to lead.
5. After acceptance, selectively commit/push website first, then parent code/docs/caches and matching gitlink; verify remote heads, after-usage
   comment, close completed/Done and metadata. No test/verification evidence goes in issues or commits.
6. Retire this plan and exact task scratch where allowed. If automatic review rejects new cleanup, stop and record exact pending paths;
   do not retry #893's rejected cleanup or touch unrelated cleanup blockers.
