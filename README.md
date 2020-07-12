[![APACHE v2 License](https://img.shields.io/badge/license-apachev2-blue.svg?style=flat)](modules/simple-java-mail/LICENSE-2.0.txt) 
[![Latest Release](https://img.shields.io/maven-central/v/org.simplejavamail/simple-java-mail.svg?style=flat)](https://search.maven.org/#search%7Cgav%7C1%7Cg%3A%22org.simplejavamail%22%20AND%20v%3A%226.3.1%22) 
[![Javadocs](https://img.shields.io/badge/javadoc-6.3.1-brightgreen.svg?color=brightgreen)](https://www.javadoc.io/doc/org.simplejavamail/maven-master-project) 
[![Codacy](https://img.shields.io/codacy/grade/c7506663a4ab41e49b9675d87cd900b7.svg?style=flat)](https://www.codacy.com/app/b-bottema/simple-java-mail)
![Java 1.7+](https://img.shields.io/badge/java-1.7+-lightgray.svg)

# Simple Java Mail #

Simple Java Mail is the simplest to use lightweight mailing library for Java, while being able to send complex emails including **[CLI support](http://www.simplejavamail.org/cli.html#navigation)**, **[authenticated socks proxy](http://www.simplejavamail.org/features.html#section-proxy)**(!), **[attachments](http://www.simplejavamail.org/features.html#section-attachments)**, **[embedded images](http://www.simplejavamail.org/features.html#section-embedding)**, **[custom headers and properties](http://www.simplejavamail.org/features.html#section-custom-headers)**, **[robust address validation](http://www.simplejavamail.org/features.html#section-email-validation)**, **[build pattern](http://www.simplejavamail.org/features.html#section-builder-api)** and even **[DKIM signing](http://www.simplejavamail.org/features.html#section-dkim)**, **[S/MIME support](http://www.simplejavamail.org/features.html#section-sending-smime)** and **[external configuration files](http://www.simplejavamail.org/configuration.html#section-config-properties)** with **property overriding**, **[Spring support](http://www.simplejavamail.org/configuration.html#section-spring-support)** and **[Email conversion](http://www.simplejavamail.org/features.html#section-converting)** tools. Just send your emails without dealing with [RFC's](http://www.simplejavamail.org/rfc-compliant.html#navigation).

The Simple Java Mail library is a thin layer on top of [Jakarta Mail](https://eclipse-ee4j.github.io/mail/) that allows users to define emails on a high abstraction level without having to deal with mumbo jumbo such as 'multipart' and 'mimemessage'.

### [simplejavamail.org](http://www.simplejavamail.org) ###

Simple Java Mail is also available in [Maven Central](https://search.maven.org/#search%7Cgav%7C1%7Cg%3A%22org.simplejavamail%22%20AND%20a%3A%22simple-java-mail%22):

```xml
<dependency>
    <groupId>org.simplejavamail</groupId>
    <artifactId>simple-java-mail</artifactId>
    <version>6.3.1</version>
</dependency>
```

### Latest Progress ###

[v6.3.1](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C6.3.1%7Cjar) (12-July-2020)

- [#248](https://github.com/bbottema/simple-java-mail/issues/248) Bugfix: MimeMessageHelper: use complete filename as resource name 


[v6.3.0](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C6.3.0%7Cjar) (11-July-2020)

- [#279](https://github.com/bbottema/simple-java-mail/issues/279) Allow extra Session properties configured through simplejavamail.properties
- [#277](https://github.com/bbottema/simple-java-mail/issues/277) Add API for using custom SSLSocketFactory


[v6.2.0](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C6.2.0%7Cjar) (9-July-2020)

This release adds the following major new feature:

- [#260](https://github.com/bbottema/simple-java-mail/issues/260) Add support for dynamic datasource resolution (file/url/classpath) for embedded images in HTML body


[v6.1.0](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C6.1.0%7Cjar) (5-July-2020)

- [#264](https://github.com/bbottema/simple-java-mail/issues/264) Switch from AssertionError to IllegalStateException
- Bumped outlook-message-parser from 1.7.3 to 1.7.5
    - bugfix for parsing chinese unsent Outlook messages
    - bugfix Outlook attachments with special characters in the name
- Bumped email-rfc2822-validator from 2.1.3 to 2.2.0
    - bugfix properly handle brackets in email addresses when allowed
- Bumped log4j-core from 2.6.1 to 2.13.2


[v6.0.5](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C6.0.5%7Cjar) (13-June-2020)

- [#270](https://github.com/bbottema/simple-java-mail/issues/270) Bug: CLI module missing Jetbrains @Nullable annotation dependency needed in runtime


[v6.0.4](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C6.0.4%7Cjar) (3-May-2020)

- [#262](https://github.com/bbottema/simple-java-mail/issues/262) Bug: Executor settings passed to the builder are ignored
- [#249](https://github.com/bbottema/simple-java-mail/issues/249) Bug: MimeMessageParser doesn't handle multiple attachments with the same name correctly


[v6.0.3](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C6.0.3%7Cjar) (5-Februari-2020)

- [#245](https://github.com/bbottema/simple-java-mail/issues/245) Bug: JDK9+ Incorrect JPMS Automatic-Module-Name


[v6.0.2](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C6.0.2%7Cjar) (21-Januari-2020)

- [#246](https://github.com/bbottema/simple-java-mail/issues/246) Bug: Sending async emails with and without the Batch module cause lingering threads


v6.0.0-rc1 - [v6.0.1](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C6.0.1%7Cjar) (18-December-2019 - 24-January-2020)

After almost two years of development the next major release 6.0.0 is finally here! And what a doozy it is, with the following major new features:

The core library is now even smaller compared to the 5.x.x series going from 183kb to 134kb!

- [CLI support!!](http://www.simplejavamail.org/cli.html#navigation), 
- major performance improvement with [advanced batch processing](http://www.simplejavamail.org/configuration.html#section-batch-and-clustering) including support for mail server clusters. 
- You can now replace the final sending of emails with [your own logic](http://www.simplejavamail.org/features.html#section-custom-mailer), using a 3rd party service of your choice. 
- 6.0.0 also includes support for [S/MIME signed and encrypted emails](http://www.simplejavamail.org/features.html#section-smime)! 
- All 3rd party dependencies have been made optional by splitting up Simple Java Mail into easy to use [modules](http://www.simplejavamail.org/modules.html#navigation).
- You can now monitor and [handle async processing](http://www.simplejavamail.org/features.html#section-handling-async-result) using Futures.
- MimeMessage results are now [structurally matched](http://www.simplejavamail.org/rfc-compliant.html#section-explore-multipart) to specific needs (only using alternative/mixed etc. when needed) 

Here's the complete list of changes:

#### New features and enhancements ####
- [#183](https://github.com/bbottema/simple-java-mail/issues/183) To manage all the optional dependencies and related code, Simple Java Mail should be split up into modules
- [#156](https://github.com/bbottema/simple-java-mail/issues/156) Add CLI support
- [#214](https://github.com/bbottema/simple-java-mail/issues/214) Support more advanced batch processing use cases
- [#187](https://github.com/bbottema/simple-java-mail/issues/187) Simple Java Mail should have optional support for signed S/MIME attachments
- [#121](https://github.com/bbottema/simple-java-mail/issues/121) Introduce interfaces for validation and sending, so these steps can be customized
- [#144](https://github.com/bbottema/simple-java-mail/issues/144) Simple Java Mail should tailor the MimeMessage structure to specific needs
- [#138](https://github.com/bbottema/simple-java-mail/issues/138) Add support for Calendar events (iCalendar vEvent)
- [#235](https://github.com/bbottema/simple-java-mail/issues/235) Be able to fix the sent date for a new email
- [#232](https://github.com/bbottema/simple-java-mail/issues/232) Improve encoding of attachment file names
- [#222](https://github.com/bbottema/simple-java-mail/issues/222) Add config property support for trusting hosts and verifying server identity
- [#212](https://github.com/bbottema/simple-java-mail/issues/212) Authenticated proxy server started even if already running, raising exception
- [#207](https://github.com/bbottema/simple-java-mail/issues/207) Implement more comprehensive ThreadPoolExecutor and expose config options
- [#211](https://github.com/bbottema/simple-java-mail/issues/211) SpringSupport should expose the intermediate builder for customization
- [#193](https://github.com/bbottema/simple-java-mail/issues/193) Simple Java Mail should use default server ports when not provided by the user

#### Bugs solved ####
- [#242](https://github.com/bbottema/simple-java-mail/issues/242) Renamed log4j2.xml to log4j2_example.xml so it doesn't clash with project config
- [#241](https://github.com/bbottema/simple-java-mail/issues/241) EmailConverter.outlookMsgToEmail duplicates recipients
- [#239](https://github.com/bbottema/simple-java-mail/issues/239) List of Recipients not ordered as added (insertion order not maintained)
- [#236](https://github.com/bbottema/simple-java-mail/issues/236) Message ID should be mapped from Outlook messages as well
- [#210](https://github.com/bbottema/simple-java-mail/issues/210) Connection/session timeout properties not set when not sending in batch mode
- [#201](https://github.com/bbottema/simple-java-mail/issues/201) When parsing Outlook message, FROM address should default to a dummy address when missing
- [#200](https://github.com/bbottema/simple-java-mail/issues/200) When parsing Outlook message, attachment name doesn't fallback on filename if proper name is empty
- [#161](https://github.com/bbottema/simple-java-mail/issues/161) When reading (chinese) .msg files, HTML converted from RTF is completely garbled (encoding issue)
- [#159](https://github.com/bbottema/simple-java-mail/issues/159) Can not parse email with blank email address headers
- [#139](https://github.com/bbottema/simple-java-mail/issues/139) Multiple Bodyparts of same Content-Type not supported for text/html & text/plain within Multipart/mixed or Multipart/alternative
- [#151](https://github.com/bbottema/simple-java-mail/issues/151) Attachment's file extension overwritten by resource's invalid extension

#### Maintenance updates ####
- [#165](https://github.com/bbottema/simple-java-mail/issues/165) Move away from Findbugs (unofficial JSR-305) annotations
- [#164](https://github.com/bbottema/simple-java-mail/issues/164) The DKIM dependency has been updated to benefit from the newer Apache V2 license
- [#164](https://github.com/bbottema/simple-java-mail/issues/164) The DKIM dependency has been updated to benefit from the newer Apache V2 license
- [#184](https://github.com/bbottema/simple-java-mail/issues/184) Update JavaMail dependency to 1.6.2, adding support for UTF-8 charset
- [#186](https://github.com/bbottema/simple-java-mail/issues/186) Update JavaMail dependency to 1.6.2, adding support for authenticated HTTP web proxy
- [#146](https://github.com/bbottema/simple-java-mail/issues/146) Added OSGI manifest and switched to spotbugs

#### Included changes from outlook-message-parser ####
- v6.0.1, v1.7.3: [#27](https://github.com/bbottema/outlook-message-parser/issues/27) When from name/address are not available (unsent emails), these fields are filled with binary garbage
- v6.0.1, v1.7.2: [#26](https://github.com/bbottema/outlook-message-parser/issues/26) To email address is not handled properly when name is omitted
- v6.0.0, v1.7.1: [#25](https://github.com/bbottema/outlook-message-parser/issues/25) NPE on ClientSubmitTime when original message has not been sent yet
- v6.0.0, v1.7.1: [#23](https://github.com/bbottema/outlook-message-parser/issues/23) Bug: __nameid_ directory should not be parsed (and causing invalid HTML body)
- v6.0.0, v1.7.0: [#18](https://github.com/bbottema/outlook-message-parser/issues/18) Upgrade Apache POI 3.9 -> 4.x (but managed back for Simple Java Mail due to incompatibility with Java 7)
- v6.0.0, v1.6.0: [#21](https://github.com/bbottema/outlook-message-parser/issues/21) Multiple TO recipients are not handles properly
- v6.0.0, v1.5.0: [#20](https://github.com/bbottema/outlook-message-parser/issues/20) CC and BCC recipients are not parsed properly
- v6.0.0, v1.5.0: [#19](https://github.com/bbottema/outlook-message-parser/issues/19) Use real Outlook ContentId Attribute to resolve CID Attachments
- v6.0.0, v1.4.1: [#17](https://github.com/bbottema/outlook-message-parser/issues/17) Fixed encoding error for UTF-8's Windows legacy name (cp)65001
- v6.0.0, v1.4.0: [#9](https://github.com/bbottema/outlook-message-parser/issues/9) Replaced the RFC to HTML converter with a brand new RFC-compliant convert! (thanks to @fadeyev!)
- v6.0.0, v1.3.0: [#14](https://github.com/bbottema/outlook-message-parser/issues/14) Dependency problem with Java9+, missing Jakarta Activation Framework
- v6.0.0, v1.3.0: [#13](https://github.com/bbottema/outlook-message-parser/issues/13) HTML start tags with extra space not handled correctly
- v6.0.0, v1.3.0: [#11](https://github.com/bbottema/outlook-message-parser/issues/11) SimpleRTF2HTMLConverter inserts too many <br/> tags
- v6.0.0, v1.3.0: [#10](https://github.com/bbottema/outlook-message-parser/issues/10) Embedded images with DOS-like names are classified as attachments
- v6.0.0, v1.3.0: [#9](https://github.com/bbottema/outlook-message-parser/issues/9) SimpleRTF2HTMLConverter removes some valid tags during conversion
- v6.0.0, v1.2.1: Ignore non S/MIME related content types when extracting S/MIME metadata
- v6.0.0, v1.2.1: Added toString and equals methods to the S/MIME data classes
- v6.0.0, v1.1.21: Upgraded mediatype recognition based on file extension for incomplete attachments
- v6.0.0, v1.1.21: Added / improved support for public S/MIME meta data
- v6.0.0, v1.1.20: [#7](https://github.com/bbottema/outlook-message-parser/issues/7) Fix missing S/MIME header details that are needed to determine the type of S/MIME application
- v6.0.0, v1.1.19: Log rtf compression error, but otherwise ignore it and keep going and extract what we can.

**A big shout out to @dnault ([runtime javadoc](https://github.com/dnault/therapi-runtime-javadoc)), @remkop ([picocli](https://picocli.info/)) and @markenwerk
([S/MIME](https://github.com/markenwerk/java-utils-mail-smime) and [DKIM](https://github.com/markenwerk/java-utils-mail-dkim)) for working with me to make the
libraries work with JDK7+ and do what Simple Java Mail needed! Finally a great many thanks the numerous contributors on Simple Java Mail as well as
[outlook-message-parser](https://github.com/bbottema/outlook-message-parser) - this release would not be there without you.**


v5.5.0 - [v5.5.1](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C5.5.1%7Cjar)

- v5.5.1 (20-October-2019): [#230](https://github.com/bbottema/simple-java-mail/issues/230) Bugfix: Missing address value in address headers (ie. Return-Path) not handled properly, resulting in Exception
- v5.5.0 (15-October-2019): [#229](https://github.com/bbottema/simple-java-mail/issues/229) Bugfix: Timeouts not working for synchronous sendMail calls.

 If you had connection properties configured for non-async send jobs, only now they will actually start to take effect.


[v5.4.0](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C5.4.0%7Cjar) (28-August-2019)

- [#221](https://github.com/bbottema/simple-java-mail/issues/221) API bugfix: server identity verification should not be tied to host trusting
- [#226](https://github.com/bbottema/simple-java-mail/issues/226) Bug fix: Attachments with spaces in name are not handled properly
- [#218](https://github.com/bbottema/simple-java-mail/issues/218) Enhancement: make Email serializable
- [#227](https://github.com/bbottema/simple-java-mail/issues/227) Enhancement: Make parsing recipients from EML file more lenient
- [#225](https://github.com/bbottema/simple-java-mail/issues/225) Enhancement: Clarify dependency on Jakarta Activation: DataSources no longer work on Java 9+


[v5.3.0](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C5.3.0%7Cjar) (16-August-2019)

- [#215](https://github.com/bbottema/simple-java-mail/issues/215) Bug: Current DKIM header canonicalization can lead to invalid DKIM

Note this release should have no impact, but nonetheless is a minor update so you can determine for yourself if this update would cause issues.
The release changes DKIM header canonicalization from SIMPLE to RELAXED.


[v5.2.1](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C5.2.1%7Cjar) (16-August-2019)

- [#219](https://github.com/bbottema/simple-java-mail/issues/219) Bug: MimeMessageParser rejects attachments with duplicate names


[v5.2.0](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C5.2.0%7Cjar) (7-July-2019)

- [#213](https://github.com/bbottema/simple-java-mail/issues/213) Update from javax.mail:1.6.0 to jakarta.mail:1.6.3

Note that dependencies that switched as well have been updated as part of this change. This includes the optional DKIM library and the email validation library:
- net.markenwerk:utils-mail-dkim (1.1.10 -> 1.2.0) 
- com.github.bbottema:emailaddress-rfc2822 (1.1.2 -> 2.1.3)


v5.1.1 - [v5.1.7](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C5.1.7%7Cjar)

- v5.1.7 (22-May-2019): [#171](https://github.com/bbottema/simple-java-mail/issues/171) Header validation tripping on known safe emails due to References header
- v5.1.6 (27-April-2019): [#204](https://github.com/bbottema/simple-java-mail/issues/204) A Concurrent exception when an async process starts when the previous connection pool didn't shutdown in time
- v5.1.6 (27-April-2019): [#204](https://github.com/bbottema/simple-java-mail/issues/204) B Exceptions in threads are now caught and logged and don't bubble up anymore. Note that more comprehensive exception handling will be available in 6.0.0 (#148).
- v5.1.5 (24-April-2019): [#202](https://github.com/bbottema/simple-java-mail/issues/202) Fixed ConcurrentModificationException when moving invalid embedded images as regular attachments
- v5.1.4 (5-April-2019): [#163](https://github.com/bbottema/simple-java-mail/issues/163) Fixed missing mimetype for attachments when parsing Outlook messages where mimeTag was not included
- v5.1.3 (15-Januari-2019): Updated to newer rfc-validator version, which fixed a regression bug in that library
- v5.1.2 (9-Januari-2019): [#189](https://github.com/bbottema/simple-java-mail/issues/189) Bugfix for missing timeout config for .testConnection() function
- v5.1.1 (22-December-2018): [#190](https://github.com/bbottema/simple-java-mail/issues/190) Fix for transitive dependency clash because of emailaddress-rfc2822 library


[v5.1.0](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C5.1.0%7Cjar) (21-November-2018)

- [#179](https://github.com/bbottema/simple-java-mail/issues/179) You can now test the connection to the SMTP server


v5.0.1 - [v5.0.8](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C5.0.8%7Cjar)

- v5.0.8 (27-Oktober-2018): [#178](https://github.com/bbottema/simple-java-mail/issues/178) Fix the annoying vulnerability Github report about spring-core
- v5.0.7 (27-Oktober-2018): [#175](https://github.com/bbottema/simple-java-mail/issues/175) Attachment names are not always parsed properly from MimeMessage
- v5.0.6 (3-Oktober-2018): [#167](https://github.com/bbottema/simple-java-mail/issues/167) Email addresses validated despite cleared validation validation criteria
- v5.0.5 (3-Oktober-2018): [#137](https://github.com/bbottema/simple-java-mail/issues/137) When replying to an email with HTML, the result body is empty
- v5.0.4 (22-September-2018): [#168](https://github.com/bbottema/simple-java-mail/issues/168) Properties aquired through ConfigLoader should be typed explicitly and converted if necessary
- v5.0.3 (11-April-2018): [#136](https://github.com/bbottema/simple-java-mail/issues/136) ServerConfig class should be public API
- v5.0.2 (7-April-2018): [#135](https://github.com/bbottema/simple-java-mail/issues/135) trustingAllHosts should be public on the Builder API
- v5.0.2 (7-April-2018): [#131](https://github.com/bbottema/simple-java-mail/issues/131) NamedDataSource should implement EncodingAware
- v5.0.1 (10-March-2018): [#130](https://github.com/bbottema/simple-java-mail/issues/130) java.lang.ClassNotFoundException: net.markenwerk.utils.mail.dkim.DkimMessage. Solves the issue of missing optional class DKIM even when not used
 

[v5.0.0](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C5.0.0%7Cjar) (14-Februari-2018)

Also see the [migration notes](http://www.simplejavamail.org/#/migrate500)

#### New features ####
- [#116](https://github.com/bbottema/simple-java-mail/issues/116) You can now test the connection to the SMTP server
- [#115](https://github.com/bbottema/simple-java-mail/issues/115) Create mailers with a very robust MailerBuilder API, able to ignore defaults as well
- [#114](https://github.com/bbottema/simple-java-mail/issues/114) Create emails with a very robust EmailBuilder API, able to ignore defaults as well. Now includes support for InternetAddress. Also copy emails.
- [#107](https://github.com/bbottema/simple-java-mail/issues/107) You can now easily forward or reply to emails!

#### Security updates ####
- [#111](https://github.com/bbottema/simple-java-mail/issues/111) Protocol properties for SMTPS are now applied properly
- [#105](https://github.com/bbottema/simple-java-mail/issues/105) SMTP tries to upgrade to TLS while SMTP_TLS now enforces it and for both SMTP_TLS and SMTPS, [mail.smtp.ssl.checkserveridentity](https://javaee.github.io/javamail/docs/api/com/sun/mail/smtp/package-summary.html) is set to true 

#### Maintenance updates ####

Complete [Javadoc](https://www.javadoc.io/doc/org.simplejavamail/simple-java-mail) overhaul. Navigating the Javadoc should be much more consistent now (builder API being the single *public* source of truth).

- [#122](https://github.com/bbottema/simple-java-mail/issues/122) The email-rfc2822-validator library has been made a proper Maven dependency (not packaged along anymore)
- [#120](https://github.com/bbottema/simple-java-mail/issues/120) The DKIM library has been made an optional proper Maven dependency (not packaged along anymore)
- [#119](https://github.com/bbottema/simple-java-mail/issues/119) Switched optional Spring dependency version to property and now testing with 4.3.11.RELEASE
- [#113](https://github.com/bbottema/simple-java-mail/issues/113) Updated the underlying JavaMail to 1.6.0

#### Bugfixes ####
- [#110](https://github.com/bbottema/simple-java-mail/issues/110) Trusted hosts should be space-delimited
- [#109](https://github.com/bbottema/simple-java-mail/issues/109) Email headers should be allowed to be empty (now conversion errors can occur as well)
- [#103](https://github.com/bbottema/simple-java-mail/issues/103) Converting to MimeMessage results in an invalid Content-Disposition for attachments


[v4.4.5](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C4.4.5%7Cjar) (2-September-2017)

- [#101](https://github.com/bbottema/simple-java-mail/issues/101) API backwards compatibility update, reinstate old addRecipient API as deprecated (sorry for removing it abruptly)


[v4.4.4](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C4.4.4%7Cjar) (23-August-2017)

API usability release. **This relase streamlined the recipient setters, breaking backwards compatibility (but straightforward to fix)**

- [#95](https://github.com/bbottema/simple-java-mail/issues/95) Feature: Add support native API for setting Return-Receipt-To header
- [#93](https://github.com/bbottema/simple-java-mail/issues/93) Feature: Add support native API for setting Disposition-Notification-To header
- [#91](https://github.com/bbottema/simple-java-mail/issues/91) **Feature: Add support for parsing preformatted email addresses that include both name and address**
- [#94](https://github.com/bbottema/simple-java-mail/issues/94) Bugfix: A single EmailBuilder would build emails that all share the same collections for recipients, attachments and embedded images
- [#98](https://github.com/bbottema/simple-java-mail/issues/98) Bugfix: Subject and body content should be optional


[v4.3.0](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C4.3.0%7Cjar) (12-August-2017)

Security and timeout release. 

This version safeguards against SMTP injection attack from external values entering the library through *Email* instance. Also, this release introduces default/configurable timeouts for connecting, reading and writing when sending an email.

- [#89](https://github.com/bbottema/simple-java-mail/issues/89) Support multiple delimited recipient addresses sharing the same TO/CC/BCC name
- [#88](https://github.com/bbottema/simple-java-mail/issues/88) **Safeguard subject property (and others) against SMTP CRLF injection attacks**
- [#85](https://github.com/bbottema/simple-java-mail/issues/85) **Apply configurable timeouts when sending emails**
- [#83](https://github.com/bbottema/simple-java-mail/issues/83) Parse INLINE attachments without ID as regular attachments when converting (mostly applicable to Apple emails)


[v4.2.3](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C4.2.3%7Cjar) (21-May-2017)

- [#79](https://github.com/bbottema/simple-java-mail/issues/79): Enhancement: define custom message ID on the Email object
- [#74](https://github.com/bbottema/simple-java-mail/issues/74): v4.2.3-java-6-release: A java6 version with limited capabilities:
    I've released a customised java6 release with a customised outlook-message-parser 1.1.16-java6-release. **This is the last java6 release** I will do, as it is simply too much manual labor to create a limited second edition.

    For this edition, I've removed the JDK7 Phaser completely which has the following consequences:

    - If authenticated proxy is used, the bridging proxy server will not be shut down automatically (and might not run the second time)
    - If mails are sent in async mode, the connection pool will not be shut down anymore by itself
    
    This means your server/application might not stop properly due to lingering processes. To be completely safe, only send emails in sync mode (used by default) and don't use authenticated proxy config.


[v4.2.2](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C4.2.2%7Cjar) (10-May-2017)

- [#73](https://github.com/bbottema/simple-java-mail/issues/73): Patch: fix for sending emails in async mode, which makes sure the connection pool is always closed when the last *known* email has been sent. Without this fix, the connection pool keeps any parent process running (main thread or Tomcat for example) until a hard kill.


[v4.2.1](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C4.2.1%7Cjar) (12-Feb-2017)

Patch: streamlined convenience methods for adding recipients.


[v4.2.0](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C4.2.0%7Cjar) (12-Feb-2017)

**Major feature: Using the EmailConverter you can now convert between Outlook .msg, EML, MimeMessage and Email**!

- [#66](https://github.com/bbottema/simple-java-mail/issues/66): Feature: convert email to EML
- [#65](https://github.com/bbottema/simple-java-mail/issues/65): Feature: read outlook messages from .msg file
- [#64](https://github.com/bbottema/simple-java-mail/issues/64): **Feature: Added support for logging-only mode that skips the actual sending of emails**
- [#63](https://github.com/bbottema/simple-java-mail/issues/63): Feature: Already including in previous patch update: Spring support (read properties from Spring context)
- [#69](https://github.com/bbottema/simple-java-mail/issues/69): Enhancement: Expanded EmailBuilder API to inlude more options for setting (multiple) recipients
- [#70](https://github.com/bbottema/simple-java-mail/issues/70): Enhancement: Most public API now have defensive null-checks for required fields (Fail Fast support)
- [#68](https://github.com/bbottema/simple-java-mail/issues/68): Bugfix: Name should be required for embedded images (added safeguards)
- [#67](https://github.com/bbottema/simple-java-mail/issues/67): Bugfix: Error when name was omitted for attachment
- minor: added methods on AttachmentResource that reads back the content as (encoded) String
- other: internal testing is now done using Wiser SMTP test server for testing live sending emails

**Note**: Starting this release, there will always be a Java6 compatible release as well versioned: "x.y.z-java6-release"


[v4.1.3](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C4.1.3%7Cjar) (28-Jan-2017)

- [#61](https://github.com/bbottema/simple-java-mail/issues/61): Feature: Add support for providing your own Properties object
- [#63](https://github.com/bbottema/simple-java-mail/issues/63): **Feature: Spring support (read properties from Spring context)**
- [#58](https://github.com/bbottema/simple-java-mail/issues/58): Bugfix: Add support for non-English attachment and embedded image names
- [#62](https://github.com/bbottema/simple-java-mail/issues/62): Bugfix: Empty properties loaded from config should be considered null

**NOTE**: ConfigLoader moved from `/internal/util` to `/util`


[v4.1.2](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C4.1.2%7Cjar) (07-Nov-2016)

- [#52](https://github.com/bbottema/simple-java-mail/issues/52): bug fix for windows / linux disparity when checking socket status
- [#56](https://github.com/bbottema/simple-java-mail/issues/56): bug fix for IOException when signing dkim with a File reference


[v4.1.1](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C4.1.1%7Cjar) (30-Jul-2016)

- [#50](https://github.com/bbottema/simple-java-mail/issues/50): bug fix for manual naming datasources


[v4.1.0](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C4.1.0%7Cjar) (22-Jul-2016)

- [#48](https://github.com/bbottema/simple-java-mail/issues/48): Added programmatic support trusting hosts for SSL connections
- [#47](https://github.com/bbottema/simple-java-mail/issues/47): Honor given names, deduce extension from datasource name, and more robust support for parsing mimemessages


[v4.0.0](https://search.maven.org/#artifactdetails%7Corg.simplejavamail%7Csimple-java-mail%7C4.0.0%7Cjar) (05-Jul-2016)

- [#41](https://github.com/bbottema/simple-java-mail/issues/41): added support for fast parallel batch processing
- [#42](https://github.com/bbottema/simple-java-mail/issues/42): **added support for config files**
- [#43](https://github.com/bbottema/simple-java-mail/issues/43): removed logging implementation dependencies from distribution and documented various sample configs
- [#39](https://github.com/bbottema/simple-java-mail/issues/39): simplified and renamed packages to reflect the domain name of the new website: [simplejavamail.org](http://www.simplejavamail.org)
- [#38](https://github.com/bbottema/simple-java-mail/issues/38): added support for anonymous proxy
- [#38](https://github.com/bbottema/simple-java-mail/issues/38): **added support for authenticated proxy** 

**NOTE**: All packages have been renamed to "org.simplejavamail.(..)"
**NOTE**: Switched to Java 7


[v3.1.1](https://search.maven.org/#artifactdetails%7Corg.codemonkey.simplejavamail%7Csimple-java-mail%7C3.1.1%7Cjar) (11-May-2016)

**Major feature: DKIM support**!

- [#36](https://github.com/bbottema/simple-java-mail/issues/36): Added proper toString and equals methods for the Email classes
- [#33](https://github.com/bbottema/simple-java-mail/issues/33): Added support for DKIM domain key signing

*NOTE*: this is the last release still using Java 6. Next release will be using Java 7.
/edit: starting with 4.2.0 every release will now have a "x.y.z-java6-release" release as well


[v3.0.2](https://search.maven.org/#artifactdetails%7Corg.codemonkey.simplejavamail%7Csimple-java-mail%7C3.0.2%7Cjar) (07-May-2016)

- [#35](https://github.com/bbottema/simple-java-mail/issues/35): added proper .equals() and .toString() methods
- [#34](https://github.com/bbottema/simple-java-mail/issues/34): Fixed bug when disposition is missing (assume it is an attachment)
- other: added findbugs support internally


[v3.0.1](https://search.maven.org/#artifactdetails%7Corg.codemonkey.simplejavamail%7Csimple-java-mail%7C3.0.1%7Cjar) (29-Feb-2016)

  * [#31](https://github.com/bbottema/simple-java-mail/issues/31): Fixed EmailAddressCriteria.DEFAULT and clarified Javadoc


[v3.0.0](https://search.maven.org/#artifactdetails%7Corg.codemonkey.simplejavamail%7Csimple-java-mail%7C3.0.0%7Cjar) (26-Feb-2016)

  * [#30](https://github.com/bbottema/simple-java-mail/issues/30): Improved the demonstration class to include attachments and embedded images
  * [#29](https://github.com/bbottema/simple-java-mail/issues/29): The package has been restructured for future maintenance, breaking backwards compatibility
  * [#28](https://github.com/bbottema/simple-java-mail/issues/28): Re-added improved email validation facility
  * [#22](https://github.com/bbottema/simple-java-mail/issues/22): Added conversion to and from MimeMessage. You can now consume and produce MimeMessage objects with simple-java-mail

  
[v2.5.1](https://search.maven.org/#artifactdetails%7Corg.codemonkey.simplejavamail%7Csimple-java-mail%7C2.5.1%7Cjar) (19-Jan-2016)

  * [#25](https://github.com/bbottema/simple-java-mail/issues/25): Added finally clause that will always close socket properly in case of an exception

  
[v2.5](https://search.maven.org/#artifactdetails%7Corg.codemonkey.simplejavamail%7Csimple-java-mail%7C2.5%7Cjar) (19-Jan-2016)

  * [#24](https://github.com/bbottema/simple-java-mail/issues/24): Updated dependencies SLF4J to 1.7.13 and switched to the updated javax mail package com.sun.mail:javax.mail 1.5.5

  
[v2.4](https://search.maven.org/#artifactdetails%7Corg.codemonkey.simplejavamail%7Csimple-java-mail%7C2.4%7Cjar) (12-Aug-2015)

  * [#21](https://github.com/bbottema/simple-java-mail/issues/21): builder API uses CC and BCC recipient types incorrectly


[v2.3](https://search.maven.org/#artifactdetails%7Corg.codemonkey.simplejavamail%7Csimple-java-mail%7C2.3%7Cjar) (21-Jul-2015)

  * [#19](https://github.com/bbottema/simple-java-mail/issues/19): supporting custom Session Properties now and emergency access to internal Session object.


[v2.2](https://search.maven.org/#artifactdetails%7Corg.codemonkey.simplejavamail%7Csimple-java-mail%7C2.2%7Cjar) (09-May-2015)

  * [#3](https://github.com/bbottema/simple-java-mail/issues/3): turned off email regex validation by default, with the option to turn it back on
  * [#7](https://github.com/bbottema/simple-java-mail/issues/7): fixed NullPointerException when using your own Session instance
  * [#10](https://github.com/bbottema/simple-java-mail/issues/10): properly UTF-8 encode recipient addresses
  * [#14](https://github.com/bbottema/simple-java-mail/issues/14): switched to [SLF4J](https://www.slf4j.org/), so you can easily use your own selected logging framework
  * [#17](https://github.com/bbottema/simple-java-mail/issues/17): Added [fluent interface](https://en.wikipedia.org/wiki/Builder_pattern) for building emails (see [here](http://www.simplejavamail.org/#section-builder-api) for an example)


[v2.1](https://search.maven.org/#artifactdetails%7Corg.codemonkey.simplejavamail%7Csimple-java-mail%7C2.1%7Cjar) (09-Aug-2012)

  * fixed character encoding for reply-to, from, to, body text and headers (to UTF-8)
  * fixed bug where Recipient was not public resulting in uncompilable code when calling email.getRecipients()


[v2.0](https://search.maven.org/#artifactdetails%7Corg.codemonkey.simplejavamail%7Csimple-java-mail%7C2.0%7Cjar) (20-Aug-2011)

  * added support for adding open headers, such as 'X-Priority: 2'


[v1.9.1](https://search.maven.org/#artifactdetails%7Corg.codemonkey.simplejavamail%7Csimple-java-mail%7C1.9.1%7Cjar) (08-Aug-2011)

  * updated for Maven support


v1.9 (6-Aug-2011)

  * added support for JavaMail's reply-to address
  * made port optional as to support port defaulting based on protocol
  * added transport strategy default in the createSession method
  * tightened up thrown exceptions (MailException instead of RuntimeException)
  * added and fixed [Javadoc](https://www.javadoc.io/doc/org.simplejavamail/simple-java-mail)


v1.8

  * Added support for TLS (tested with gmail)


v1.7 (22-Mar-2011)

Added support for SSL! (tested with gmail)

  * improved argument validation when creating a Mailer without preconfigured Session instance

known possible issue: SSL self-signed certificates might not work (yet). Please let me know by e-mail or create a new issue


v1.6

Completed migration to Java Simple Mail project.

  * removed all Vesijama references
  * updated TestMail demonstration class for clarification
  * updated readme.txt for test run instructions
  * included log4j.properties


v1.4 (15-Jan-2011)


vX.X (26-Apr-2009)

  * Initial upload to Google Code.

