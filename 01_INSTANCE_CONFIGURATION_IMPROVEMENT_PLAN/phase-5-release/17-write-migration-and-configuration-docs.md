# Step 17: Write migration notes and durable configuration documentation

- Status: Done
- Depends on: Steps 14 through 16
- Primary repositories: root library checkout and the dedicated 10.0.0 website branch in `simplejavamail.org`
- Primary files: `MIGRATION-10.0.md`, `README.md`, `RELEASE.txt`, public Javadocs, configuration/Spring/CLI website pages, mechanism docs

## Goal

Give 9.x users a direct migration for every real public break and rewrite the normal configuration docs so they stand on their own after the migration guide becomes old.

## Migration notes

Add concise before/after sections for:

1. Every removed `EmailBuilder` entry method, using `SimpleJavaMail.fromDefaults().emailBuilder()` or an application-owned factory.
2. Every removed `MailerBuilder` entry method, using `SimpleJavaMail.fromDefaults().mailerBuilder()` or an application-owned factory.
3. `ConfigLoader.loadProperties(..., false)` followed by default builder access.
4. `addProperties=true` source layering.
5. The old returned map's live-view behavior versus a detached immutable snapshot.
6. Static config inspection.
7. Multiple independent configurations.
8. Replacement-object lifecycle and closing the old Mailer.
9. `fromDefaults()` first-use timing and its implied source recipe.
10. `TransportStrategy.SMTP.setOpportunisticTLS(...)`, with the replacement explicitly described as plain-`SMTP` only.
11. Direct `MailerImpl.createMailSession(...)` use where affected.
12. Spring config/factory/Mailer beans and removal of the `defaultMailerBuilder` bean.
13. Spring Environment precedence replacing the extra raw JVM overlay.
14. Corrected embedded-image URL/classpath Spring mapping. This item is mandatory even if the implementation fix looks trivial by release time.
15. Property-specific parsing where direct ConfigLoader callers could observe a different Java type.

Do not list internal constructor changes, moved private helpers, test cleanup, or implementation package changes that do not affect a supported public use.

## Durable documentation

1. Rewrite property loading around immutable snapshots and ordered sources.
2. Explain `SimpleJavaMail.fromDefaults()` separately from explicit application-owned configuration, including everything the short form implies.
3. Show a two-tenant/two-SMTP example in one JVM.
4. Show replacement configuration with safe Mailer handoff.
5. Document exact precedence and blank handling.
6. Document custom sources and clarify that source decryption belongs outside Simple Java Mail.
7. Explain Spring's config, configured factory, and default Mailer beans. Builders always come from the injected factory.
8. Update CLI property-file wording while keeping its simple `etc/simplejavamail.properties` route.
9. Update `API_EXPANSION_WORKFLOW.md` and `PROJECT_MECHANISMS_CATALOGUE.md` so future properties use the schema and snapshot pipeline.
10. Strengthen Javadocs for thread-safety, snapshot timing, stream ownership, secrets, and lifecycle.

## Copy rules

- Speak directly to the developer.
- Use plain language and concrete examples.
- Avoid sales language, robotic phrasing, and em dashes.
- Do not make durable pages depend on a migration guide for basic usage.
- Keep migration notes limited to public breaks and non-obvious behavior changes.

## Verification

- Compile every Java snippet against the 10.0 API.
- Check every old snippet against 9.3.2 where it claims to be a before example.
- Build the website from the actual updated website checkout.
- Check links and rendered navigation.

## Acceptance criteria

- [x] Every Step 3 migration row has one copyable replacement.
- [x] Every removed `EmailBuilder` and `MailerBuilder` entry has a direct equivalent without recreating a second shortcut API.
- [x] The embedded URL/classpath correction is still present as a named behavior change.
- [x] The opportunistic-TLS section says plainly that it only affects `SMTP`.
- [x] Migration notes contain no internal-only churn.
- [x] The normal configuration page explains the complete current API without referring readers back to migration notes.
- [x] Spring, CLI, multiple-config, and replacement examples compile.
- [x] Secret handling is accurate and does not imply built-in encryption.
- [x] Root docs and website copy agree.
- [x] The root docs and website build pass on their respective 10.0.0 branches.

## Completion evidence

- `MIGRATION-10.0.md` covers only public breaks and non-obvious behavior, including every builder entry, static loader use, Spring bean change, snapshot timing, SMTP-only opportunistic TLS, and the embedded URL/classpath correction.
- Root README, workflow, mechanism catalogue, and the website configuration, features, security, CLI, debugging, download, migration, pooling, and rationale pages describe the current API directly.
- Website `npm run build`, `npm run check`, and `npm run verifyLinks:internal` passed: 24 pages, 23 indexed, and 1,998 internal links checked.
