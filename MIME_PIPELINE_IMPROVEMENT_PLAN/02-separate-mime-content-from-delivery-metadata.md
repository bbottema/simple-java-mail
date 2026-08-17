# Step 2 — Separate MIME content from delivery metadata

- Status: Done
- Depends on: Step 1
- Primary areas: `SpecializedMimeMessageProducer`, converter/send boundary, bounce and DSN models

## Goal

Stop representing SMTP envelope and DSN choices by changing the type of the MIME message. Introduce an internal prepared-mail model that carries content and delivery instructions as separate values.

Working names are `PreparedMail` for the complete send input and `DeliveryEnvelope` for resolved recipients, envelope sender, and provider-neutral DSN choices. Names are internal until the provider SPI review in Step 3.

## Work

1. Make MIME producers return only the provider-neutral MIME result.
2. Resolve normal versus override recipients in the prepared-mail layer.
3. Move bounce address and DSN mapping into provider-neutral values; do not store Angus integer constants outside the Angus adapter.
4. Preserve the public `EmailConverter.emailToMimeMessage` contract: envelope and DSN information remain outside the MIME document.
5. Route the internal Mailer send path through `PreparedMail` while keeping a narrowly scoped compatibility bridge until Step 4 supplies the Angus adapter.
6. Keep maximum-size checks and logging attached to the actual message representation that will be sent, not an earlier intermediate representation.

## Acceptance criteria

- [x] `SpecializedMimeMessageProducer` imports neither `SMTPMessage` nor Angus DSN constants.
- [x] MIME construction does not create `ImmutableDelegatingSMTPMessage`.
- [x] `PreparedMail` contains a Jakarta `MimeMessage`, resolved transport recipients, and a provider-neutral `DeliveryEnvelope`.
- [x] Bounce and DSN tests assert values on `DeliveryEnvelope` rather than an Angus subtype.
- [x] Public MIME conversion remains usable without a transport provider.
- [x] The compatibility bridge is isolated at the final send edge and explicitly scheduled for removal in Step 4.
- [x] All Step 1 behavior tests remain green.

## Non-goals

This step does not yet remove Angus from the build and does not define cryptographic finalization. It creates the data boundary required for those later changes.

## Completion evidence

- `DeliveryEnvelope` contains the resolved envelope sender and provider-neutral DSN model; `PreparedMail` contains the `MimeMessage`, transport recipients, envelope, and optional submission state.
- `SessionBasedEmailToMimeMessageConverter#convertAndLogPreparedMail` is the single Mailer conversion boundary, and `TransportRunner` consumes `PreparedMail` directly.
- `DeliveryStatusNotificationMimeMessageProducerTest` asserts bounce, notify, and return values on `DeliveryEnvelope`.
- Both provider-neutral consumer fixtures convert MIME without an implementation provider; the full reactor keeps all Step 1 tests green.
- The temporary send-edge bridge was removed as part of Step 4.
