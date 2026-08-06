# Clarify ConfigLoader replacement semantics

- Status: Done
- Priority: Medium
- Work: Documentation

## Problem

The guide says loading with `addProperties=false` “clears everything.” The implementation clears cached file/programmatic values and then reapplies system properties and environment variables.

## Plan

Describe replacement as replacing loaded defaults while retaining higher-precedence runtime sources. Update every “ditch all defaults” example.

## Acceptance criteria

- [x] No example claims system or environment values are cleared.
- [x] Replacement and additive loading are contrasted precisely.
- [x] A test or source anchor supports the stated precedence.

## Evidence

- Documentation: `simplejavamail.org/src/pages/configuration.hbs` now distinguishes merging from replacement, explains that runtime sources are reapplied, and uses the correct term “system properties.”
- Implementation: `modules/core-module/src/main/java/org/simplejavamail/config/ConfigLoader.java:360-365,375-396,400-429` clears resolved values only in replacement mode and resolves system properties before environment variables before the supplied source.
- Tests: `modules/simple-java-mail/src/test/java/org/simplejavamail/config/ConfigLoaderTest.java:131-139,237-258,397-410,413-451` anchors system-property retention, additive loading, environment loading, and runtime-source precedence.
- Verification: `npm run check` and `npm run verifyLinks` passed on 6 August 2026.
