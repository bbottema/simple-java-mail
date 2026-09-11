# Step 5: Make authenticated plaintext fallback explicit

- Status: Proposed security review
- Depends on: Step 4's negotiated TLS/capability model is preferred but not required for initial characterization
- Proposed child issue: `Make authentication without verified TLS an explicit policy`
- Proposed classification: `security` plus `enhancement`, never `major feature` at the same time as `enhancement`
- Release sensitivity: A safer default may be behavior-changing and requires a 10.0 migration decision
- Primary modules: `core-module`, `simple-java-mail`, `angus-mail-provider-module`, OAuth2, authenticated proxy support

## Goal

Determine exactly when Simple Java Mail can send a password or bearer token over an SMTP connection that did not establish verified TLS, then replace accidental fallback with an explicit, diagnosable policy.

This is a review target, not a pre-declared vulnerability. Plain SMTP is a valid choice for unauthenticated local relays and controlled test systems. The concern is specifically authentication material combined with opportunistic STARTTLS that is absent, rejected, or fails validation.

## Characterization first

Trace and test every transport strategy:

- plain SMTP with no credentials;
- plain SMTP with credentials and STARTTLS not advertised;
- plain SMTP with credentials and STARTTLS advertised but refused;
- plain SMTP with credentials and TLS negotiation or validation failure;
- mandatory SMTP with STARTTLS;
- implicit TLS through SMTPS;
- SMTP OAuth2 and refreshable tokens;
- custom Session properties that alter authentication or STARTTLS behavior;
- caller-owned Sessions and custom mailers.

Record whether Angus attempts authentication, which mechanism it selects, whether TLS and server identity were verified first, and whether existing settings can contradict the high-level transport strategy.

## Policy to design

Prefer one explicit high-level policy over scattered booleans. It must distinguish at least:

- require verified TLS before sending authentication material;
- allow authentication without TLS only through a deliberate compatibility choice;
- no authentication configured.

Questions to settle:

1. Should verified TLS become the default whenever username/password or OAuth credentials are present?
2. Should the compatibility override apply to all authentication mechanisms or only named ones?
3. How should conflicting raw Jakarta Mail properties be handled: rejected, overridden, or reported?
4. How does the policy interact with trust-all certificates and disabled server-identity verification?
5. What can be enforced for caller-owned Sessions and custom mailers?
6. Which failure type and diagnostic details help users correct the configuration without printing secrets?

Do not equate an encrypted socket with a verified peer. The secure policy needs both an accepted TLS protocol/cipher and the configured certificate/identity checks.

## Tests first

1. Use a scripted peer to record whether AUTH is sent when STARTTLS is absent or fails.
2. Cover password, LOGIN/PLAIN-style mechanisms, XOAUTH2/OAUTHBEARER where supported, and refreshed tokens.
3. Prove mandatory strategies never downgrade.
4. Prove unauthenticated local SMTP remains available without an unsafe override.
5. Cover certificate trust failure, hostname mismatch, explicit trust exceptions, and deliberately disabled identity verification.
6. Cover malicious capability stripping between pre-TLS and post-TLS EHLO.
7. Verify diagnostics name the policy and connection state without including usernames where unnecessary, passwords, tokens, or AUTH payloads.
8. Verify Spring, property, Java-builder, CLI, and migration behavior if the policy becomes configurable.
9. Cover custom Sessions and clearly state where Simple Java Mail cannot enforce the promise.

## Documentation and migration

- Explain opportunistic versus mandatory TLS in ordinary SMTP terms.
- Include a migration example for a legacy server that intentionally authenticates without TLS.
- Recommend fixing the server or using a protected network before selecting the compatibility escape hatch.
- Cross-link trust-store and server-identity settings without suggesting that trust-all is an equivalent security control.
- Add the effective policy to configuration and SMTP capability diagnostics.

## Acceptance criteria

- [ ] The [shared architecture overview](../../docs/concurrency/inside-a-mail-send.md#phase-completion-check) has a recorded updated-or-unchanged review, including TLS/authentication ownership.
- [ ] Current authentication fallback behavior is proven for every built-in transport strategy.
- [ ] Authentication material is never sent without the selected policy being satisfied.
- [ ] Any plaintext compatibility path requires an explicit user choice and is visible in diagnostics.
- [ ] Unauthenticated local-relay use remains straightforward.
- [ ] OAuth tokens receive the same transport protection as passwords.
- [ ] Conflicting raw properties produce deterministic behavior.
- [ ] Migration notes cover every changed default.

## Stop condition

If Simple Java Mail cannot determine or enforce verified TLS for a caller-owned Session or custom mailer, scope the guarantee to built-in transports and say so explicitly. Do not claim process-wide authentication safety across opaque user implementations.
