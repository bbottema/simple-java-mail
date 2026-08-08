# Cover reply and body-editing APIs

- Status: Done
- Priority: Medium
- Work: Documentation

## Gap

The website guide omits `replyingToAll(...)`, custom HTML quoting templates, multi-recipient `withReplyTo(...)`, file-backed bodies, `appendText*`, `prependTextHTML`, and body-clearing methods.

## Plan

Expand the reply section into a compact API family overview with one complete reply-all example and one body-editing example. Link to Javadocs for overload detail.

## Acceptance criteria

- [x] Reply and reply-all behavior are both shown.
- [x] `%s` quoting-template requirements are explicit.
- [x] Plain and HTML body editing remain paired where appropriate.
- [x] Reply-To and message recipients are not conflated.

## Resolution

Expanded the Capabilities page's conversation section into a compact reply, reply-all, and forwarding reference. It now includes a custom quote-template example, paired plain/HTML body editing, file-backed bodies, clear operations, and an explicit distinction between `Reply-To` headers and outgoing recipients. The section links to the complete builder Javadocs for overload-level detail.

## Evidence

- Website guide: `simplejavamail.org/src/pages/features.hbs:1565-1727`
- Reply facade: `modules/simple-java-mail/src/main/java/org/simplejavamail/email/EmailBuilder.java:38-114`
- Reply contract: `modules/core-module/src/main/java/org/simplejavamail/api/email/EmailStartingBuilder.java:80-190`
- Reply implementation: `modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/EmailStartingBuilderImpl.java:140-177`
- Live reply-all coverage: `modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/MailerLiveTest.java:621-646`
- Body API: `modules/core-module/src/main/java/org/simplejavamail/api/email/EmailPopulatingBuilder.java:180-224,355-480,1235-1240`
