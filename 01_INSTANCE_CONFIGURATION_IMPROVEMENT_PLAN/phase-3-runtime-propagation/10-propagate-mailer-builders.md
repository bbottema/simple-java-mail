# Step 10: Propagate configuration through mailer builders

- Status: Done
- Depends on: Step 8
- Primary module: `simple-java-mail`
- Primary files: `MailerBuilder.java`, `MailerGenericBuilderImpl.java`, `MailerRegularBuilderImpl.java`, `MailerFromSessionBuilderImpl.java`

## Goal

Seed every Mailer builder from exactly one immutable snapshot and preserve the rule that explicit fluent calls win. Do not resolve Session-time or governance-time values yet; those move in Steps 11 and 12.

## Tests first

For configurations A and B, cover:

1. SMTP host, port, username, password, transport strategy, and custom SSL factory class.
2. Proxy host, port, credentials, and bridge port.
3. Debug mode/output, validation, timeout, local bind address/port, SMTP client hostname, trust settings, transport logging, and thread-pool settings.
4. Global pool settings, configured cluster key, and cluster-specific settings.
5. Regular and caller-provided Session builders.
6. Every explicit fluent override after snapshot seeding.
7. Two fresh builders from the same config when no cluster key is configured. They must receive different random fallback UUIDs so they do not cluster accidentally.
8. A configured cluster UUID shared intentionally by more than one builder.
9. Mutation of source `Properties` after snapshot and builder creation.

## Implementation

1. Add a final config reference or a resolved seed object to the generic builder implementation.
2. Replace constructor static reads with config access.
3. Keep runtime-only defaults in the builder: random cluster key, strict validator, executor selection, and other per-builder objects.
4. Add config-aware constructors used by the configured factory. Keep no-arg constructors only as temporary bridges until Step 15; they are not part of the approved 10.0 API.
5. Apply snapshot defaults before fluent methods so Java calls continue to win.
6. Copy maps and lists out of the snapshot before placing them in mutable builder fields.
7. Do not store SMTP or proxy passwords in diagnostic `toString()` output.

## Acceptance criteria

- [x] Mailer builders contain no static ConfigLoader read.
- [x] Explicit Java builder calls beat snapshot values for every field family.
- [x] A missing cluster key remains per-builder random, not per-config random.
- [x] Regular and custom-Session builders both use their factory's snapshot where current semantics require it.
- [x] Config A and B builders can be created concurrently without cross-talk.
- [x] Every constructor intended to survive Step 15 requires an explicit snapshot or resolved seed.
- [x] Focused mailer builder tests pass on Java 8.

## Completion evidence

- Regular and Session Mailer builder implementations require an explicit snapshot and copy its defaults into builder-owned mutable state.
- Mailer configuration, proxy, local-bind, cluster, Session, and fluent-precedence tests pass on Java 8, including fresh random fallback cluster identifiers.
- Production scans find no static loader read or default-selecting no-argument implementation constructor.
