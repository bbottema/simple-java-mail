# Developing and maintaining Simple Java Mail

Start here when contributing to or maintaining Simple Java Mail. These guides cover working on the project, including local development, architectural decisions and releases.

For using the library in an application, see the [website documentation](https://www.simplejavamail.org/docs.html) and [migration notes](https://www.simplejavamail.org/migration-notes.html).

## Choose a guide

For a first code change, start with the development and coding guides, then read the architectural decisions relevant to the affected code. Run shell commands from the repository root unless a guide says otherwise.

| Task | Guide |
| --- | --- |
| Set up the supported JDKs, build, test, generate coverage or refresh CLI metadata | [Development guide](DEVELOPMENT.md) |
| Write and review code | [Coding style guide](CODING_STYLE_GUIDE.md) |
| Add or change public APIs across builders, configuration, modules, Spring and CLI | [API expansion workflow](API_EXPANSION_WORKFLOW.md) |
| Understand architectural decisions and find documentation by topic | [Architecture decisions and topic index](adr/README.md) |
| Investigate sending, concurrency and resource ownership | [Concurrency and state-machine catalogue](concurrency/README.md) |
| Handle issues, dependency updates, verification and releases | [Maintainer workflow](MAINTAINER_WORKFLOW.md) |

## Active work and evidence

- [GitHub issues](https://github.com/bbottema/simple-java-mail/issues) track ongoing work. Active implementation plans belong under `docs/plans/` while that work is being implemented or reviewed.
- The [SMTP conformance runner](../tools/smtp-conformance/README.md) documents the repeatable checks delivered under [#747](https://github.com/bbottema/simple-java-mail/issues/747), within [#722](https://github.com/bbottema/simple-java-mail/issues/722). Its first hosted run is recorded in the [verification report](research/smtp-conformance-verification.md).
- [Research reports](research/) retain investigations and measurements. Read each report's date and scope alongside the maintained guides and ADRs.
- Follow the [documentation ownership and plan lifecycle instructions](MAINTAINER_WORKFLOW.md#7-update-documentation-and-release-notes) when moving documentation or completing a plan.
