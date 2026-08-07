# Update bounce-address implementation details

- Status: Done
- Priority: Medium
- Work: Website documentation and Javadocs

## Problem

The bounce section says `withBounceTo` sets `mail.smtp.from` on the shared Session. Current behavior applies envelope-from per message through a specialized SMTP message wrapper.

## Plan

Describe the observable envelope-from result without anchoring users to the obsolete Session implementation detail. Explain the difference from `Reply-To`.

## Acceptance criteria

- [x] The shared Session is no longer described as mutated.
- [x] Per-message envelope-from semantics are clear.
- [x] Bounce and Reply-To purposes are distinguished.

## Resolution

- Replaced the obsolete `mail.smtp.from`/shared-Session explanation with the current per-message SMTP envelope behavior.
- Distinguished the transport-level envelope sender from the `Reply-To` message header and explained how `Return-Path` relates to it.
- Removed the display-name example because SMTP `MAIL FROM` carries only the address.
- Updated the public builder Javadocs with the same contract. No sending behavior changed.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs`
- Public API: `modules/core-module/src/main/java/org/simplejavamail/api/email/EmailPopulatingBuilder.java`
- Implementation: `modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/SpecializedMimeMessageProducer.java`
