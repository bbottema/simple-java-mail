# Document environment-variable syntax

- Status: Done
- Priority: Medium
- Work: Documentation, tests

## Gap

Environment variables are listed as a source, but the fixed-key uppercase/underscore mapping is not shown. Wildcard extra-property and per-cluster namespaces are scanned differently and have limitations.

## Plan

Provide concrete fixed-key examples such as `SIMPLEJAVAMAIL_SMTP_HOST`, then document wildcard namespace handling and platform caveats separately.

## Acceptance criteria

- [x] At least three common environment-variable examples are shown.
- [x] Fixed and wildcard mapping rules are distinguished.
- [x] Tests anchor case and separator conversion.
- [x] Cluster environment limitations are explicit.

## Evidence

- `simplejavamail.org/src/pages/configuration.hbs` now shows four common fixed-key environment variables, explains the uppercase/dot-to-underscore conversion, and separates it from literal wildcard scanning.
- The guide explicitly recommends property files, exact dotted JVM system properties, or Java configuration for wildcard extra-property and per-cluster namespaces because literal dotted environment names are not portable.
- `ConfigLoaderTest` now covers a multi-segment fixed key, literal dotted wildcard names, and the unsupported uppercase/underscore wildcard form without changing production behavior.
- Verification: the targeted `ConfigLoaderTest` suite passed on JDK 8; website type/check task, production build, and 1,323-link internal link check passed.
