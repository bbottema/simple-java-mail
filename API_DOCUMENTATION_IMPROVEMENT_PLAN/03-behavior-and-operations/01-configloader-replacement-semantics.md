# Clarify ConfigLoader replacement semantics

- Status: Planned
- Priority: Medium
- Work: Documentation

## Problem

The guide says loading with `addProperties=false` “clears everything.” The implementation clears cached file/programmatic values and then reapplies system properties and environment variables.

## Plan

Describe replacement as replacing loaded defaults while retaining higher-precedence runtime sources. Update every “ditch all defaults” example.

## Acceptance criteria

- [ ] No example claims system or environment values are cleared.
- [ ] Replacement and additive loading are contrasted precisely.
- [ ] A test or source anchor supports the stated precedence.

## Evidence

- Documentation: `simplejavamail.org/src/pages/configuration.hbs:305-306,603-606`
- Implementation: `modules/core-module/src/main/java/org/simplejavamail/config/ConfigLoader.java:360-419`
