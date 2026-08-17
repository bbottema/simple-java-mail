# Step 8 — Make provider-neutral packaging real

- Status: Done
- Depends on: Steps 4, 6, and 7
- Primary areas: Maven dependencies, JPMS descriptors, activation handlers, consumer samples

## Goal

Remove Angus as a hard implementation dependency from `core-module` and the `simple-java-mail` facade while retaining a straightforward, officially supported Angus installation path.

## Packaging direction

- `core-module` and `simple-java-mail` depend on Jakarta Mail/Activation APIs only.
- The Angus adapter module is the only Simple Java Mail module that compiles against `org.eclipse.angus`.
- The Angus adapter artifact brings the compatible Angus implementation and can serve as the one-dependency convenience path for applications that want the current default stack.
- Applications choosing another provider depend on the provider-neutral facade plus that provider and its matching adapter.

Exact artifact names are frozen with the SPI in Step 3, but these dependency directions are not optional.

## Work

1. Remove the runtime Angus dependency from `core-module` and the compile dependency from `simple-java-mail`.
2. Remove `requires transitive org.eclipse.angus.mail` from the facade module descriptor.
3. Replace `MimeMessageParser`'s extension of Angus `text_plain` with a Jakarta Activation-based handler owned by Simple Java Mail.
4. Confirm DKIM, S/MIME, and OpenPGP optional modules do not reintroduce Angus transitively.
5. Add provider-neutral and Angus-convenience Maven/Gradle examples and a 10.0.0 migration note.
6. Add classpath and JPMS consumer projects that compile and run without Angus present.
7. Give conversion-only use a fully functional no-provider path and give send attempts without a provider an actionable dependency error.

## Acceptance criteria

- [x] `rg org.eclipse.angus` over provider-neutral production sources finds only documentation or the isolated adapter module.
- [x] Dependency trees for `core-module` and provider-neutral `simple-java-mail` contain no Angus artifact.
- [x] Their JPMS descriptors neither require nor expose an Angus module.
- [x] A clean consumer converts Email/EML using only API/provider-neutral artifacts.
- [x] A clean consumer sends through the Angus convenience artifact with current behavior.
- [x] A fake or alternate Jakarta provider sends a basic message without loading an Angus class.
- [x] Missing provider/adapter and unsupported-provider-feature errors name the required corrective action.
- [x] Documentation says plainly that removing Angus does not remove the need for some Jakarta Mail implementation when sending.

## Completion evidence

- Production-source scans find `org.eclipse.angus` only in `angus-mail-provider-module`; the provider-neutral facade, MIME pipeline, and crypto modules have no provider import.
- The provider-neutral runtime path contains `core-module`, Jakarta Mail/Activation APIs, `jmail`, `throwing-function`, and `slf4j-api`, with no Angus artifact.
- Packaged module descriptors show no Angus requirement in `org.simplejavamail`, while `org.simplejavamail.mailprovider.angus` alone requires `org.eclipse.angus.mail` and provides the adapter service.
- Java 21 verification compiles and runs clean classpath and JPMS consumers that convert MIME and send with a fake Jakarta `Transport` after asserting that Angus is not loadable.
- Full live SMTP tests exercise the supported Angus convenience path and adapter behavior.
- `MIGRATION-10.0.md` gives Maven and Gradle examples for provider-neutral, Angus, and alternate-provider installations and explains that sending always needs a Jakarta Mail implementation.
