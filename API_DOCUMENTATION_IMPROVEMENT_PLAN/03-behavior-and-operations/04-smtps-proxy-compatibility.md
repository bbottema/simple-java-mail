# Restore SMTPS proxy compatibility

- Status: Completed
- Priority: Low
- Work: Bug fix, integration tests and documentation

## Problem

Simple Java Mail rejected every SOCKS proxy combined with `TransportStrategy.SMTPS`, even though Angus Mail supports `mail.smtps.socks.host` and
`mail.smtps.socks.port`. The restriction dated from 2016 and was repeated in `ProxyConfig` Javadoc. A Spring example also combined SMTPS with proxy
credentials but omitted the proxy host and port.

## Resolution

Removed the obsolete guard and retained transport-specific property selection, so SMTPS now uses Angus Mail's `mail.smtps.socks.*` path. Added a local
SOCKS-to-implicit-TLS integration test for anonymous proxying and for Simple Java Mail's authenticated-proxy bridge path. Corrected the API Javadoc and
completed the Spring configuration example.

## Acceptance criteria

- [x] Anonymous SOCKS routing works with SMTPS.
- [x] Authenticated-proxy bridge routing works with SMTPS.
- [x] The integration test proves the SOCKS server accepted the connection and the TLS SMTP endpoint received the message.
- [x] Proxy API guidance no longer repeats the obsolete restriction.
- [x] The Spring sample forms a valid SMTPS and proxy configuration.

## Evidence

- GitHub issue: [#687](https://github.com/bbottema/simple-java-mail/issues/687)
- Regression origin: `322ff855f7b946b7ee06fcb078c333872a66b369` (first released in 4.1.1)
- Implementation: `modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal/MailerImpl.java`
- Integration test: `modules/simple-java-mail/src/test/java/org/simplejavamail/mailer/MailerSOCKSLiveTest.java`
- API guidance: `modules/core-module/src/main/java/org/simplejavamail/api/mailer/config/ProxyConfig.java`
- Website guidance: `simplejavamail.org/src/pages/features.hbs` and `simplejavamail.org/src/pages/configuration.hbs`
