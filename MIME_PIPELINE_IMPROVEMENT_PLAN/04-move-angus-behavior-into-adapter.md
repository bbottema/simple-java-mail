# Step 4 — Move Angus behavior into its adapter

- Status: Done
- Depends on: Step 3
- Primary areas: new Angus adapter module, `ImmutableDelegatingSMTPMessage`, `TransportRunner`, DSN and receipts

## Goal

Preserve the full current Angus behavior while confining every `org.eclipse.angus` reference to one optional provider adapter module.

Angus may still require a narrowly scoped `SMTPMessage` facade for per-message envelope and DSN settings. That inheritance is acceptable only inside this adapter: it must delegate the finalized MIME representation, must not become a cryptographic wrapper, and must never be used as pipeline state.

## Work

1. Add the Angus adapter module and register it through the provider SPI.
2. Move the `SMTPMessage` facade and all DSN constant translation into that module.
3. Move `SMTPTransport` response-code/response-text extraction out of `TransportRunner`.
4. Apply envelope sender, DSN notify/return, send-partial, submitter, and mail-extension behavior only at the final provider boundary.
5. Define and enforce the adapter's protected-content policy for Angus 8BITMIME conversion.
6. Remove the temporary Step 2 compatibility bridge and the facade module's `instanceof SMTPTransport` checks.
7. Exercise direct and batch/pooled transports against the same adapter contract.

## Acceptance criteria

- [x] Existing Angus envelope sender, DSN, and submission receipt behavior is unchanged.
- [x] `TransportRunner` contains no Angus import or type check.
- [x] No Angus object is returned to the MIME or cryptography pipeline.
- [x] A protected message cannot be recursively re-encoded even when the Session enables `mail.smtp.allow8bitmime` and the server advertises `8BITMIME`.
- [x] The adapter does not mutate a caller-owned shared Session to enforce per-message policy.
- [x] Direct, proxy, and pooled send paths select the same adapter for the same physical provider.
- [x] The old `ImmutableDelegatingSMTPMessage` in `simple-java-mail` is deleted; any necessary replacement lives only in the Angus module.

## Failure behavior

If a safe per-message suppression of Angus conversion cannot be guaranteed for a protected entity, submission must fail before writing bytes. Silently relying on a false `SMTPMessage.getAllow8bitMIME()` value is insufficient because Angus falls back to the Session property when that value is false.

## Completion evidence

- `angus-mail-provider-module` is the sole owner of Angus imports, `SMTPMessage` adaptation, DSN constant mapping, and `SMTPTransport` submission-response extraction.
- `AngusMailTransportAdapterTest` covers provider matching, delivery metadata, and stable-content `8BITMIME` suppression without changing the caller-owned Session.
- `MailerLiveTest#protectedOpenPgpSurvivesAngusEightBitMimeTransport` submits a DKIM-plus-OpenPGP message with bounce and DSN through real Angus, then verifies the captured OpenPGP signature while the Session property remains `true`.
- Existing direct, proxy, asynchronous, open-connection, and pooled/batch tests all resolve through `TransportRunner` without provider type checks.
- `ImmutableDelegatingSMTPMessage` was deleted from `simple-java-mail`; the narrowly scoped facade is private to `AngusMailTransportAdapter`.
