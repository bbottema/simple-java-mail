# Describe Spring dependencies as provided

- Status: Planned
- Priority: Low
- Work: Documentation

## Problem

The module page says Spring support adds Spring core. The POM declares Spring core, context, and beans with `provided` scope; the application/runtime supplies them.

## Plan

Describe the module as integrating with an existing Spring or Spring Boot runtime and list the provided-scope expectation.

## Acceptance criteria

- [ ] No text promises Spring libraries transitively.
- [ ] The expected host application responsibility is clear.
- [ ] Module documentation matches the POM scopes.

## Evidence

- Documentation: `simplejavamail.org/src/pages/modules.hbs:181-193`
- POM: `modules/spring-module/pom.xml:35-49`
