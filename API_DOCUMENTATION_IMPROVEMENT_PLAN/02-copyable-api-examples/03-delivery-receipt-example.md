# Repair the delivery-receipt example

- Status: Planned
- Priority: High
- Work: Documentation

## Problem

The receipt example has broken fluent chaining and uses a removed two-argument `Recipient` constructor.

## Plan

Use the current string overloads or `RecipientBuilder`, and present one complete compilable chain for disposition notification and return receipt addresses.

## Acceptance criteria

- [ ] The example compiles against 9.1.5.
- [ ] It uses current recipient construction APIs.
- [ ] The text distinguishes request headers from guaranteed receipt delivery.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:1262-1267`
- Model: `modules/core-module/src/main/java/org/simplejavamail/api/email/Recipient.java:18-41`
- Builder: `modules/core-module/src/main/java/org/simplejavamail/api/email/IRecipientBuilder.java`
