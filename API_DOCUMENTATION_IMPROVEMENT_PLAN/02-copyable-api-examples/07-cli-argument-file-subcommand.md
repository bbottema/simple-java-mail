# Add the CLI argument-file subcommand

- Status: Done
- Priority: Medium
- Work: Documentation, CLI smoke test

## Problem

The argument file contained only options and was invoked as `sjm @file`, but the CLI requires `send`, `connect`, or `validate`.

## Plan

Keep the self-contained `sjm @file` form and put `send` first in the argument file. Explain that Picocli expands the file after `sjm`, then exercise that form through the CLI entry point used by both generated launchers.

## Acceptance criteria

- [x] The argument-file example parses successfully.
- [x] The relationship between the root command and subcommands is clear.
- [x] The chosen form works through the CLI entry point used by the supported launch scripts.

## Evidence

- Documentation: `simplejavamail.org/src/pages/cli.hbs:115-131`
- Command model: `modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/CliCommandLineProducer.java:43-54`
- Process smoke test: `modules/cli-module/src/test/java/org/simplejavamail/internal/clisupport/CliProcessSmokeTest.java`
