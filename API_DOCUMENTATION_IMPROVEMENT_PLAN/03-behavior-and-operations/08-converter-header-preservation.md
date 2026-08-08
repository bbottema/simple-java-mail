# Describe converter header preservation accurately

- Status: Done
- Priority: Medium
- Work: Documentation

## Problem

Converter documentation promises headers remain intact, but structural headers are deliberately filtered and reconstructed.

## Plan

State that semantic fields and non-structural custom headers are preserved where possible. Link or list ignored headers such as `Received`, `Date`, address fields, `Subject`, content headers, and `Reply-To`.

## Acceptance criteria

- [x] “Headers intact” is removed.
- [x] Structural reconstruction is explained.
- [x] The documentation links to the canonical ignored-header list.

## Resolution

- Replaced the verbatim-preservation claim with the actual conversion boundary.
- Distinguished semantic Email fields and preserved custom headers from reconstructed MIME structure and omitted transport history.
- Linked the source-controlled filter so the exact header set does not have to be duplicated in prose.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:1424-1434`
- Filter: `modules/core-module/src/main/java/org/simplejavamail/api/internal/general/HeadersToIgnoreWhenParsingExternalEmails.java:20-63`
- Application: `modules/simple-java-mail/src/main/java/org/simplejavamail/converter/EmailConverter.java:827-832`
