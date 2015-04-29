# Simple Java Mail #

Simple Java Mail aims to be the simplest to use lightweight mailing framework for Java, while being able to send complex emails including attachments and embedded images. Just send your emails without dealing with RFC's.

The Simple Java Mail framework essentially is a wrapper around the JavaMail smtp mailing API that allows users to define emails on a high abstraction level without having to deal with mumbo jumbo such a 'multipart' and 'mimemessage'.

### [Simple Java Mail one-page manual](http://code.google.com/p/simple-java-mail/wiki/Manual) ###


---


### Latest Progress ###

Simple Java Mail is now available in Maven Central!

```
<dependency>
    <groupId>org.codemonkey.simplejavamail</groupId>
    <artifactId>simple-java-mail</artifactId>
    <version>2.1</version>
</dependency>
```

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