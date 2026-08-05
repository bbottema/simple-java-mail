# Update bounce-address implementation details

- Status: Planned
- Priority: Medium
- Work: Documentation

## Problem

The bounce section says `withBounceTo` sets `mail.smtp.from` on the shared Session. Current behavior applies envelope-from per message through a specialized SMTP message wrapper.

## Plan

Describe the observable envelope-from result without anchoring users to the obsolete Session implementation detail. Explain the difference from `Reply-To`.

## Acceptance criteria

- [ ] The shared Session is no longer described as mutated.
- [ ] Per-message envelope-from semantics are clear.
- [ ] Bounce and Reply-To purposes are distinguished.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:1475-1489`
- Implementation: `modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/SpecializedMimeMessageProducer.java:119-125`
