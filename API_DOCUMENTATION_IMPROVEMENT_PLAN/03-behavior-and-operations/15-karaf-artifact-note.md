# Remove the obsolete Karaf artifact note

- Status: Planned
- Priority: Low
- Work: Documentation

## Problem

The page shows `karaf-module` and then claims the artifact is temporarily named `simplejavamail-karaf-feature`.

## Plan

Remove the obsolete warning and verify every Karaf coordinate against the current module POM.

## Acceptance criteria

- [ ] Only the current `karaf-module` artifact name appears.
- [ ] Coordinates match the POM and release artifacts.
- [ ] Search finds no remaining temporary-name references.

## Evidence

- Documentation: `simplejavamail.org/src/pages/modules.hbs:196-209`
- POM: `modules/karaf-module/pom.xml:14-16`
