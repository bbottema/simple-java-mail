# Approved API review repairs

The maintainer accepted all six findings and authorized delegated implementation and completion of the review workflow on 28 October 2026.
Delivery targets are the existing `codex/10.0.0` main branch and, when documentation changes apply, `codex/10.0.0-content` in the website.
Preserve unrelated staged, unstaged and untracked work. No release or deployment is part of this round.

| Issue | Agreed behavior | Worker ownership | Governing ADRs |
| --- | --- | --- | --- |
| [#884](https://github.com/bbottema/simple-java-mail/issues/884) | Equivalent S/MIME media types retain protection interpretation. | S/MIME recognition and focused protection regressions. | 0008, 0035 |
| [#885](https://github.com/bbottema/simple-java-mail/issues/885) | HTML attribute character references participate in CID identity. | CID reference interpretation and import/exact regressions. | 0008, 0035, 0036 |
| [#886](https://github.com/bbottema/simple-java-mail/issues/886) | Trailing comments do not erase valid delivery-report timestamps. | Report date parsing and focused delivery-report regressions. | 0035 |
| [#887](https://github.com/bbottema/simple-java-mail/issues/887) | Automatic image resolution skips existing CID schemes case-insensitively. | Image resolver and focused builder regressions. | 0035, 0036 |
| [#888](https://github.com/bbottema/simple-java-mail/issues/888) | Inline comments complete argument tokens, including empty ones. | Argument-file tokenizer and its focused regressions. | 0018, 0019 |
| [#889](https://github.com/bbottema/simple-java-mail/issues/889) | Genuine help ignores later malformed daemon selectors. | Bootstrap help/routing and its focused regressions. | 0018, 0019 |

Workers use isolated checkouts, run focused failing regressions before repairs, retain boundary controls and create selective local semantic
commits. The lead coordinates shared ADRs, release notes, website/Journal/case-study and OpenRewrite alignment, combined-diff review,
integration, publication, issue closure and cleanup. No worker may publish, close issues or change shared documentation independently.
Return any material contract expansion to the lead. Keep execution evidence in the maintainer conversation or CI artifacts.

Review the assembled result before publishing. Keep each issue open until its implementation and required companions are delivered,
then add an after-usage completion comment, close it and set its existing project item to Done. Retire this plan when coordination ends.
