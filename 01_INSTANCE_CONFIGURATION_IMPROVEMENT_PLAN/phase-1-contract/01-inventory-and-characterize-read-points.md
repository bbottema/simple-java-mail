# Step 1: Inventory and characterize every configuration read point

- Status: Done
- Depends on: None
- Primary modules: `core-module`, `simple-java-mail`, `core-test-module`, `spring-module`, `cli-module`
- Primary production files: `ConfigLoader.java`, `TransportStrategy.java`, `EmailPopulatingBuilderImpl.java`, `MailerGenericBuilderImpl.java`, `MailerRegularBuilderImpl.java`, `EmailGovernanceImpl.java`, `MailerImpl.java`, `SimpleJavaMailSpringSupport.java`

## Goal

Turn the current timing and ownership behavior into an executable baseline before changing it. The baseline must show which values are copied at email-builder creation, mailer-builder creation, Mailer construction, Session construction, and send-time governance.

## Tests first

1. Add a lifecycle test that uses configuration A, creates an email builder and mailer builder, replaces the global values with configuration B, then builds and uses both old and new builders. Assert every observed A/B value.
2. Repeat the timeline for Mailer-level message defaults, DKIM/S/MIME defaults, embedded-image settings, extra Session properties, and opportunistic TLS.
3. Prove whether `MailerBuilder.usingSession(...)` applies configured extra Session properties or only explicit `withProperties(...)` values.
4. Characterize the public `MailerImpl.createMailSession(...)` path independently from `MailerBuilder`.
5. Characterize `EmailGovernanceImpl.produceEmailApplyingDefaultsAndOverrides(...)` after global configuration changes, including the fresh `EmailBuilder` instances it creates.
6. Characterize static `EmailConverter` and Outlook conversion paths that create email builders internally.
7. Reproduce the embedded-image `outside.base.url` and `outside.base.classpath` behavior through property files and through Spring. Record the crossed enum names separately from actual behavior.
8. Capture the mutable `TransportStrategy.SMTP.setOpportunisticTLS(...)` behavior across two Mailers and two threads.
9. Inventory every public `EmailBuilder` and `MailerBuilder` entry signature, including demos and classpath/JPMS consumer fixtures, so each removal has an equivalent `SimpleJavaMail` factory call in Step 3.
10. Prove that the opportunistic-TLS property and setter affect only plain `SMTP` and leave `SMTP_TLS`, `SMTP_OAUTH2`, and `SMTPS` unchanged.

The temporary characterization fixture may use the existing global helper, but it must be isolated and clearly marked for replacement in Step 15. It may not depend on test order.

## Deliverables

- A checked-in read-point matrix in the test package or this plan file, linked to named tests.
- Observable lifecycle tests for each row in the README read-point table.
- A list of behavior to preserve, behavior to correct, and behavior that only reflects an implementation accident.

## Acceptance criteria

- [x] Every production `ConfigLoader` read has a named test or an explicit reason why it is not observable.
- [x] At least one test demonstrates the current mixed-snapshot problem.
- [x] Late reads during governance and Session creation are covered.
- [x] Custom Session, static Session factory, CLI, Spring, and conversion entry points are included.
- [x] Every legacy builder-entry signature is paired with the fluent builder operation it currently starts.
- [x] The embedded URL/classpath mismatch is reproduced without assuming the enum names are correct.
- [x] No proposed implementation class name is asserted as behavior.
- [x] The focused baseline passes before Step 2 starts.

## Completion evidence

- Production scans account for every former static read in builders, governance, Session creation, Spring, conversion, and CLI code.
- The migrated A/B snapshot tests cover the mixed-lifecycle failure mode without asserting implementation fields.
- `ConfigLoaderTest`, `ConfigLoaderInstanceTest`, `SimpleJavaMailFactoryTest`, `MailerTest`, conversion tests, and Spring context-isolation tests cover the inventoried routes and the corrected URL/classpath mapping.

## Migration notes raised by this step

Anything that changes from the captured public timeline must be listed in Step 3. Internal movement alone does not belong in migration notes.

## Stop condition

If a read point cannot be assigned to one configuration snapshot without changing a documented feature, stop and add that decision to Step 3 before implementation continues.
