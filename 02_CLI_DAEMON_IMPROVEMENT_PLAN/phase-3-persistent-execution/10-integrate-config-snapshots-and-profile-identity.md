# Step 10: Integrate immutable configuration and Mailer identity

- Status: Done
- Depends on: Step 9 and completed [01 - Instance-based configuration](../../01_INSTANCE_CONFIGURATION_IMPROVEMENT_PLAN/README.md)
- Prerequisite rule: plan 01 is complete before any work in this plan starts
- Primary modules: `cli-module`, `simple-java-mail`, and existing configuration APIs from `core-module`

## Goal

Give every daemon request one immutable startup configuration plus explicit CLI overrides, and derive a complete secret-safe identity that can prove whether two requests may share one `Mailer`.

One daemon intentionally supports many effective Mailer configurations. Existing CLI mailer options implicitly select the matching cached entry; there is no new public Mailer-profile name to maintain.

## Tests first

1. Start a daemon with configuration A, change files/environment/system properties to B, and prove requests continue to use A until restart.
2. Start a replacement daemon and prove new requests use B while no object from A is retained.
3. Apply mailer CLI overrides to A and compare them with the same values supplied by the snapshot. Prove equivalent effective inputs may share and conflicting inputs never share.
4. Cover SMTP host/port/strategy, credentials, proxy, trust, verification, Session properties, debug, async executor defaults, connection-pool settings, cluster settings, governance/security defaults, and supported custom CLI properties.
5. Change only a password, OAuth token, proxy password, trust material, certificate file content, or security-default file and prove the identity changes without exposing the value.
6. Reorder commutative options and repeat overriding options. Freeze whether identity canonicalizes to final effective values or conservatively produces distinct profiles.
7. Construct two profile identities with a forced digest collision and prove full internal equality still prevents incompatible reuse.
8. Run two explicit non-CLI configured factories beside the daemon and prove no global configuration cross-talk.
9. Exercise ordinary configuration precedence and CLI-overrides-snapshot precedence from the sibling plan.
10. Assert identity, status, metrics, exceptions, and debug output contain no secret or raw security material.
11. Send through two different SMTP/credential configurations in one daemon and prove each reuses only its own Mailer. Then send the equivalent configuration through reordered inputs and prove the documented canonicalization behavior.

## Implementation

1. At daemon startup, resolve one immutable conventional `SimpleJavaMailConfig` and configured factory using the sibling plan's approved API.
2. Carry that snapshot/factory into the request executor instead of constructing `EmailStartingBuilderImpl` or `MailerRegularBuilderImpl` directly.
3. Convert supported mailer options into an internal immutable `CliMailerProfile` before building a Mailer.
4. Include every CLI-representable value that can change Mailer behavior, governance, Session construction, pooling, proxying, trust, or security defaults.
5. Canonicalize converted values where safe. For file-backed certificates or key material, identity uses the resolved content/fingerprint required by the actual builder input, not only pathname or modification time.
6. Compute a daemon-local HMAC-SHA-256 lookup digest over the canonical profile, using a key separate from IPC authentication. Retain an internal equality representation so a digest collision alone never authorizes reuse and never expose the digest as a public profile identifier.
7. Represent secret fields with secret-aware equality/fingerprint components. Never include them in `toString()`, status, logs, exception text, or metrics labels.
8. Include daemon config generation/session identity so a replacement daemon cannot accidentally treat an old profile as current.
9. Preserve explicit CLI option precedence over startup configuration.
10. Require daemon restart for configuration source changes. Do not add live mutation to the sibling immutable configuration architecture.

## Profile scope

The initial feature supports the existing CLI configuration surface and multiple effective Mailer identities in one daemon. For example, two `sjm send -d` calls with different SMTP hosts or credentials create two separate bounded registry entries in the default daemon; later equivalent calls reuse the appropriate entry automatically.

It does not add a new named-profile file format. `--daemon-instance=<name>` selects a separately running daemon for distinct startup defaults, lifecycle, logs, or isolation; it does not select a Mailer inside a daemon.

OAuth2 behavior remains explicit:

- an access token supplied by an existing CLI/property route participates in profile identity;
- a changed token produces a new Mailer profile and retires the old one according to registry policy;
- a token captured only from startup configuration requires restart to change;
- the Java-only `OAuth2AccessTokenProvider` remains excluded from CLI input;
- no executable token-provider command is introduced.

## Acceptance criteria

- [x] Every daemon request is derived from one identifiable immutable startup snapshot plus its explicit options.
- [x] No direct internal email/mailer builder construction remains in CLI execution.
- [x] Equivalent profiles may reuse; incompatible profiles never reuse.
- [x] Multiple SMTP/Mailer configurations coexist safely in one daemon without a public profile selector.
- [x] Secrets and security material affect identity without appearing in observable keys or diagnostics.
- [x] Configuration changes require replacement/restart and never mutate an existing Mailer.
- [x] CLI override precedence matches one-shot behavior and the sibling configuration plan.
- [x] Two factories/configurations can coexist without global state.
- [x] The sibling plan's CLI completion criteria are updated to the Java 17/daemon architecture when implementation begins.

## Completion evidence

- Daemon requests build through the completed instance-configuration API and combine the immutable startup snapshot with explicit CLI overrides.
- `MailerProfile` canonically covers the effective Mailer configuration, fingerprints referenced security material, and retains only a daemon-keyed HMAC rather than raw secrets or canonical bytes.
- Process tests create and reuse three compatible/incompatible profiles in one daemon; named daemon instances remain a lifecycle/isolation selector rather than a public Mailer selector.

## Stop condition

If a CLI-supported Mailer option cannot be represented deterministically before constructing the Mailer, treat that option combination as non-reusable until an identity contract exists. Never guess that two Mailers are compatible.
