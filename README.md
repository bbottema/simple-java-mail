[![APACHE v2 License](https://img.shields.io/badge/license-apachev2-blue.svg?style=flat)](LICENSE)

# Simple Java Mail #

Simple Java Mail aims to be the simplest to use lightweight mailing framework for Java, while being able to send complex emails including attachments and embedded images. Just send your emails without dealing with RFC's.

The Simple Java Mail framework essentially is a wrapper around the JavaMail smtp mailing API that allows users to define emails on a high abstraction level without having to deal with mumbo jumbo such a 'multipart' and 'mimemessage'.

### [Simple Java Mail one-page manual](https://github.com/bbottema/simple-java-mail/wiki/Manual) ###


---


Simple Java Mail is available in Maven Central:

```
<dependency>
    <groupId>org.codemonkey.simplejavamail</groupId>
    <artifactId>simple-java-mail</artifactId>
    <version>3.0.0</version>
</dependency>
```

### Latest Progress ###

v3.0.0

  * [#30](https://github.com/bbottema/simple-java-mail/issues/30): Improved the demonstration class to include attachments and embedded images
  * [#29](https://github.com/bbottema/simple-java-mail/issues/29): The package has been restructured for future maintenance, breaking backwards compatibility
  * [#28](https://github.com/bbottema/simple-java-mail/issues/28): Re-added improved email validation facility
  * [#22](https://github.com/bbottema/simple-java-mail/issues/22): Added conversion to and from MimeMessage. You can now consume and produce MimeMessage objects with simple-java-mail

v2.5.1

  * [#25](https://github.com/bbottema/simple-java-mail/issues/25): Added finally clause that will always close socket properly in case of an exception

v2.5

  * [#24](https://github.com/bbottema/simple-java-mail/issues/24): Updated dependencies SLF4J to 1.7.13 and switched to the updated javax mail package com.sun.mail:javax.mail 1.5.5

v2.4

  * [#21](https://github.com/bbottema/simple-java-mail/issues/21): builder API uses CC and BCC recipient types incorrectly


v2.3

  * [#19](https://github.com/bbottema/simple-java-mail/issues/19): supporting custom Session Properties now and emergency access to internal Session object.


v2.2

  * [#3](https://github.com/bbottema/simple-java-mail/issues/3): turned off email regex validation by default, with the option to turn it back on
  * [#7](https://github.com/bbottema/simple-java-mail/issues/7): fixed NullPointerException when using your own Session instance
  * [#10](https://github.com/bbottema/simple-java-mail/issues/10): properly UTF-8 encode recipient addresses
  * [#14](https://github.com/bbottema/simple-java-mail/issues/14): switched to SLF4J, so you can easily use your own selected logging framework
  * [#17](https://github.com/bbottema/simple-java-mail/issues/17): Added [fluent interface](http://en.wikipedia.org/wiki/Builder_pattern) for building emails (see [manual](https://github.com/bbottema/simple-java-mail/wiki/Manual) for an example)


v2.1

  * fixed character encoding for reply-to, from, to, body text and headers (to UTF-8)
  * fixed bug where Recipient was not public resulting in uncompilable code when calling email.getRecipients()


v2.0

  * added support for adding open headers, such as 'X-Priority: 2'


v1.9.1

  * updated for Maven support


v1.9

  * added support for JavaMail's reply-to address
  * made port optional as to support port defaulting based on protocol
  * added transport strategy default in the createSession method
  * tightened up thrown exceptions (MailException instead of RuntimeException)
  * added and fixed [JavaDoc](http://simple-java-mail.googlecode.com/svn/trunk/javadoc/users/index.html)


v1.8

  * Added support for TLS (tested with gmail)


v1.7

Added support for SSL! (tested with gmail)

  * improved argument validation when creating a Mailer without preconfigured Session instance

known possible issue: SSL self-signed certificates might not work (yet). Please let me know by e-mail or create a new issue


v1.6

Completed migration to Java Simple Mail project.

  * removed all Vesijama references
  * updated TestMail demonstration class for clarification
  * updated readme.txt for test run instructions
  * included log4j.properties
