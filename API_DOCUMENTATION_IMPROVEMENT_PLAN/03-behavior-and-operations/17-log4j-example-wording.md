# Describe Log4j configuration as an example

- Status: Done
- Priority: Low
- Work: Documentation

## Problem

Diagnostics says Maven users receive default Log4j2 XML configuration. The facade ships `log4j2_example.xml`, not an active default `log4j2.xml`.

## Plan

Call it an example configuration that users may copy and adapt. Make clear that SLF4J binding and logging configuration remain application choices.

## Resolution

Replaced the default-configuration claim with an explicit explanation that the application chooses its SLF4J backend and configuration. The bundled `log4j2_example.xml` resource is now presented only as a starting point that Log4j 2 users may copy and adapt.

## Acceptance criteria

- [x] “Default XML configuration” is removed.
- [x] The resource name is stated accurately.
- [x] The page does not imply Simple Java Mail selects the application logging backend.

## Evidence

- Documentation: `simplejavamail.org/src/pages/debugging.hbs:183-190`
- Resource: `modules/simple-java-mail/src/main/resources/log4j2_example.xml`
