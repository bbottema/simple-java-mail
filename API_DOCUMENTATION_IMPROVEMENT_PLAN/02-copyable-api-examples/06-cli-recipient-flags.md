# Restore dedicated CLI recipient flags

- Status: Done
- Priority: Medium
- Work: CLI compatibility fix, generated CLI data, regression tests

## Problem

The primary CLI example uses `--email:to`, `--email:cc`, and `--email:bcc`, but these options were accidentally removed along with the old Java recipient overloads in 9.0.0.

The retained `--email:withRecipients` option cannot replace them in a command containing more than one recipient type. Picocli combines repeated occurrences into one invocation, so three occurrences are presented as twelve arguments to a method that accepts at most four.

## Plan

Restore `--email:to`, `--email:cc`, and `--email:bcc` through an internal CLI-only builder facade. Move the retained string-parsing `withRecipients(...)` bridge to that facade as well, while keeping the object-based recipient methods on the public Java builder API. Regenerate the packaged CLI metadata and execute the published form in a subprocess smoke test.

## Acceptance criteria

- [x] The command works with the current `sjm send` CLI.
- [x] TO, CC, and BCC are all demonstrated correctly.
- [x] A smoke test guards the published command.
- [x] The dedicated methods remain outside `EmailPopulatingBuilder`.
- [x] The CLI/property string parser remains available internally but is absent from `EmailPopulatingBuilder`.

## Evidence

- Documentation: `simplejavamail.org/src/pages/cli.hbs:56-69`
- Issue: https://github.com/bbottema/simple-java-mail/issues/682
- Regression source: commit `66345568b2d12b3ce505c9aaade58b0ad0af401f`
- CLI-only facade: `modules/core-module/src/main/java/org/simplejavamail/api/internal/clisupport/CliEmailRecipientBuilder.java`
- Mapper guard: `modules/cli-module/src/test/java/org/simplejavamail/internal/clisupport/BuilderApiToPicocliCommandsMapperTest.java`
- Process smoke test: `modules/cli-module/src/test/java/org/simplejavamail/internal/clisupport/CliProcessSmokeTest.java`
