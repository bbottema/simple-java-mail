# Step 5: Make authenticated plaintext fallback explicit

- Status: Construction-time guard implemented, verified and accepted on 15 September 2026 under #735; existing opportunistic default retained
- Depends on: Step 4 provides related diagnostics; neither characterization nor sending requires a probe preflight
- Child issue: [#735 — Reject extra properties that disable mandatory STARTTLS](https://github.com/bbottema/simple-java-mail/issues/735), milestone 10.0.0; complete and accepted, unreleased
- Classification: `security` plus `enhancement`, never `major feature` at the same time as `enhancement`
- Release sensitivity: No default or port change; stricter handling of conflicting properties may require 10.0.0 migration guidance
- Primary modules: `core-module`, `simple-java-mail`, `angus-mail-provider-module`, OAuth2, authenticated proxy support

## Goal

Document when the existing transport strategies allow authentication without TLS, preserve the opportunistic default, and design how to handle raw properties that contradict a mandatory strategy. Use the existing strategy API rather than introducing another security policy type.

This is a review target, not a pre-declared vulnerability. Opportunistic SMTP remains a supported usability choice, with or without credentials. Its plaintext fallback when STARTTLS is absent must be clearly distinguished from a failed TLS upgrade, which stops the connection.

## Characterization checkpoint: 15 September 2026

The [wire-level findings and maintainer decision](05a-authentication-tls-characterization.md) distinguish missing STARTTLS from a failed upgrade, reproduce raw-property overrides, and cover password/OAuth2 authentication, certificate checks, pooling and execution modes. The characterization was test-only. A subsequent documentation pass corrects the SMTP Javadoc and clarifies existing behavior on the website; no production default, public signature or runtime security policy has changed.

The probe is not a send preflight. Any future enforcement must apply on the connection that carries the credentials and message, including newly opened pooled connections. A report from a separate probe cannot authorize a later send.

## Accepted default decision: 15 September 2026

- Keep `SMTP` as the default, with opportunistic STARTTLS enabled, whether or not username/password credentials are supplied.
- Try STARTTLS when the server advertises it. When it is absent, allow the existing plaintext path, including authentication. No additional compatibility opt-in is required.
- Do not continue in plaintext after a refused STARTTLS command, failed handshake, untrusted certificate, or hostname mismatch. "Auto-fallback" does not mean ignoring a failed TLS upgrade or validation check.
- Leave mandatory TLS to the caller's existing strategy choice: `SMTP_TLS` requires STARTTLS and `SMTPS` uses implicit TLS. Keep `SMTP_OAUTH2`'s existing mandatory STARTTLS behavior.
- Do not introduce a credential-dependent default, a new policy enum, or probe-based strategy selection. Preserve current port defaults.

The behavioral work concerns configuration conflicts, not another decision about the default. The [construction-time guardrail](05b-mandatory-starttls-configuration-guardrail.md) is implemented, verified and accepted. This completes Phase 3 alongside the accepted probe and execution-view work.

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

## Accepted strategy-hardening design

The original characterization showed that raw `mail.smtp.starttls.required=false` could weaken `SMTP_TLS` and `SMTP_OAUTH2`. The implementation rejects that override while retaining the accepted opportunistic default.

The implementation rejects the reproduced mandatory-STARTTLS override during regular Mailer construction, leaving trust exceptions, caller-owned Sessions and CustomMailer unchanged:

1. Reject rather than rewrite the final extra property before runtime resources are set up.
2. Leave explicit trust exceptions and disabled server-identity verification unchanged. An encrypted socket and a verified peer are different guarantees.
3. Exempt caller-owned Sessions and `CustomMailer`; do not add a runtime check or claim enforcement across custom implementations.
4. Name the strategy and conflicting property, suggest removing the override or choosing opportunistic SMTP, and never render the supplied value or credentials.

Do not add another policy getter to the probe report. The selected transport strategy and the observed connection details answer different questions; explain the distinction using the existing APIs.

## Tests first

1. Use a scripted peer to record whether AUTH is sent when STARTTLS is absent or fails.
2. Cover password, LOGIN/PLAIN-style mechanisms, XOAUTH2/OAUTHBEARER where supported, and refreshed tokens.
3. Prove mandatory strategies do not silently downgrade, including the agreed handling of conflicting properties once designed.
4. Preserve the default opportunistic behavior with and without credentials, and prove that a failed TLS upgrade never becomes a plaintext retry.
5. Cover certificate trust failure, hostname mismatch, explicit trust exceptions, and deliberately disabled identity verification.
6. Cover malicious capability stripping between pre-TLS and post-TLS EHLO.
7. Verify diagnostics explain configuration conflicts and connection state without including usernames where unnecessary, passwords, tokens, or AUTH payloads.
8. Verify the chosen conflict handling through existing Spring, property, Java-builder and CLI configuration paths, with migration coverage if behavior changes.
9. Cover custom Sessions and clearly state where Simple Java Mail cannot enforce the promise.

## Documentation and migration

- Explain the default opportunistic behavior and show the existing explicit mandatory-TLS strategies alongside it.
- State that missing STARTTLS permits plaintext authentication under `SMTP`, but a refused or failed upgrade does not.
- Explain that an attacker can suppress an opportunistic upgrade; users who require encrypted submission must choose a mandatory strategy.
- Correct the SMTP Javadoc: choosing opportunistic TLS does not disable certificate validation when TLS is used.
- Cross-link trust-store and server-identity settings without suggesting that trust-all is an equivalent security control.
- Explain property precedence and link the existing configuration/probe diagnostics, without treating a separate probe as authorization for sending.
- Add migration guidance only for actual behavior changes, such as newly rejected conflicting properties. Do not invent a default-change migration or a new plaintext opt-in.

## Acceptance criteria

- [x] The [shared architecture overview](../../docs/concurrency/inside-a-mail-send.md#phase-completion-check) has a recorded updated-or-unchanged review, including TLS/authentication ownership.
- [x] Current authentication fallback behavior is proven for every built-in transport strategy.
- [x] `SMTP` remains the opportunistic default with and without credentials; no new compatibility opt-in is required.
- [x] Missing STARTTLS and failed TLS negotiation/validation have distinct, documented behavior.
- [x] Disabling mandatory STARTTLS through regular-builder extra properties is rejected under the agreed design; custom-provider and mutable-Session boundaries are documented.
- [x] Unauthenticated local-relay use remains straightforward.
- [x] `SMTP_OAUTH2` retains its existing mandatory TLS protection before token transmission.
- [x] Conflicting raw properties produce deterministic behavior.
- [x] Defaults and default ports are unchanged; migration notes cover any stricter configuration handling actually introduced.

## Stop condition

If Simple Java Mail cannot determine or enforce verified TLS for a caller-owned Session or custom mailer, scope the guarantee to built-in transports and say so explicitly. Do not claim process-wide authentication safety across opaque user implementations.
