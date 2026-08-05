# Document withDebugPrinter

- Status: Planned
- Priority: Low
- Work: Documentation

## Gap

The main diagnostics page covers built-in `SessionDebugOutput` targets but omits the Java-only `withDebugPrinter(PrintStream)` API.

## Plan

Add a compact example beside `withDebugOutput(...)`, including PrintStream ownership and why this API is excluded from the CLI.

## Acceptance criteria

- [ ] The Java-only API is discoverable from Diagnostics.
- [ ] Built-in and custom output choices are contrasted.
- [ ] Stream lifecycle responsibility is stated.

## Evidence

- API: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/MailerGenericBuilder.java:217-239`
- Existing documentation: `simplejavamail.org/src/pages/debugging.hbs:80-90`
