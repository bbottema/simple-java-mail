# Replace stale source-Javadoc website links

- Status: Planned
- Priority: Medium
- Work: Source Javadocs, CLI footer, link checking

## Defect

Several source Javadocs and the CLI footer still use the removed hash-router URLs such as `/#/configuration`, `/#/cli`, and homepage section fragments.

## Plan

Replace them with current canonical routes, regenerate Javadocs/help, and extend link checking to scan Java source links and CLI footer URLs.

## Acceptance criteria

- [ ] Spring support links to `/configuration.html#section-spring-support`.
- [ ] CLI help links to `/cli.html`.
- [ ] DKIM and S/MIME source links point to Security sections.
- [ ] Automated checks reject future `simplejavamail.org/#/` links.

## Evidence

- `modules/spring-module/src/main/java/org/simplejavamail/springsupport/SimpleJavaMailSpringSupport.java:27`
- `modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/CliCommandLineProducer.java:148`
- `modules/cli-module/src/main/java/org/simplejavamail/internal/clisupport/CliCommandLineConsumerUsageHelper.java:59`
- `modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/SpecializedMimeMessageProducer.java:30-31`
