# Add the CLI argument-file subcommand

- Status: Planned
- Priority: Medium
- Work: Documentation, CLI smoke test

## Problem

The argument file contains only options and is invoked as `sjm @file`, but the CLI requires `send`, `connect`, or `validate`.

## Plan

Put `send` first in the argument file or invoke it as `sjm send @file`, then test the documented form.

## Acceptance criteria

- [ ] The argument-file example parses successfully.
- [ ] The relationship between the root command and subcommands is clear.
- [ ] The chosen form works on the supported launch scripts.

## Evidence

- Documentation: `simplejavamail.org/src/pages/cli.hbs:120-129`
- Command model: `modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/CliCommandLineProducer.java:43-54`
