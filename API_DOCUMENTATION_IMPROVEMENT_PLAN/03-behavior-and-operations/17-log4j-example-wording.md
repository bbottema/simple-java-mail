# Describe Log4j configuration as an example

- Status: Planned
- Priority: Low
- Work: Documentation

## Problem

Diagnostics says Maven users receive default Log4j2 XML configuration. The facade ships `log4j2_example.xml`, not an active default `log4j2.xml`.

## Plan

Call it an example configuration that users may copy and adapt. Make clear that SLF4J binding and logging configuration remain application choices.

## Acceptance criteria

- [ ] “Default XML configuration” is removed.
- [ ] The resource name is stated accurately.
- [ ] The page does not imply Simple Java Mail selects the application logging backend.

## Evidence

- Documentation: `simplejavamail.org/src/pages/debugging.hbs:183-190`
- Resource: `modules/simple-java-mail/src/main/resources/log4j2_example.xml`
