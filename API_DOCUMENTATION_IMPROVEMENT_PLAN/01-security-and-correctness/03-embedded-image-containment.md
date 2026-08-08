# Make embedded-image resolution a real containment boundary

- Status: Done
- Priority: High
- Work: Code, security tests, documentation

## Problem

Auto-resolution is recommended for freely entered HTML, but `allowingEmbeddedImageOutsideBase*` is not a reliable sandbox. File paths are joined without canonical containment, and URL containment does not adequately constrain scheme and authority.

## Plan

1. Define the intended containment contract for file, classpath, and URL resources.
2. Resolve and normalize paths before checking they remain under the configured root.
3. Compare URL scheme, host, effective port, and normalized path, and recheck every redirect.
4. Resolve filesystem paths to their real location so symlinks cannot escape the base.
5. Add traversal, sibling-prefix, alternate-origin, redirect, encoded-path, symlink, and absolute-resource tests.
6. Document the unrestricted behavior when no base is configured or an explicit `allow outside` option is enabled.

## Acceptance criteria

- [x] `../` and encoded traversal cannot leave the configured base.
- [x] A URL on another authority cannot pass a same-path check.
- [x] Explicit “allow outside” options retain their documented behavior.
- [x] The guide clearly distinguishes convenience from a security boundary.

## Evidence

- Issue: [#678](https://github.com/bbottema/simple-java-mail/issues/678), with historical context from [#260](https://github.com/bbottema/simple-java-mail/issues/260) and [#617](https://github.com/bbottema/simple-java-mail/issues/617)
- Documentation: `simplejavamail.org/src/pages/features.hbs` and `simplejavamail.org/src/pages/migration-notes-9.2.0.hbs`
- Javadocs: `modules/core-module/src/main/java/org/simplejavamail/api/email/EmailPopulatingBuilder.java`
- Resolution code: `modules/core-module/src/main/java/org/simplejavamail/internal/util/MiscUtil.java`
- Tests: `modules/simple-java-mail/src/test/java/org/simplejavamail/internal/util/MiscUtilTest.java` and the existing `EmailPopulatingBuilderImpl1Test` compatibility suite
