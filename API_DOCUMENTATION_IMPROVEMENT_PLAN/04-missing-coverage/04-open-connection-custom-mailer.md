# Document withOpenConnection limitations

- Status: Planned
- Priority: Medium
- Work: Documentation

## Gap

The website shows `withOpenConnection(...)` but does not say it rejects custom mailers because Simple Java Mail does not own their underlying connection.

## Plan

Add the limitation next to every open-connection example and link the custom-mailer section to the normal alternative.

## Acceptance criteria

- [ ] The custom-mailer restriction appears before users copy the example.
- [ ] The reason—connection ownership—is explained.
- [ ] Features and configuration pages agree.

## Evidence

- Documentation: `simplejavamail.org/src/pages/features.hbs:466-476`
- Additional example: `simplejavamail.org/src/pages/configuration.hbs:785-810`
- Contract: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/Mailer.java:147-152`
