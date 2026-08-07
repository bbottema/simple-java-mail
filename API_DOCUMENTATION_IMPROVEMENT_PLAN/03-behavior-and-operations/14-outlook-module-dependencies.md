# Update Outlook module dependencies

- Status: Done
- Priority: Medium
- Work: Documentation, build-derived inventory

## Problem

The module page lists Kryo and kryo-serializers for Outlook conversion, but the current Outlook module directly depends on `outlook-message-parser` and no longer declares Kryo.

## Resolution

Verified the module POM and runtime dependency tree, then replaced the stale Kryo inventory with the one direct Outlook-specific dependency:
`org.simplejavamail:outlook-message-parser`. The page now explains that this parser handles Outlook OLE content through Apache POI and related conversion
libraries without freezing its complete transitive dependency tree into the website.

## Acceptance criteria

- [x] Kryo dependencies are removed from the Outlook section.
- [x] Direct dependencies match `modules/outlook-module/pom.xml`.
- [x] The transitive implementation is described without presenting an exhaustive list.

## Evidence

- Documentation: `simplejavamail.org/src/pages/modules.hbs:101-111`
- POM: `modules/outlook-module/pom.xml:25-38`
