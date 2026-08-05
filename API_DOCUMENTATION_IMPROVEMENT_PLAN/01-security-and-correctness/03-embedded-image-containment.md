# Make embedded-image resolution a real containment boundary

- Status: Planned
- Priority: High
- Work: Code, security tests, documentation

## Problem

Auto-resolution is recommended for freely entered HTML, but `allowingEmbeddedImageOutsideBase*` is not a reliable sandbox. File paths are joined without canonical containment, and URL containment does not adequately constrain scheme and authority.

## Plan

1. Define the intended containment contract for file, classpath, and URL resources.
2. Resolve and normalize paths before checking they remain under the configured root.
3. Compare URL scheme, host, effective port, and normalized path.
4. Add traversal, alternate-host, encoded-path, and absolute-resource tests.
5. Until the code is fixed, warn against attacker-controlled HTML.

## Acceptance criteria

- [ ] `../` and encoded traversal cannot leave the configured base.
- [ ] A URL on another authority cannot pass a same-path check.
- [ ] Explicit “allow outside” options retain their documented behavior.
- [ ] The guide clearly distinguishes convenience from a security boundary.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:972-1013`
- Resolution code: `modules/core-module/src/main/java/org/simplejavamail/internal/util/MiscUtil.java:235-300`
