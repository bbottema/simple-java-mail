# Cover reply and body-editing APIs

- Status: Planned
- Priority: Medium
- Work: Documentation

## Gap

The guide omits `replyingToAll(...)`, custom HTML quoting templates, multi-recipient `withReplyTo(...)`, file-backed bodies, `appendText*`, `prependTextHTML`, and body-clearing methods.

## Plan

Expand the reply section into a compact API family overview with one complete reply-all example and one body-editing example. Link to Javadocs for overload detail.

## Acceptance criteria

- [ ] Reply and reply-all behavior are both shown.
- [ ] `%s` quoting-template requirements are explicit.
- [ ] Plain and HTML body editing remain paired where appropriate.
- [ ] Reply-To and message recipients are not conflated.

## Evidence

- Reply facade: `modules/simple-java-mail/src/main/java/org/simplejavamail/email/EmailBuilder.java:37-94`
- Reply contract: `modules/core-module/src/main/java/org/simplejavamail/api/email/EmailStartingBuilder.java:107-145`
- Body API: `modules/core-module/src/main/java/org/simplejavamail/api/email/EmailPopulatingBuilder.java:184-224,350-476,1211-1218`
