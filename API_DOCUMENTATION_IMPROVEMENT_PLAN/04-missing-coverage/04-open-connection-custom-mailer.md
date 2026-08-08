# Document withOpenConnection limitations

- Status: Done
- Priority: Medium
- Work: Documentation

## Gap

The website shows `withOpenConnection(...)` but does not say it rejects custom mailers because Simple Java Mail does not own their underlying connection.

## Plan

Add the limitation next to every open-connection example and link the custom-mailer section to the normal alternative.

## Acceptance criteria

- [x] The custom-mailer restriction appears before users copy the example.
- [x] The reason—connection ownership—is explained.
- [x] Features and configuration pages agree.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs`, `configuration.hbs`, and `migration-notes-9.0.0.hbs`
- Custom-mailer guidance: `simplejavamail.org/src/pages/features.hbs#section-custom-mailer`
- Website commit: `a839739` on `codex/website-relaunch`
- Contract: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/Mailer.java`
- Runtime guard: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/SendMailsWithOpenConnectionClosure.java`
- Regression coverage: `MailerTest.testOpenConnection_sendEmails_rejectsCustomMailer`
- Verification: website check/build and 1,318 internal links
