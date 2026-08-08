# Replace stale source-Javadoc website links

- Status: Done
- Priority: Medium
- Work: Source Javadocs, CLI footer, link checking

## Defect

Several source Javadocs and the CLI footer still use the removed hash-router URLs such as `/#/configuration`, `/#/cli`, and homepage section fragments.

## Plan

Replace them with current canonical routes and regenerate Javadocs/help. Cover the corrected CLI footer with focused help assertions; a broader Java-source scanning guard is deliberately out of scope.

## Acceptance criteria

- [x] Spring support links to `/configuration.html#section-spring-support`.
- [x] CLI help links to `/cli.html`.
- [x] DKIM and S/MIME source links point to Security sections.
- [x] Focused CLI help assertions reject the old `simplejavamail.org/#/cli` footer.

## Evidence

- `modules/spring-module/src/main/java/org/simplejavamail/springsupport/SimpleJavaMailSpringSupport.java:27`
- `modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/CliCommandLineProducer.java:148`
- `modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/CliCommandLineConsumerUsageHelper.java:59`
- `modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/SpecializedMimeMessageProducer.java:30-31`
- Spring support now points directly to `configuration.html#section-spring-support`; DKIM and S/MIME point to their existing Security sections.
- Both general command help and individual-option help now use `https://www.simplejavamail.org/cli.html`, with focused assertions covering both footer implementations.
- Per maintainer direction, no broader Java-source URL scanner was added.
- Verification: `GenerateCliHelpTest` passed on JDK 8, and Javadocs generated successfully for the facade and Spring modules.
- Implementation commit: `75ece88d fix(docs): replace stale website routes`.
