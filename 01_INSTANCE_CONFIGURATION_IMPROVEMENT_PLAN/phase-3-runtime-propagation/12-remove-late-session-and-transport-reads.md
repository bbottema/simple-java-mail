# Step 12: Remove late Session and transport reads

- Status: Done
- Depends on: Steps 10 and 11
- Primary modules: `core-module` and `simple-java-mail`
- Primary files: `TransportStrategy.java`, `MailerImpl.java`, `OperationalConfig.java`, `OperationalConfigImpl.java`, Mailer builder API

## Goal

Eliminate the final runtime configuration reads during Session creation. Extra Jakarta Mail properties and opportunistic TLS must already belong to the builder/Mailer snapshot.

## Tests first

1. Build plain-`SMTP` Mailers from A and B with conflicting extra Session properties and opportunistic TLS. Inspect their Sessions concurrently.
2. Prove explicit `withProperties(...)` values beat configured `simplejavamail.extraproperties.*` values.
3. Characterize and preserve the approved behavior for caller-provided Sessions. If configured extras are intentionally not applied there, assert that boundary clearly.
4. Exercise `SMTP`, `SMTP_TLS`, `SMTP_OAUTH2`, and `SMTPS`. Assert the escape hatch only changes plain `SMTP`; mandatory STARTTLS and implicit TLS behavior must remain fixed.
5. Replace `TransportStrategy.SMTP.setOpportunisticTLS(...)` tests with Mailer-builder and snapshot tests.
6. Cover the public static Session creation helper and its explicit 10.0 replacement.
7. Build an old Mailer, create a new snapshot, and prove the old Session cannot observe the replacement.
8. Assert `TransportStrategy` constants are stateless under parallel use.

## Implementation

1. Add `withOpportunisticTLS(...)` to the regular Mailer builder as the working replacement signature. Store the resolved option internally with the Mailer, but do not add a general-purpose public runtime getter.
2. Remove the mutable field and setter from `TransportStrategy.SMTP`.
3. Make `generateProperties()` deterministic, or pass the resolved transport option explicitly without storing it on the enum.
4. Carry a detached copy of configured extra Session properties into Mailer construction.
5. Merge Session properties in one documented order: transport defaults, configured extras, server settings, explicit builder properties, then required Simple Java Mail runtime settings where those must win for correctness.
6. Replace `MailerImpl.createMailSession(...)` global behavior with the explicit API frozen in Step 3. Keep an old signature only if its behavior can be deterministic and clearly documented.
7. Remove the static ConfigLoader import from `MailerImpl` and `TransportStrategy`.

## Opportunistic TLS scope

Issue #105 introduced this as an unusual opt-out from STARTTLS attempts for plain `SMTP`. Keep that scope:

- `true` means plain `SMTP` attempts STARTTLS when advertised and may fall back to plaintext;
- `false` means plain `SMTP` does not attempt STARTTLS;
- the option has no effect on `SMTP_TLS`, `SMTP_OAUTH2`, or `SMTPS`;
- it is not a generic switch for weakening transport security;
- the existing `simplejavamail.opportunistic.tls` key remains supported and seeds the regular Mailer builder;
- an explicit builder call beats the snapshot value.

The exact null/reset shape is frozen in Step 3, but the scope above is not optional.

## Migration impact

- `TransportStrategy.SMTP.setOpportunisticTLS(...)` is a source break with a regular Mailer-builder replacement that is explicitly limited to plain `SMTP`.
- Direct Session factory callers that relied on global extra properties need an explicit config or properties argument.
- Any merge-order difference between configured extras and builder `withProperties` must be documented if observable.

## Acceptance criteria

- [x] No Session or transport code reads mutable global config.
- [x] `TransportStrategy` has no mutable per-enum configuration.
- [x] Opportunistic TLS cannot alter mandatory or implicit TLS strategies.
- [x] Explicit builder configuration beats the snapshot property for plain `SMTP`.
- [x] A/B Sessions retain independent extra properties and TLS behavior.
- [x] Explicit builder Session properties keep their documented precedence.
- [x] Custom Session behavior is explicit and tested.
- [x] All transport strategies and OAuth2 tests pass.

## Completion evidence

- `TransportStrategy` is stateless. The sole public escape hatch is `MailerRegularBuilder.withOpportunisticTLS(boolean)`, documented on the builder interface as affecting plain `SMTP` only.
- `TransportStrategyOpportunisticTlsTest` passed on Java 8 and Java 21 for SMTP, SMTP_TLS, SMTP_OAUTH2, and SMTPS behavior.
- Session-property, custom-Session, explicit-precedence, all-transport, and OAuth2 tests passed in both complete reactors.
