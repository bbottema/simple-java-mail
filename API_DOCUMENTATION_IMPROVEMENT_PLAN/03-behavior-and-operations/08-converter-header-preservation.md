# Describe converter header preservation accurately

- Status: Planned
- Priority: Medium
- Work: Documentation

## Problem

Converter documentation promises headers remain intact, but structural headers are deliberately filtered and reconstructed.

## Plan

State that semantic fields and non-structural custom headers are preserved where possible. Link or list ignored headers such as `Received`, `Date`, address fields, `Subject`, content headers, and `Reply-To`.

## Acceptance criteria

- [ ] “Headers intact” is removed.
- [ ] Structural reconstruction is explained.
- [ ] The documented ignored-header set matches the source enum/list.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:1377-1383`
- Filter: `modules/core-module/src/main/java/org/simplejavamail/api/internal/general/HeadersToIgnoreWhenParsingExternalEmails.java:16-52`
- Application: `modules/simple-java-mail/src/main/java/org/simplejavamail/converter/EmailConverter.java:816-821`
