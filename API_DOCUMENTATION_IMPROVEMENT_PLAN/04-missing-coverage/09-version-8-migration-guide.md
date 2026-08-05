# Add the version 8 migration guide

- Status: Planned
- Priority: Medium
- Work: Documentation, navigation, sitemap

## Gap

The migration index recommends moving one major version at a time but jumps from 9 to 7. Version 8 changed defaults/overrides, validation, field opt-outs, and DKIM behavior.

## Plan

Create `migration-notes-8.0.0` from release history and relevant commits/tests, add it to navigation and sitemap, and link 7 → 8 → 9.

## Acceptance criteria

- [ ] Every 8.0 breaking or behavioral change has a migration note.
- [ ] Migration navigation is sequential.
- [ ] New page is searchable and present in `sitemap.xml`.
- [ ] Examples use current APIs while explaining the old behavior.

## Evidence

- Migration index: `simplejavamail.org/src/pages/migration-notes.hbs:4-26`
- Release history: `RELEASE_HISTORY.md:226-235`
