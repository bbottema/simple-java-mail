# Add the version 8 migration guide

- Status: Done
- Priority: Medium
- Work: Documentation, navigation, sitemap

## Gap

The migration index recommends moving one major version at a time but jumps from 9 to 7. Version 8 changed defaults/overrides, validation, field opt-outs, and DKIM behavior.

## Plan

Create `migration-notes-8.0.0` from release history and relevant commits/tests, add it to navigation and sitemap, and link 7 → 8 → 9.

## Acceptance criteria

- [x] Every 8.0 breaking or behavioral change has a migration note.
- [x] Migration navigation is sequential.
- [x] New page is searchable and present in `sitemap.xml`.
- [x] Examples use current APIs while explaining the old behavior.

## Evidence

- `simplejavamail.org/src/pages/migration-notes-8.0.0.hbs` covers the governance lifecycle and inspection model, field opt-outs, default S/MIME signing, converter changes, lenient validation, receipt fallbacks, CustomMailer behavior, DKIM configuration, copying fidelity, Outlook parsing, and low-level removals.
- The guide was checked against issues #446–#452, the 7.9.1-to-8.0.0 public API diff, release history, implementation tests, and the copying-fidelity fix that shipped without its own issue.
- The migration index, manifest, tracked sitemap, content plan, and 7 → 8 → 9 page sequence now include version 8.
- Related Configuration and Security examples now use the current `EmailProperty` constants and preserve property defaults when installing a programmatic S/MIME default.
- Verification: website type/check task, production build, 1,402-link internal link check, and local visual/deep-link inspection passed.
- Website commit: `322fb0c docs(migration): add the version 8 upgrade guide [skip ci]`.
