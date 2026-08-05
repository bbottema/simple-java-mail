# Update the CLI exec-plugin version

- Status: Planned
- Priority: Low
- Work: Documentation, release-update automation

## Problem

The CLI Maven example pins `exec-maven-plugin` 1.6.0 while the current project uses 3.6.3.

## Plan

Update the example and decide whether plugin versions should come from the website manifest/release inventory or a lightweight POM-derived check.

## Acceptance criteria

- [ ] The published plugin version is current and supported.
- [ ] The example runs successfully.
- [ ] The release update list includes this pinned version or validates it automatically.

## Evidence

- Documentation: `simplejavamail.org/src/pages/cli.hbs:204-222`
- Current build: `modules/cli-module/pom.xml:131-132`
