# Resolve extra-property precedence

- Status: Done
- Priority: Medium
- Work: Code decision, tests, documentation

## Problem

The general precedence contract says system properties override environment and file values. For `simplejavamail.extraproperties.*`, current insertion order lets file values overwrite both.

## Plan

Decide whether the general precedence contract also applies to the wildcard namespace. Prefer fixing implementation ordering; otherwise document the exception prominently.

## Acceptance criteria

- [x] Precedence is intentional and covered by tests.
- [x] Fixed and wildcard property behavior is documented without contradiction.
- [x] Migration impact is noted if implementation ordering changes.

## Evidence

- Issue: [#685](https://github.com/bbottema/simple-java-mail/issues/685), linked to the original extra-property work in #279 and assigned to v9.2.0.
- Implementation: `modules/core-module/src/main/java/org/simplejavamail/config/ConfigLoader.java` now applies file, environment, then system values so later entries have the documented higher priority.
- Tests: `ConfigLoaderTest.loadPropertiesExtraPropertiesFollowStandardPrecedence` covers system-over-environment and environment-over-file collisions; all 20 `ConfigLoaderTest` tests pass on JDK 8.
- Documentation: `simplejavamail.org/src/pages/configuration.hbs` states the shared precedence contract, and `migration-notes-9.2.0.hbs` explains the duplicate-key impact.
- Release notes: `README.md`, `RELEASE.txt`, and `RELEASE_HISTORY.md` record the v9.2.0 behavior change.
