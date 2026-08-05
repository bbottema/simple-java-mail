# Document environment-variable syntax

- Status: Planned
- Priority: Medium
- Work: Documentation, tests

## Gap

Environment variables are listed as a source, but the fixed-key uppercase/underscore mapping is not shown. Wildcard extra-property and per-cluster namespaces are scanned differently and have limitations.

## Plan

Provide concrete fixed-key examples such as `SIMPLEJAVAMAIL_SMTP_HOST`, then document wildcard namespace handling and platform caveats separately.

## Acceptance criteria

- [ ] At least three common environment-variable examples are shown.
- [ ] Fixed and wildcard mapping rules are distinguished.
- [ ] Tests anchor case and separator conversion.
- [ ] Cluster environment limitations are explicit.

## Evidence

- Fixed mapping: `modules/core-module/src/main/java/org/simplejavamail/config/ConfigLoader.java:407-408`
- Wildcard scanning: `modules/core-module/src/main/java/org/simplejavamail/config/ConfigLoader.java:465-495`
