# Update both reply content alternatives

- Status: Done
- Priority: High
- Work: Documentation

## Problem

`replyingTo(...)` creates plain-text and HTML alternatives, but the example prepends the new reply only to plain text. HTML-capable clients can therefore show only the quoted original.

## Plan

Add `prependTextHTML(...)`, or explicitly clear the HTML alternative when demonstrating a plain-only reply. Explain why both representations must be updated.

## Acceptance criteria

- [x] The reply is present in both body alternatives.
- [x] The example renders correctly for plain-text and HTML clients.
- [x] The guidance links to the broader reply API coverage item.

## Related follow-up

- [Cover reply and body-editing APIs](../04-missing-coverage/02-reply-and-body-api.md)

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs` now prepends the reply to both plain-text and HTML alternatives and explains why they stay paired.
- Contract: `modules/core-module/src/main/java/org/simplejavamail/api/email/EmailStartingBuilder.java:139-145` documents that replies start with both alternatives.
- Implementation: `modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/EmailStartingBuilderImpl.java:151-157` initializes both quoted bodies.
- Verification: `npm run check`, `npm run build`, and `npm run verifyLinks:internal` pass; generated HTML preserves the escaped HTML reply string.
