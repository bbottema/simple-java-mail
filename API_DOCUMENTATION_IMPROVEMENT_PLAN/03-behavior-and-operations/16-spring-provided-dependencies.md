# Describe Spring dependencies as provided

- Status: Done
- Priority: Low
- Work: Documentation

## Problem

The module page says Spring support adds Spring core. The POM declares Spring core, context, and beans with `provided` scope; the application/runtime supplies them.

## Plan

Describe the module as integrating with an existing Spring or Spring Boot runtime and list the provided-scope expectation.

## Resolution

Reworded the module page to explain that the application supplies `spring-core`, `spring-context`, and `spring-beans`. The page no longer suggests that adding `spring-module` brings those libraries onto the runtime classpath.

## Acceptance criteria

- [x] No text promises Spring libraries transitively.
- [x] The expected host application responsibility is clear.
- [x] Module documentation matches the POM scopes.

## Evidence

- Documentation: `simplejavamail.org/src/pages/modules.hbs:181-193`
- POM: `modules/spring-module/pom.xml:35-49`
