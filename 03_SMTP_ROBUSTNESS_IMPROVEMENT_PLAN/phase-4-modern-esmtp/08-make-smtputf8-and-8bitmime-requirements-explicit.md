# Step 8: Negotiate SMTP content per message, with explicit legacy-server support

- Status: Implementation, production-code review and coding-guide pass complete and accepted on 25 September 2026. Delivery is tracked under #742; this is unreleased 10.0.0 work.
- Depends on: Step 4 capability reporting and the finalized-content contract from exact EML/rehearsal
- Child issue: [#742](https://github.com/bbottema/simple-java-mail/issues/742), under [#722](https://github.com/bbottema/simple-java-mail/issues/722)
- Milestone: 10.0.0; classification: `enhancement`, never also `major feature`
- Primary modules: `angus-mail-provider-module`, `simple-java-mail`, configuration/Spring and generated CLI support

## Goal and agreed correction

Do not mark ordinary ASCII-compatible email as requiring SMTPUTF8 just because its reusable transport can encode UTF-8. Select declarations per
submission without reconnecting, replacing pools, changing shared Session properties or rewriting protected content. Normal Unicode display names,
subjects and bodies retain their existing MIME encoding.

Missing advertisement does not establish that a server cannot accept the content. Legacy servers may accept unadvertised UTF-8 or 8-bit data, but
SMTP acceptance cannot establish that the delivery route will preserve it. Support those deployments through an explicit opt-in, not automatic fallback.

## Public API and configuration

```java
mailerBuilder.withLegacySmtpContentSupport(true);
```

```properties
simplejavamail.smtp.legacycontentsupport=true
```

- Default false; repeated builder calls replace the choice. Expose `OperationalConfig.isLegacySmtpContentSupportEnabled()`.
- Resolve the Boolean in the builder, inject it into operational configuration, and register visible `SMTP_CONNECTION` diagnostics.
- Include property files, Spring metadata and generated CLI help/options. The builder interface owns the contract; implementations link with `@see`.
- This is server compatibility, not Email/recipient policy, a new enum, an always-SMTPUTF8 mode, or permission to weaken transport security.

## Submission behavior

| Actual submission | Default | Explicit legacy opt-in |
| --- | --- | --- |
| ASCII-compatible envelope/content | No unnecessary SMTPUTF8 declaration | Same |
| Required capability advertised | Declare and use it | Same |
| SMTPUTF8 needed but not advertised | Reject before MAIL FROM | Attempt unchanged UTF-8 without automatically declaring SMTPUTF8 |
| 8BITMIME needed but not advertised | Reject before MAIL FROM | Attempt unchanged 8-bit content without automatically declaring BODY=8BITMIME |

- Determine requirements from actual envelope/content, not display names or an `8bit` label whose transmitted body is ASCII.
- Preserve existing capability-dependent Angus 8-bit conversion and deliberately supplied advanced provider extensions.
- Retain injection checks, well-formed UTF-8, binary/NUL/overlong-DATA restrictions and conflicting explicit BODY checks.
- Never substitute mailbox characters, strip accents or rewrite exact/DKIM/S/MIME/OpenPGP bytes. Never retry by weakening the requirements.
- Keep TLS, REQUIRETLS, explicit DSN requirements and internationalized ORCPT capability checks independent of the opt-in.
- Errors say that support is not advertised, explain the normal remedy, and offer the opt-in only for a verified legacy deployment.

## Provider and pool ownership

- Keep owned managed Angus transports UTF-8-capable for their lifetime, while honoring explicit `mail.mime.allowutf8=false`.
- Apply the per-submission declaration through the existing managed command hook, preserving the sender path and unrelated parameters. Angus still
  owns SMTP sequencing and command encoding. Never falsify the capabilities it reports.
- Carry immutable choices on the existing Angus message facade; clear active submission state in `finally` under the transport monitor.
- Carry compatibility permission through `PreparedMail`; existing constructors default to disabled. Resolve it from the actual selected Session's
  configuration, following clustered-server ownership. Do not introduce another mutable Session policy property.
- Install managed protocol handling for owned stock Angus Sessions independently of socket tracking. Preserve application socket factories;
  unavailable abort support stays unavailable. Initialize UTF-8 before MIME/transports, including offline preparation.
- Do not modify caller-owned Sessions, CustomMailer or third-party provider ownership. Ordinary caller-owned Angus retains its Session-wide declaration
  behavior. The content opt-in does not override explicitly disabled UTF-8 encoding.

## Verification and delivery

- [x] Exact wire parameters/bytes for ASCII, internationalized addresses, raw UTF-8 headers and 8-bit bodies with neither/either/both advertisements and both policies.
- [x] Tolerant and rejecting legacy peers; no altered-content retry and no claim of final delivery.
- [x] Same-connection alternating and concurrent pooled sends, post-STARTTLS capabilities, replacements, mixed cluster policies, failures and cancellation.
- [x] Simple batches/open connections, exact/protected bytes/signatures, quoting, explicit encoding/extensions, DSN and REQUIRETLS combinations.
- [x] Configuration precedence/isolation, diagnostics, Spring metadata/compatibility, CLI help/execution, classpath/JPMS and provider ownership.
- [x] Run focused tests, Java 11 library verification, the modern-JDK non-live reactor and website checks/build/link scan; complete license cleanup and coding-guide audit.
- [x] Feature/configuration/Spring/CLI guidance, release notes, architecture ownership and migration notes aligned.
- [x] Production changes reviewed and accepted. Selective commits and delivery are authorized; Phase 4 and the parent plan remain open for the remaining steps.

Migration notes compare with released versions: previously working unadvertised submissions can require the explicit legacy setting. Do not describe
changes between intermediate unreleased implementations as migration work. Document one reused Mailer for international mail, probes as diagnostics,
and legacy fallback as an application decision about its verified server and onward route. Preserve unrelated staged research, documentation and Journal work.

### Verification results

- The legacy integration suite passes 73 cases, including a 48-case content/capability/policy matrix; the separate SMTP content characterization suite passes 41 cases.
- Provider regressions cover finalized MIME-part UTF-8 headers as well as outer headers, omitted Bcc headers, explicit parameter conflicts and unchanged protected bytes.
- Real Java 11 library verification passed during implementation. The final Java 21 non-live reactor coverage includes Javadocs, classpath/JPMS consumers, generated CLI metadata and CLI process/daemon tests, with focused reruns of the corrected hostname-dependent fixtures after the full run.
- Final reports contain 1,527 passing tests, no failures or errors, and one intentionally disabled benchmark. The final Java 11 provider recheck passes 26 cases. The final `mvn license:remove` changes none of the 560 Java source files checked.
- All three existing Spring lanes pass: Boot 2.7.18 / Spring 5.3.39 / Java 11, Boot 3.0.13 / Spring 6.0.14 / Java 17, and Boot 3.5.16 / Spring 6.2.19 / Java 21.
- Website checks and a fresh isolated production build pass. The final internal-link scan passes 3,063 links across 61 pages; draft Journal pages are excluded from production output. The live preview and unrelated Journal files remain untouched. All 65 local link targets in the affected root documentation resolve.
- `mvn license:remove` ran with one formatter thread. Source-body comparison across 560 Java files confirms header cleanup changed no code. Four already-committed generated test headers, outside the plugin's main-source cleanup, were removed as well.
- The coding-guide audit is complete. MIME inspection now has named stages and named immutable result fields. Focused verification passed 343 cases, with a 24-case provider subset also passing on Java 11. Two existing deadline integration tests received more startup/network timing headroom for parallel test forks; their observer-exclusion and abort-before-socket-timeout assertions remain intact. Explicit EHLO and Message-ID fixture hostnames keep machine DNS delays outside DSN, cancellation, queue and protocol-fault test budgets. The parser test compares the parsed date with the source message rather than imposing a five-second conversion limit. Production deadlines are unchanged.
- No live-email demos, benchmarks or SpotBugs ran. Delivery includes only #742 and its verification cleanup; unrelated research, builder-policy documentation and Journal changes remain separate.

## Characterization and references

Angus 2.0.5 captures `mail.mime.allowutf8` at transport construction, adds SMTPUTF8 whenever offered even for ASCII mail, and can encode UTF-8 commands
without that advertisement. Its existing optional 8-bit conversion needs a corresponding BODY declaration. These facts motivate a per-submission hook,
not a second pool or a mutable encoding mode.

Legacy fallback deliberately operates outside negotiated extension guarantees. [Postfix documents legacy UTF-8 handling](https://www.postfix.org/SMTPUTF8_README.html);
that is not a guarantee about every server or onward hop.

- [RFC 6531: SMTPUTF8](https://www.rfc-editor.org/rfc/rfc6531.html#section-3.2)
- [RFC 6152: 8BITMIME](https://www.rfc-editor.org/rfc/rfc6152.html#section-3)
- [Angus 2.0.5 SMTP transport](https://github.com/eclipse-ee4j/angus-mail/blob/2.0.5/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java)
- [Shared send architecture](../../docs/concurrency/inside-a-mail-send.md#phase-completion-check)
