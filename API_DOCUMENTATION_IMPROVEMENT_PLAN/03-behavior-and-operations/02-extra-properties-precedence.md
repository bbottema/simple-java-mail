# Resolve extra-property precedence

- Status: Planned
- Priority: Medium
- Work: Code decision, tests, documentation

## Problem

The general precedence contract says system properties override environment and file values. For `simplejavamail.extraproperties.*`, current insertion order lets file values overwrite both.

## Plan

Decide whether the general precedence contract also applies to the wildcard namespace. Prefer fixing implementation ordering; otherwise document the exception prominently.

## Acceptance criteria

- [ ] Precedence is intentional and covered by tests.
- [ ] Fixed and wildcard property behavior is documented without contradiction.
- [ ] Migration impact is noted if implementation ordering changes.

## Evidence

- Documentation: `simplejavamail.org/src/pages/configuration.hbs:56-57,289-295`
- Implementation: `modules/core-module/src/main/java/org/simplejavamail/config/ConfigLoader.java:452-455`
