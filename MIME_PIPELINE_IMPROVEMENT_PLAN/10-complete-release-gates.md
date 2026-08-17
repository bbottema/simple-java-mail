# Step 10 — Complete compatibility, migration, and release gates

- Status: Done
- Depends on: Steps 1–9
- Target release: 10.0.0
- Primary areas: full verification, dependency publication, migration documentation, cleanup

## Goal

Prove the new architecture across artifact, provider, module, transport, and cryptographic combinations, then remove every temporary bridge and publish an unambiguous 10.0.0 migration path.

## Verification matrix

1. Java 8 source/bytecode build for all production modules and focused consumer projects.
2. Supported modern JDK full reactor build, Javadocs, JPMS consumers, and packaged module-name inspection.
3. Classpath and module-path use with provider-neutral conversion only.
4. Angus direct send, proxy send, authenticated proxy where applicable, async send, and batch/pooled send.
5. Basic send through the fake/alternate provider with no Angus classes present.
6. Plain, DKIM, S/MIME, OpenPGP, S/MIME-plus-DKIM, and OpenPGP-plus-DKIM messages, each with and without bounce/DSN where supported.
7. `8BITMIME` enabled/disabled, non-ASCII bodies, large attachments, repeated serialization, failure cleanup, and cancellation.
8. Inbound EML fixtures from Simple Java Mail and independent implementations, including tampered and missing-key cases.

## Work

1. Remove temporary compatibility bridges, subtype assertions, obsolete tests, and dead provider-specific helpers.
2. Verify published POM scopes and dependency trees from clean external consumers.
3. Release any required provider-neutral DKIM/S/MIME utility versions and the provider SPI/Angus adapter in bottom-up order.
4. Publish migration notes covering dependency changes, provider selection, unsupported adapter capabilities, OpenPGP configuration, and the S/MIME/OpenPGP exclusion.
5. Update security and configuration documentation with exact ordering and trust semantics.
6. Record command output, fixture provenance, artifact versions, and any consciously unsupported provider behavior in this file.

## Acceptance criteria

- [x] The complete Maven reactor passes on the required Java 8 and release JDK environments.
- [x] Clean classpath and JPMS consumers pass for provider-neutral, Angus, and optional crypto combinations.
- [x] Provider-neutral dependency trees contain no Angus; the Angus convenience path contains exactly the managed provider/adapter stack.
- [x] Captured protected messages verify after the real transport path, including `allow8bitmime=true` Sessions.
- [x] No MIME/crypto code imports Angus or branches on provider-specific message subtypes.
- [x] No temporary compatibility bridge or unchecked plan TODO remains.
- [x] The 10.0.0 migration guide gives existing `simple-java-mail` users a copyable dependency replacement.
- [x] Every prior step contains concrete completion evidence and is marked `Done`.

## Release record

### Artifacts and dependency versions

- Provider adapter: `org.simplejavamail:angus-mail-provider-module`, JPMS name `org.simplejavamail.mailprovider.angus`.
- OpenPGP module: `org.simplejavamail:openpgp-module`, JPMS name `org.simplejavamail.openpgp`.
- Angus Mail: 2.0.5. Bouncy Castle `bcpg-jdk18on`/`bcprov-jdk18on`: 1.78.1.
- Existing provider-neutral utility integrations: `utils-mail-dkim` 3.3.0 and `utils-mail-smime` 2.3.12, both with their Angus transitive dependency excluded.
- Independent OpenPGP fixture/client: OpenPGP.js 6.3.1.

### Verification results

- Java 8u152 / Maven 3.9.10: `mvn -q verify "-Dlicense.skip=true"` — passed, 183.7 seconds.
- Oracle JDK 21 / Maven 3.9.10: `mvn -q verify "-Dlicense.skip=true"` — passed, 211.6 seconds. This run includes clean classpath and JPMS provider-neutral consumers.
- OpenPGP.js: `node interop.mjs verify-sjm ../../../../target/openpgpjs-interop/sjm-signed.eml ../../../../target/openpgpjs-interop/sjm-encrypted.eml` — verified the Simple Java Mail signature and decrypted its ciphertext.
- The focused real-SMTP `protectedOpenPgpSurvivesAngusEightBitMimeTransport` test passed with DKIM, OpenPGP, bounce, DSN, and `mail.smtp.allow8bitmime=true`.
- The facade's compile dependency tree and the runtime tree with its default adapter excluded contain no `org.eclipse.angus` artifact. The normal runtime tree includes the adapter and Angus implementation automatically. Packaged JPMS descriptors place the Angus requirement and adapter service only in `angus-mail-provider-module`.
- Production-source scans find no obsolete subtype hooks or Angus imports outside the isolated adapter. `git diff --check` passes.

### Migration and release notes

- `MIGRATION-10.0.md` contains a copyable one-dependency default setup, Angus exclusion and alternate-provider guidance, the provider SPI contract, OpenPGP configuration, protection ordering/trust semantics, and repeatable-storage behavior.
- No artifact was published as part of this implementation task. Version/tag creation remains with the normal 10.0.0 release workflow.
- The implementation remains available as working-tree changes; the completed plan and removal of the superseded API documentation plan are committed separately as requested.
