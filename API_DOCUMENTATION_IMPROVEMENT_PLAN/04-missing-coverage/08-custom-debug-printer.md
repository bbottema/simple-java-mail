# Document withDebugPrinter

- Status: Done
- Priority: Low
- Work: Documentation

## Gap

The main diagnostics page covers built-in `SessionDebugOutput` targets but omits the Java-only `withDebugPrinter(PrintStream)` API.

## Plan

Add a compact example beside `withDebugOutput(...)`, including PrintStream ownership and why this API is excluded from the CLI.

## Acceptance criteria

- [x] The Java-only API is discoverable from Diagnostics.
- [x] Built-in and custom output choices are contrasted.
- [x] Stream lifecycle responsibility is stated.

## Evidence

- `MailerGenericBuilder.withDebugPrinter(...)` now states that Simple Java Mail does not close the supplied stream and that the caller must keep it open while the Mailer can use it.
- Diagnostics now contrasts `SessionDebugOutput` with the Java-only `withDebugPrinter(PrintStream)` path and explains why properties and CLI arguments support only built-in targets.
- A try-with-resources example declares the stream before the Mailer, causing the Mailer to close first and the caller-owned stream afterwards.
- Verification: core-module Javadocs passed; website type/check task, production build, and 1,325-link internal link check passed.
