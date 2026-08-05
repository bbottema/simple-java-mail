# Update Outlook module dependencies

- Status: Planned
- Priority: Medium
- Work: Documentation, build-derived inventory

## Problem

The module page lists Kryo and kryo-serializers for Outlook conversion, but the current Outlook module directly depends on `outlook-message-parser` and no longer declares Kryo.

## Plan

Regenerate the dependency description from the current POM/dependency tree. Prefer describing why the dependency exists over maintaining a brittle full transitive list.

## Acceptance criteria

- [ ] Kryo dependencies are removed from the Outlook section.
- [ ] Direct dependencies match `modules/outlook-module/pom.xml`.
- [ ] Any transitive list is generated or clearly non-exhaustive.

## Evidence

- Documentation: `simplejavamail.org/src/pages/modules.hbs:101-111`
- POM: `modules/outlook-module/pom.xml:25-38`
