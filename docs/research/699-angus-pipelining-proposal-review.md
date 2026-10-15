# Angus PIPELINING proposal: code and contribution review

Reviewed 2026-10-12 for [SJM #699](https://github.com/bbottema/simple-java-mail/issues/699) and [Angus #213](https://github.com/eclipse-ee4j/angus-mail/issues/213). The [proposal](../plans/699-angus-pipelining-proposal.md) contains the suggested scope and behavior; this note records the evidence behind it.

## Basis and conclusion

I reviewed upstream `master` at [a7bd3fed](https://github.com/eclipse-ee4j/angus-mail/commit/a7bd3fed964d9c9bc2981d4b2c9867358453c571), including the merged BDAT fix. The sibling checkout's upstream ref was refreshed without switching its branch or changing its files. This was a source and process review, not a runtime experiment. No implementation was added or tests run.

The proposed first contribution fits Angus's configuration conventions and SMTP protocol rules. Two implementation questions still need proof: safe flow control through the existing socket/stream infrastructure, and compatibility with subclass hooks. The proposal now calls these out rather than assuming they are solved.

## What matches the existing code

| Suggestion | Evidence and implication |
| --- | --- |
| Session-property feature toggle | The [SMTP constructor][properties] already uses `PropUtil.getBooleanProperty` with `mail.` plus the selected protocol name. `SMTPSSLTransport` supplies `smtps`. The [SMTP package documentation][smtp-docs] describes the same convention, and [Jason requested a toggle][toggle]. The new property names and default `false` are recommendations, not maintainer decisions. |
| Synchronous public send, existing connection | [`sendMessage`][send] is synchronized and sends the envelope before choosing DATA or BDAT. That supports keeping the public contract. Whether all implementation changes fit in `SMTPTransport` remains to be established; no new runtime dependency or pool is planned. |
| Envelope-only first contribution | [RFC 2920, sections 3.1 and 3.2][pipelining] permits MAIL and RCPT groups and requires ordered replies. Waiting before DATA is a narrower scope than the RFC permits, but is valid. Compliant servers must release pending replies when their input buffer empties; the test server should also exercise buffered replies. |
| Keep CHUNKING independent | [`sendMessage`][send] selects BDAT only for a positive `chunksize` and advertised CHUNKING. [Each BDAT chunk currently waits for its own reply][bdat]. The proposal changes envelope timing, not chunk scheduling or BINARYMIME support. [RFC 3030, section 2][chunking] requires all MAIL/RCPT replies to be processed before BDAT. |
| Preserve delivery reporting | [`rcptTo`][recipients] classifies recipient replies, builds exception chains and address arrays, and implements `sendpartial` and `reportsuccess`. These rules need to be shared, not recreated with a simpler success/failure check. In particular, an unfamiliar 2xx reply is accepted, and `reportsuccess` can produce an exception after a successful submission. |

## Corrections and prototype questions

### Fresh capability information

[`protocolConnect`][connect] sends EHLO again after STARTTLS, but ignores a negative result. [`ehlo`][ehlo] only replaces `extMap` after a 250 reply; HELO, STARTTLS and connection close do not clear it. Source inspection therefore shows a path where earlier capabilities survive a failed post-TLS EHLO or a reconnect that falls back to HELO. This review did not reproduce those paths on the wire.

PIPELINING must depend on a successful EHLO in the current connection and TLS state. [RFC 3207, section 4.2][starttls] requires pre-TLS information to be discarded. Tests must cover failed EHLO and HELO fallback as well as a successful EHLO with changed capabilities. The new feature must not rely on an unqualified `supportsExtension("PIPELINING")` check against stale state.

### Protected hooks

The [class documentation][hooks] explicitly supports subclassing. `mailFrom()` currently returns after its reply, while `rcptTo()` sends and reads one recipient at a time. Changing that sequence can break overrides that insert commands or associate the latest command with the next reply.

SJM is a concrete example: [`ManagedAngusTransport`][sjm-hooks] has one `pendingRecipientIndex`, which each outgoing command replaces. It also uses `mailFrom()` for message-size preparation and `sendCommand()` for envelope parameters. Simply sending all commands first would misattribute responses; bypassing hooks could also skip those preparations.

The disabled path should retain current hook behavior. Enabled behavior needs maintainer agreement and tests. The SJM prototype must track all pending commands in order, including MAIL and non-recipient commands, and preserve final-response tracking.

### Failure handling and validation

[`issueSendCommand`][send-failure] sends RSET immediately on rejection, and `rcptTo()` also resets after recipient failure. Those helpers cannot be reused unchanged while other replies are pending. Save the command's response before later reads overwrite `lastServerResponse`, consume outstanding replies on a usable connection, and complete RSET before reuse.

If MAIL is rejected, keep the sender failure primary and recipients unsent. A later RCPT reply caused by the missing valid sender must not be reported as proof that the recipient itself is invalid. A 421 shutdown, EOF, timeout or malformed reply calls for closing the connection rather than attempting synchronization through more commands.

[`validateCommand`][command-validation] rejects CR and LF inside each command. Grouping must preserve that per-command check. The new path must not bypass it or mistake the group's legitimate separators for characters inside an individual command.

### Flow control

The current I/O is blocking. [`sendCommand` flushes each command][command-validation], and `readServerResponse` updates shared fields and asserts that the transport monitor is held. A separate reader cannot simply call that method while synchronized `sendMessage` holds the monitor.

[RFC 2920, section 3.1][pipelining] requires nonblocking operation or groups that fit the TCP window. Byte and reply bounds are useful, but an arbitrary small batch is not proof of that condition. The prototype needs to resolve the read/write strategy through both plain and TLS streams, including existing timeout and custom-socket behavior. If that requires broader changes, bring those findings back to #213.

## Contribution process and testing

[Angus's contribution page][contributions] asks contributors to coordinate before coding and follow the existing Java style. [Jason specifically asked for this proposal as a comment in #213][proposal-request], so that is the agreed place for the discussion. I found no prescribed design-document template in the reviewed guidance. The root [CONTRIBUTING.md][contributing-file] still contains Jakarta Mail wording and legacy links; the Angus website and the issue's direct instructions provide clearer project-specific guidance.

The contribution file requires an ECA and Signed-off-by for outside contributors. The current [Eclipse handbook][handbook] also covers review of substantial new contributions and disclosure of meaningful AI assistance. Its 1,000-line review threshold concerns new intellectual property, not GitHub additions plus deletions. Maintainers assess that; the survey below does not establish an exemption. Learning from another implementation and copying its source are different contribution/provenance cases.

Production SMTP code lives in `providers/smtp`; its wire-test fixtures live in `providers/angus-mail/src/test`. [`SMTPHandler`][handler] is extensible, but normally replies immediately. New tests must deliberately control response timing and backpressure. Reuse JUnit and those fixtures: [Lukas stated a preference against mocking-framework dependencies][mocking].

The [current workflow][ci] verifies on JDK 11, 17 and 21, and the root POM targets Java 11. The implementation should document the property in `smtp/package-info.java`, update `CHANGES.txt`, and add `COMPAT.txt` notes if hooks or observable behavior need explanation. The release target is still a question for maintainers.

## Short survey of larger changes

[Repository metadata][repository] dates Angus Mail's GitHub repository to 2021-07-19. Its first commit is dated 2021-08-18; the [project website][history] explains that Jakarta Mail's implementation moved to Angus then. Earlier JavaMail/Jakarta Mail work predates this standalone repository.

I scanned the 81 merged PRs returned by GitHub and inspected these three substantive examples, excluding bulk formatting and package renames. Sizes are GitHub diff counts, including tests and documentation; elapsed time is opening-to-merge time, not time spent reviewing.

| Merged change | Author | Diff size | Elapsed | Relevant precedent |
| --- | --- | --- | --- | --- |
| [#22: caller-supplied executor for write timeout][pr22] | egor-ponomarev, outside contributor | +1,051 / -41; 10 files | About 140 days | The closest example. It added a Session-property extension and changed socket lifecycle behavior. Jason reviewed ownership, failure cases and cleanup in detail, and requested targeted tests and SpotBugs checks. |
| [#126: TLS hostname verification changes][pr126] | jmehrens, project committer | +1,315 / -139; 7 files | About 9 days | A substantial transport utility change with new properties, compatibility documentation, tests and reviews from other committers. |
| [#49: native-image support][pr49] | lukasj, project lead | +179 / -640; 18 files | About 13 days | Broader implementation and packaging work, coordinated with a corresponding Angus Activation change. |

The [current project roster][team] identifies Jason and Lukas as committers. The outside-contributor example shows that larger work can be accepted, including a transport change, but took sustained review. These examples support asking for scope agreement early and arriving with precise failure-path tests. They do not predict the acceptance rate or review time for PIPELINING.

[properties]: https://github.com/eclipse-ee4j/angus-mail/blob/a7bd3fed964d9c9bc2981d4b2c9867358453c571/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java#L189
[smtp-docs]: https://eclipse-ee4j.github.io/angus-mail/docs/api/org.eclipse.angus.mail/org/eclipse/angus/mail/smtp/package-summary.html
[toggle]: https://github.com/eclipse-ee4j/angus-mail/issues/213#issuecomment-6086528787
[send]: https://github.com/eclipse-ee4j/angus-mail/blob/a7bd3fed964d9c9bc2981d4b2c9867358453c571/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java#L1242
[bdat]: https://github.com/eclipse-ee4j/angus-mail/blob/a7bd3fed964d9c9bc2981d4b2c9867358453c571/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java#L2847
[pipelining]: https://www.rfc-editor.org/rfc/rfc2920.html#section-3.1
[chunking]: https://www.rfc-editor.org/rfc/rfc3030.html#section-2
[recipients]: https://github.com/eclipse-ee4j/angus-mail/blob/a7bd3fed964d9c9bc2981d4b2c9867358453c571/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java#L1859
[connect]: https://github.com/eclipse-ee4j/angus-mail/blob/a7bd3fed964d9c9bc2981d4b2c9867358453c571/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java#L674
[ehlo]: https://github.com/eclipse-ee4j/angus-mail/blob/a7bd3fed964d9c9bc2981d4b2c9867358453c571/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java#L1680
[starttls]: https://www.rfc-editor.org/rfc/rfc3207.html#section-4.2
[hooks]: https://github.com/eclipse-ee4j/angus-mail/blob/a7bd3fed964d9c9bc2981d4b2c9867358453c571/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java#L80
[sjm-hooks]: ../../modules/angus-mail-provider-module/src/main/java/org/simplejavamail/internal/mailprovider/angus/ManagedAngusTransport.java
[send-failure]: https://github.com/eclipse-ee4j/angus-mail/blob/a7bd3fed964d9c9bc2981d4b2c9867358453c571/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java#L2379
[command-validation]: https://github.com/eclipse-ee4j/angus-mail/blob/a7bd3fed964d9c9bc2981d4b2c9867358453c571/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java#L2450
[contributions]: https://eclipse-ee4j.github.io/angus-mail/Contributions
[proposal-request]: https://github.com/eclipse-ee4j/angus-mail/issues/213#issuecomment-6086585715
[contributing-file]: https://github.com/eclipse-ee4j/angus-mail/blob/a7bd3fed964d9c9bc2981d4b2c9867358453c571/CONTRIBUTING.md
[handbook]: https://www.eclipse.org/projects/handbook/#contributing
[handler]: https://github.com/eclipse-ee4j/angus-mail/blob/a7bd3fed964d9c9bc2981d4b2c9867358453c571/providers/angus-mail/src/test/java/org/eclipse/angus/mail/smtp/SMTPHandler.java
[mocking]: https://github.com/eclipse-ee4j/angus-mail/pull/22#issuecomment-1222473973
[ci]: https://github.com/eclipse-ee4j/angus-mail/blob/a7bd3fed964d9c9bc2981d4b2c9867358453c571/.github/workflows/maven.yml
[repository]: https://api.github.com/repos/eclipse-ee4j/angus-mail
[history]: https://eclipse-ee4j.github.io/angus-mail/#august-18-2021---jakarta-mail-moves-to-eclipse-angus
[pr22]: https://github.com/eclipse-ee4j/angus-mail/pull/22
[pr126]: https://github.com/eclipse-ee4j/angus-mail/pull/126
[pr49]: https://github.com/eclipse-ee4j/angus-mail/pull/49
[team]: https://projects.eclipse.org/projects/ee4j.angus/who
