# Step 8 — Make provider-neutral packaging real

- Status: Done
- Depends on: Steps 4, 6, and 7
- Primary areas: Maven dependencies, JPMS descriptors, activation handlers, consumer samples

## Goal

Remove Angus from the source and JPMS contracts of `core-module` and the `simple-java-mail` facade while retaining Angus as the default, replaceable runtime implementation.

## Packaging direction

- `core-module` depends on Jakarta Mail/Activation APIs only.
- `simple-java-mail` includes the Angus adapter as its default runtime dependency, so the normal sending setup remains a single dependency.
- The Angus adapter module is the only Simple Java Mail module that compiles against `org.eclipse.angus`.
- Excluding the Angus adapter from `simple-java-mail` also removes its transitive Angus implementation without changing the facade's API or JPMS descriptor.
- Applications choosing another provider exclude the default adapter and add that provider and its matching adapter.

Exact artifact names are frozen with the SPI in Step 3, but these dependency directions are not optional.

## Work

1. Remove Angus from `core-module` and replace the facade's direct implementation dependency with a runtime dependency on the isolated Angus adapter.
2. Remove `requires transitive org.eclipse.angus.mail` from the facade module descriptor.
3. Replace `MimeMessageParser`'s extension of Angus `text_plain` with a Jakarta Activation-based handler owned by Simple Java Mail.
4. Confirm DKIM, S/MIME, and OpenPGP optional modules do not reintroduce Angus transitively.
5. Add default-Angus and provider-replacement Maven/Gradle examples and a 10.0.0 migration note.
6. Add classpath and JPMS consumer projects that compile and run without Angus present.
7. Give conversion-only use a fully functional no-provider path and give send attempts without a provider an actionable dependency error.

## Acceptance criteria

- [x] `rg org.eclipse.angus` over provider-neutral production sources finds only documentation or the isolated adapter module.
- [x] Dependency trees for `core-module` and `simple-java-mail` with its default adapter excluded contain no Angus artifact.
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
- `MIGRATION-10.0.md` gives a one-dependency default setup plus Maven and Gradle exclusion examples for conversion-only and alternate-provider installations.
