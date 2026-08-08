# Repair the delivery-receipt example

- Status: Done
- Priority: High
- Work: Documentation

## Problem

The receipt example has broken fluent chaining and uses a removed two-argument `Recipient` constructor.

## Plan

Use the current string overloads or `RecipientBuilder`, and present one complete compilable chain for disposition notification and return receipt addresses.

## Acceptance criteria

- [x] The example compiles against 9.1.5.
- [x] It uses current recipient construction APIs.
- [x] The text distinguishes request headers from guaranteed receipt delivery.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs` now contains one complete chain using `RecipientBuilder` and the current receipt-address overloads.
- Model: `modules/core-module/src/main/java/org/simplejavamail/api/email/Recipient.java:18-41` confirms the removed two-argument constructor is no longer used.
- Builder: `modules/core-module/src/main/java/org/simplejavamail/api/email/EmailPopulatingBuilder.java` documents MDN and return-receipt request semantics and the Reply-To/From fallback.
- Verification: `npm run check`, `npm run build`, `npm run verifyLinks:internal`, and core `javadoc:javadoc` pass.
