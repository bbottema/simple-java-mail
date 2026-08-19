# Step 11: Snapshot governance and security defaults

- Status: Done
- Depends on: Steps 9 and 10
- Primary module: `simple-java-mail`
- Primary files: `EmailGovernanceImpl.java`, `DkimPrivateKeyPropertyResolver.java`, governance tests

## Goal

Build email defaults, overrides, DKIM defaults, and S/MIME defaults from the Mailer's snapshot once. Make all later governance-created email builders use the same configured factory.

## Tests first

1. Build Mailers from configurations A and B with conflicting subject, sender, reply-to, bounce, TO/CC/BCC, DSN, and body encoding defaults.
2. Cover configured S/MIME signing/encryption and every DKIM field, including `file:`, `base64:`, and unprefixed private-key forms.
3. Build a Mailer, replace the application's config reference, then send/log/validate through the old Mailer. Assert it retains A.
4. Exercise `produceEmailApplyingDefaultsAndOverrides(...)` and prove its fresh builders use A's embedded-image settings, not the conventional context or B.
5. Preserve explicit `withEmailDefaults`, `withEmailOverrides`, `withDefaultDkimSigning`, and `clearDefaultDkimSigning` precedence.
6. Verify `NO_GOVERNANCE` is genuinely empty even when a conventional config exists.
7. Capture when keystore, certificate, and private-key failures occur and keep them at Mailer construction unless Step 3 deliberately chooses earlier validation.
8. Capture secret-safe error and logging behavior.

## Implementation

1. Pass `SimpleJavaMailConfig` and the configured email-builder factory into `EmailGovernanceImpl`.
2. Replace every static ConfigLoader access with snapshot reads.
3. Build property-backed default and override Emails once during Mailer construction.
4. Use the same configured builder factory for later governed Email production.
5. Keep security material resolution out of `SimpleJavaMailConfig.toString()` and source diagnostics.
6. Decouple `DkimPrivateKeyPropertyResolver` error naming from a static property registry where useful, but retain the public property key in messages.
7. Construct `NO_GOVERNANCE` with an explicit empty config/factory.

## Acceptance criteria

- [x] `EmailGovernanceImpl` contains no static ConfigLoader read and no `EmailBuilder` call that can select another config.
- [x] Existing Mailers are unaffected by replacement snapshots.
- [x] Explicit governance builder settings retain precedence over property defaults.
- [x] DKIM and S/MIME defaults are resolved once per Mailer.
- [x] Empty governance is independent from conventional defaults.
- [x] Secret values never appear in logs, errors, or object strings.
- [x] Governance, DKIM, S/MIME, send, log, and validation tests pass.

## Completion evidence

- `EmailGovernanceImpl` now retains its Mailer's snapshot and configured email starter; its implementation Javadocs link back to the public builder contracts.
- Governance, DKIM, S/MIME, OpenPGP, send, log, validation, and snapshot-replacement tests passed in the complete facade suite.
- Diagnostic and packaged-artifact scans found none of the secret fixture values.
