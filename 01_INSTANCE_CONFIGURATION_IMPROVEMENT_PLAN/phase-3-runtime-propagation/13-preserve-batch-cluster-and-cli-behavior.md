# Step 13: Preserve batch, cluster, and CLI behavior

- Status: Done
- Depends on: Steps 10 through 12
- Primary modules: `batch-module` and `cli-module`
- Primary files: `OperationalConfig` consumers, `BatchSupport`/cluster helpers, `CliCommandLineConsumerResultHandler.java`, generated CLI metadata

> Historical gate: the Java 8 CLI evidence in this completed step predates plan 02. Plan 02 moved `cli-module` and metadata generation to Java 17, and issue #707 later moved the 10.0.0 library baseline to Java 11. The Java 8 results below remain historical evidence for this completed step.

## Goal

Carry the resolved snapshot through the two indirect consumers that currently depend on globally seeded builders: batch cluster registration and CLI command execution.

## Tests first

### Batch and clusters

1. Build two factories with different global pool defaults and different cluster-specific maps.
2. Register Mailers for distinct cluster UUIDs and assert each effective pool configuration.
3. Cover alias-resolved UUIDs, direct UUID keys, partial cluster overrides, and fallback to each Mailer's global pool settings.
4. Assert a random fallback cluster key is not accidentally shared by builders using one snapshot.
5. Exercise concurrent registration from A and B and verify no config map is mutated by the batch module.

### CLI

1. Start a CLI command through `SimpleJavaMail.fromDefaults()` with a conventional property file and assert the email and Mailer builders share one snapshot.
2. Cover command-line builder options overriding property defaults.
3. Cover send, validate, and connect paths.
4. Add the new opportunistic-TLS builder option to CLI generation if it is intended for CLI use.
5. Assert config/factory object methods are excluded from CLI option generation.
6. Run a command after creating a separate explicit non-CLI factory and prove there is no cross-talk.

## Implementation

1. Keep cluster configs in the immutable snapshot and copy them into `OperationalConfig`.
2. Do not let batch registration retain or mutate a config-source map.
3. Update CLI startup to call `SimpleJavaMail.fromDefaults()` once per command and request both builder types from that factory.
4. Stop constructing `EmailStartingBuilderImpl` and `MailerRegularBuilderImpl` directly in the CLI handler.
5. Regenerate `cli.data` and `therapi.data` only in Step 16 on Java 8 after all builder signatures settle.
6. Update mechanism documentation later in Step 17, not during production movement.

## Acceptance criteria

- [x] Batch cluster behavior matches #565/#618 for each independent snapshot.
- [x] Cluster-specific maps are immutable and never shared as mutable builder state.
- [x] CLI email and Mailer creation use one snapshot per command.
- [x] CLI flags retain precedence over config defaults.
- [x] All three CLI commands pass focused tests.
- [x] No direct internal builder constructor remains in CLI production code.
- [x] Batch and CLI focused module builds pass on Java 8.

## Completion evidence

- Cluster alias, direct UUID, partial override, global fallback, and independent snapshot behavior passed in batch/facade tests.
- CLI production creates one `SimpleJavaMail.fromDefaults()` context per command and obtains both builders from it; source scans find no direct implementation constructor.
- All 28 CLI tests and the batch module passed in the Java 8 and Java 21 reactors, and Java 8 regenerated `cli.data` and `therapi.data`.
