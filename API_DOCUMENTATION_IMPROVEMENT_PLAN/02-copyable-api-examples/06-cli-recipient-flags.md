# Replace removed CLI recipient flags

- Status: Planned
- Priority: Medium
- Work: Documentation, CLI smoke test

## Problem

The primary CLI example uses removed `--email:to`, `--email:cc`, and `--email:bcc` options.

## Plan

Replace them with current `--email:withRecipients` calls and verify the command through the generated CLI.

## Acceptance criteria

- [ ] The command works with the current `sjm send` CLI.
- [ ] TO, CC, and BCC are all demonstrated correctly.
- [ ] A smoke test guards the published command.

## Evidence

- Documentation: `simplejavamail.org/src/pages/cli.hbs:56-69`
- CLI-compatible API: `modules/core-module/src/main/java/org/simplejavamail/api/email/EmailPopulatingBuilder.java:528-548`
- Existing smoke test: `modules/cli-module/src/test/java/org/simplejavamail/internal/clisupport/CliProcessSmokeTest.java`
