# Step 4: Add a structured SMTP capability probe

- Status: Proposed
- Depends on: Existing configuration diagnostics; coordinate the capability model with Steps 1 and 5 through 9
- Proposed child issue: `Expose negotiated SMTP and TLS capabilities safely`
- Proposed classification: `enhancement`, never also `major feature`
- Release sensitivity: Additive; suitable for 10.0 or a later 10.x release after its provider-neutral shape is reviewed
- Primary modules: `core-module`, `simple-java-mail`, `angus-mail-provider-module`, website diagnostics

## Goal

Give developers a read-only diagnostic that explains what an SMTP connection negotiated instead of reducing connection testing to success or failure.

The existing `testConnection()` API remains useful as a simple health check. The probe should complement it with an immutable, redacted report that can answer why a feature or security policy is available in one environment but not another.

## Information to model

Where the provider exposes it safely, report:

- endpoint and transport strategy;
- server greeting;
- pre-TLS and post-TLS EHLO capabilities, clearly separated;
- STARTTLS availability and whether an upgrade occurred;
- TLS protocol, cipher, peer certificate identities, and identity-verification result;
- advertised authentication mechanisms and the selected mechanism when authentication is requested;
- SIZE support and advertised maximum;
- DSN, SMTPUTF8, 8BITMIME, PIPELINING, CHUNKING, BINARYMIME, and REQUIRETLS capability flags;
- the terminal connection/probe phase and sanitized failure when probing fails;
- timestamps or phase durations useful for diagnosis.

Do not expose credentials, authentication exchanges, certificate private material, message content, or a raw transcript by default.

## Probe semantics to settle

1. Decide whether the normal probe stops after EHLO/STARTTLS or can optionally authenticate to verify credentials.
2. Decide whether the API belongs on `Mailer`, a dedicated probe built from `SimpleJavaMail`, or both without duplicating configuration behavior.
3. Keep `testConnection()` source-compatible and document whether it delegates to the new probe internally.
4. Define behavior for logging-only mode, custom mailers, caller-owned Sessions, non-SMTP transports, and adapters that cannot inspect capabilities.
5. Do not let the diagnostic probe consume or mutate a caller's normal pooled connection silently. Either use a dedicated connection or document a safe lease/release path.
6. Distinguish advertised support from a feature actually selected for a message.
7. Discard pre-TLS capabilities for operational decisions and reissue EHLO after STARTTLS; retaining both snapshots is diagnostic history only.

## Tests first

1. Script different pre-TLS and post-TLS EHLO responses and prove only the post-TLS snapshot drives effective capabilities.
2. Cover multiline replies, duplicate extensions, parameters, mixed case, unknown extensions, and malformed capability lines.
3. Cover STARTTLS unavailable, refused, successfully negotiated, certificate-untrusted, and identity-mismatch cases.
4. Cover authenticated and non-authenticated probe modes without recording credentials or challenges.
5. Cover SIZE with and without a maximum, all capabilities named above, and no-EHLO fallback behavior.
6. Prove deterministic immutable output and safe `toString()` escaping/redaction.
7. Cover caller-owned Sessions, custom adapters with partial detail, custom mailers, proxies, and pooled Mailers.
8. Verify sync/async behavior and cleanup ordering.
9. Compile the public API on the classpath and module path; exclude callback-only or object-only values from CLI where necessary.
10. Add a manual demo using the same style as the existing connection and configuration-diagnostics demos.

## Documentation and release work

- Add a Diagnostics section showing a successful and a policy-mismatch report.
- Compare `testConnection()`, validation, rehearsal, configuration provenance, and the SMTP probe directly.
- Explain that capabilities can differ after STARTTLS and across endpoints in a cluster.
- Make clear that a successful probe does not send an email and does not prove later delivery.

## Acceptance criteria

- [ ] One immutable report exposes all facts the provider can determine without raw provider types.
- [ ] Pre-TLS and post-TLS capabilities cannot be confused.
- [ ] Probe output is safe for ordinary logs and contains no authentication or message secrets.
- [ ] `testConnection()` retains its existing simple contract.
- [ ] Probe connections and pool leases are always closed, released, or invalidated correctly.
- [ ] Partial-capability custom adapters degrade explicitly rather than fabricating support.
- [ ] The website and demo explain the difference between configured policy and negotiated capability.

## Stop condition

If the needed Angus state is unavailable through supported APIs, stop and choose between a narrow adapter-owned subclass, an upstream provider seam, or a smaller truthful report. Do not use fragile reflection or parse debug-log text as the public diagnostic source.
