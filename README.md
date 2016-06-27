[![APACHE v2 License](https://img.shields.io/badge/license-apachev2-blue.svg?style=flat)](LICENSE) [![Latest Release](https://img.shields.io/maven-central/v/org.codemonkey.simplejavamail/simple-java-mail.svg?style=flat)](http://search.maven.org/#search%7Cgav%7C1%7Cg%3A%22org.codemonkey.simplejavamail%22%20AND%20a%3A%22simple-java-mail%22) [![Javadocs](http://www.javadoc.io/badge/org.codemonkey.simplejavamail/simple-java-mail.svg?color=brightgreen)](http://www.javadoc.io/doc/org.codemonkey.simplejavamail/simple-java-mail) [![Build Status](https://img.shields.io/travis/bbottema/simple-java-mail.svg?style=flat)](https://travis-ci.org/bbottema/simple-java-mail) [![Codacy](https://img.shields.io/codacy/9f142ca8c8c640c984835a8ae02d29f3.svg?style=flat)](https://www.codacy.com/app/b-bottema/simple-java-mail)

# Simple Java Mail #

Simple Java Mail is the simplest to use lightweight mailing library for Java, while being able to send complex emails including **authenticated socks proxy**(!), **attachments**, **embedded images**, **custom headers and properties**, **robust address validation**, **build pattern** and even **DKIM signing** and **external configuration files** with **property overriding**. Just send your emails without dealing with RFC's.

The Simple Java Mail library is a thin layer on top of the JavaMail smtp mailing API that allows users to define emails on a high abstraction level without having to deal with mumbo jumbo such a 'multipart' and 'mimemessage'.

### [simplejavamail.org](http://www.simplejavamail.org) ###

```java
ConfigLoader.loadProperties("simplejavamail.properties"); // optional default
ConfigLoader.loadProperties("overrides.properties"); // optional extra

Email email = new Email();

email.addRecipient("lollypop", "lolly.pop@somemail.com", Message.RecipientType.TO);
email.setReplyToAddress("lollypop", "lolly.pop@othermail.com");
email.addRecipient("C. Cane", "candycane@candyshop.org", Message.RecipientType.TO);
email.addRecipient("C. Bo", "chocobo@candyshop.org", Message.RecipientType.CC);
email.setSubject("hey");
email.setText("We should meet up! ;)");
email.setTextHTML("&lt;img src=&#39;cid:wink1&#39;&gt;&lt;b&gt;We should meet up!&lt;/b&gt;&lt;img src=&#39;cid:wink2&#39;&gt;");
email.addEmbeddedImage("wink1", imageByteArray, "image/png");
email.addEmbeddedImage("wink2", imageDatesource);
email.addAttachment("invitation", pdfByteArray, "application/pdf");
email.addAttachment("dresscode", odfDatasource);

email.signWithDomainKey(privateKeyData, "somemail.com", "selector");

new Mailer(
		new ServerConfig("smtp.host.com", 587, "user@host.com", "password"),
		TransportStrategy.SMTP_TLS,
		new ProxyConfig("socksproxy.host.com", 1080, "proxy user", "proxy password")
).sendMail(email);
```

---


Simple Java Mail is available in Maven Central:

```
<dependency>
    <groupId>org.codemonkey.simplejavamail</groupId>
    <artifactId>simple-java-mail</artifactId>
    <version>3.1.1</version>
</dependency>
```

### Latest Progress ###

v4.0.0 (unreleased)

- [#41](https://github.com/bbottema/simple-java-mail/issues/41): added support for fast parallel batch processing
- [#42](https://github.com/bbottema/simple-java-mail/issues/42): **added support for config files**
- [#43](https://github.com/bbottema/simple-java-mail/issues/43): removed logging implementation dependencies from distribution and documented various sample configs
- [#38](https://github.com/bbottema/simple-java-mail/issues/38): added support for anonymous proxy
- [#38](https://github.com/bbottema/simple-java-mail/issues/38): **added support for authenticated proxy** 


v3.1.1

Major feature: DKIM support!

- [#36](https://github.com/bbottema/simple-java-mail/issues/36): Added proper toString and equals methods for the Email classes
- [#33](https://github.com/bbottema/simple-java-mail/issues/33): Added support for DKIM domain key signing


v3.0.2

- [#35](https://github.com/bbottema/simple-java-mail/issues/35): added proper .equals() and .toString() methods
- [#34](https://github.com/bbottema/simple-java-mail/issues/34): Fixed bug when disposition is missing (assume it is an attachment)
- #00: added findbugs support internally


v3.0.1

  * [#31](https://github.com/bbottema/simple-java-mail/issues/31): Fixed EmailAddressCriteria.DEFAULT and clarified Javadoc


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
