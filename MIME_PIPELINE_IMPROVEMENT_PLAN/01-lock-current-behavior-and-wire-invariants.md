# Step 1 — Lock current behavior and wire invariants

- Status: Done
- Depends on: None
- Primary areas: MIME conversion, DKIM, S/MIME, bounce handling, DSN, transport receipts

## Goal

Create a characterization suite and a written wire contract before changing message construction. Existing subtype assertions are useful as evidence of the current design, but the lasting contract must describe observable MIME bytes and delivery behavior rather than implementation classes.

## Work

1. Capture representative outgoing messages for plain text, alternative content, mixed attachments, embedded resources, calendars, and forwarded messages.
2. Cover generated and explicit Message-ID behavior before and after `saveChanges()`.
3. Cover S/MIME signing, encryption, sign-then-encrypt, DKIM, and the current S/MIME-plus-DKIM ordering.
4. Cover bounce address and every supported DSN notify/return combination independently from MIME headers.
5. Capture submission-response behavior for Angus and the current null response for an unrecognized transport.
6. Add a transport-path regression showing what happens when the server advertises `8BITMIME` and `mail.smtp.allow8bitmime=true`.
7. Record the intended 10.0.0 ordering and the initial rejection of simultaneous S/MIME and OpenPGP configuration.

## Acceptance criteria

- [x] Tests assert CRLF-sensitive wire output or stable digests where exact bytes matter.
- [x] Explicit Message-ID, bounce address, DSN, and receipt semantics have behavior-level assertions.
- [x] The test matrix includes DKIM with S/MIME and delivery metadata, not only each feature in isolation.
- [x] The `allow8bitmime=true` case demonstrates the mutation risk that later steps must remove.
- [x] Tests distinguish public behavior from temporary `MimeMessage`/`SMTPMessage` subtype shape.
- [x] The full characterization suite passes before production refactoring begins.

## Completion evidence

- `MailerTest#testDKIMPriming`, `MailerTest#testDKIMPrimingAndSmimeCombo`, and the existing S/MIME live/read tests lock the supported protection order and Message-ID behavior.
- `DeliveryStatusNotificationMimeMessageProducerTest` now asserts provider-neutral envelope and DSN values, while the receipt tests cover synchronous, asynchronous, batch, and provider-response behavior.
- `MailerLiveTest#protectedOpenPgpSurvivesAngusEightBitMimeTransport` captures the real Angus SMTP path with a quoted-printable UTF-8 protected entity and `mail.smtp.allow8bitmime=true`.
- The legacy Outlook expectations that failed during the refactor were reproduced against clean `HEAD`; they were stale governance assertions caused by Outlook conversion intentionally using `ignoringDefaults()`, not pipeline regressions.
- The complete Java 8 reactor passes with the characterization and final regression suite enabled.
