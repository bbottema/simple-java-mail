# Update both reply content alternatives

- Status: Planned
- Priority: High
- Work: Documentation

## Problem

`replyingTo(...)` creates plain-text and HTML alternatives, but the example prepends the new reply only to plain text. HTML-capable clients can therefore show only the quoted original.

## Plan

Add `prependTextHTML(...)`, or explicitly clear the HTML alternative when demonstrating a plain-only reply. Explain why both representations must be updated.

## Acceptance criteria

- [ ] The reply is present in both body alternatives.
- [ ] The example renders correctly for plain-text and HTML clients.
- [ ] The guidance links to the broader reply API coverage item.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:1519-1523`
- Contract: `modules/core-module/src/main/java/org/simplejavamail/api/email/EmailStartingBuilder.java:139-145`
- Implementation: `modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/EmailStartingBuilderImpl.java:151-157`
