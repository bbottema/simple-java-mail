# Update the CLI exec-plugin version

- Status: Done
- Priority: Low
- Work: Documentation

## Problem

The CLI Maven example pins `exec-maven-plugin` 1.6.0 while the current project uses 3.6.3.

## Plan

Update the example and decide whether plugin versions should come from the website manifest/release inventory or a lightweight POM-derived check.

## Resolution

Updated the CLI Maven example from `exec-maven-plugin` 1.6.0 to 3.6.3, matching the version used by the CLI module itself. This build-tool version is maintained with the example rather than added to the website release inventory: it changes independently of Simple Java Mail releases, while an automated cross-repository POM check would unnecessarily couple the website build to the library repository.

## Acceptance criteria

- [x] The published plugin version matches the current CLI module build.
- [x] The example runs successfully.
- [x] The maintenance decision is recorded without expanding release-update automation.

## Evidence

- Documentation: `simplejavamail.org/src/pages/cli.hbs:204-222`
- Current build: `modules/cli-module/pom.xml:131-132`
