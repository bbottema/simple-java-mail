# Angus contribution guide for the PIPELINING prototype

Internal working guide, 2026-10-14. Use this before editing and when reviewing the final diff. This is not a new policy for Angus. Related: [prototype plan](../plans/699-angus-pipelining-prototype.md), [proposal review](699-angus-pipelining-proposal-review.md), and [upstream discussion](https://github.com/eclipse-ee4j/angus-mail/issues/213).

Inspected upstream `master`: [`a7bd3fed964d9c9bc2981d4b2c9867358453c571`](https://github.com/eclipse-ee4j/angus-mail/commit/a7bd3fed964d9c9bc2981d4b2c9867358453c571), refreshed on 2026-10-14. References below describe this revision, not a future release.

## Documented requirements

- Follow the [published coding guidance](https://eclipse-ee4j.github.io/angus-mail/Contributions): four-space indentation units, eight-column tabs, opening braces on the same line, spaces after control-flow keywords, method documentation, and no trailing whitespace. Match nearby imports, wrapping and whitespace. Do not reformat whole files.
- Coordinate the feature before contributing. That discussion is already happening in #213; it is not approval of an untested implementation or of every proposed hook.
- Follow [CONTRIBUTING.md](https://github.com/eclipse-ee4j/angus-mail/blob/a7bd3fed964d9c9bc2981d4b2c9867358453c571/CONTRIBUTING.md): ECA and Signed-off-by for outside contributions. Preserve each file's actual license and SPDX header. Attribute new work accurately; do not assign it to an existing copyright owner by habit.
- Follow the maintainer's [preference against mocking-framework dependencies](https://github.com/eclipse-ee4j/angus-mail/pull/22#issuecomment-1222473973). Use the existing test fixtures or small test doubles.
- Treat [resource cleanup and explicit SpotBugs checks](https://github.com/eclipse-ee4j/angus-mail/pull/22#issuecomment-1227403064) as review requirements for this threading change. The [transport review](https://github.com/eclipse-ee4j/angus-mail/pull/22#discussion_r967929358) also addresses interrupt preservation and keeping the original failure.

The root contribution file and `www/Build-Instructions.md` contain legacy Jakarta Mail names, links and commands. Resolve operational details against current Angus files and direct maintainer instructions. Do not mechanically follow obsolete directory or release names.

## Observed conventions

### Production code and ownership

- The [root POM](https://github.com/eclipse-ee4j/angus-mail/blob/a7bd3fed964d9c9bc2981d4b2c9867358453c571/pom.xml) targets Java 11. Use ordinary classes, not records or newer language features.
- Canonical SMTP implementation and package Javadoc live in `providers/smtp/src/main/java/org/eclipse/angus/mail/smtp`. Wire tests live in `providers/angus-mail/src/test/java/org/eclipse/angus/mail/smtp`.
- The [combined provider build](https://github.com/eclipse-ee4j/angus-mail/blob/a7bd3fed964d9c9bc2981d4b2c9867358453c571/providers/angus-mail/pom.xml) unpacks dependency sources. Files under `target/generated-sources/dependencies` are generated copies, never editing targets.
- [SMTPTransport](https://github.com/eclipse-ee4j/angus-mail/blob/a7bd3fed964d9c9bc2981d4b2c9867358453c571/providers/smtp/src/main/java/org/eclipse/angus/mail/smtp/SMTPTransport.java) uses `PropUtil`, `MailLogger`, protocol tracing, Jakarta exceptions and explicit exception chains. Reuse these rather than adding logging, annotation or utility frameworks from SJM.
- Synchronized transport operations and `Thread.holdsLock(this)` assertions define state ownership. A helper must not acquire that monitor while the sending thread waits for it. Document which thread performs callbacks and state changes.
- Preserve null versus empty address arrays, partial-delivery state, listener notifications and exception chaining. These are observable behavior, not cleanup opportunities.
- Keep helpers private or package-private. Do not widen public API just to test a helper.
- No repository-wide formatter configuration was found. Nearby files and published guidance are the formatting baseline. Preserve existing encodings and line endings.

### Tests and resource lifecycle

- Tests use JUnit 4, its existing assertions and timeouts. Extend [SMTPHandler](https://github.com/eclipse-ee4j/angus-mail/blob/a7bd3fed964d9c9bc2981d4b2c9867358453c571/providers/angus-mail/src/test/java/org/eclipse/angus/mail/smtp/SMTPHandler.java) and `TestServer` where useful. A fixture must deliberately control replies to prove pipelining; the default immediate-reply fixture alone cannot do that.
- Use bounded waits and clear cleanup paths. Close owned sockets and join owned helpers in `finally`; preserve interruption. Do not shut down a caller-owned executor.
- `WriteTimeoutSocket` is an important adjacent lifecycle example. Test the actual stream wrappers and TLS paths, not only an in-memory queue.
- The [CI workflow](https://github.com/eclipse-ee4j/angus-mail/blob/a7bd3fed964d9c9bc2981d4b2c9867358453c571/.github/workflows/maven.yml) verifies on JDK 11, 17 and 21 with the release/staging/build-only profiles, GPG skipped, copyright checking and enforcer skipped. Match that invocation when reporting CI-equivalent verification.
- SpotBugs is configured but `spotbugs.skip` defaults to true. Explicitly enable it and confirm analysis output exists. Compare findings with the unmodified baseline; merely running `verify` is not a SpotBugs result.

### Documentation

- Extend the existing HTML property table in SMTP `package-info.java`. Include Boolean type, default, capability fallback, subclass behavior and helper resource cost. The package already explains the `smtps` prefix.
- Document protected hooks where declared, including callback timing, thread ownership, failure behavior and whether older hooks still run.
- Qualify the existing CHUNKING statement about no pipelining: it refers to BDAT chunks, not the new envelope option.
- `doc/src/main/resources/docs/CHANGES.txt` and `COMPAT.txt` are plain-text release and compatibility notes. Follow their existing heading/bullet format, not Markdown.
- The documentation build disables doclint. Main CI also excludes documentation-only paths. Generate and inspect the affected Javadoc page explicitly; a green Java build is insufficient.
- Master currently says `2.1.0-SNAPSHOT`, while discussion mentioned a different release. Finalize release headings and `@since` only when the target is confirmed.

## Our implementation choices, not upstream rules

- Keep all prototype research and evidence in SJM, outside the upstream patch. Keep Angus and SJM implementation diffs separate.
- One short-lived reader helper per enabled envelope exchange, immutable reply holders, and sender-owned callbacks/state. No shared executor, second connection pool or runtime dependency.
- Start with groups of at most 32 commands or 32 KiB of encoded commands. An oversized command travels alone. Reserve reply capacity before writing. These bounds do not prove TCP flow-control safety.
- Use real plain SMTP, STARTTLS and implicit TLS backpressure/abort tests to establish the reader-helper limitations. After the first experiment the user explicitly asked to continue a substantial local prototype while collecting concerns. Keep unresolved TLS progress/abort behavior visible; it prevents production-readiness claims, not further controlled prototype work.
- Keep old subclass behavior sequential unless the subclass explicitly opts in. Proposed protected hooks remain subject to upstream review.
- Read NioSmtpClient at `58c521bb6485a577409fc85b4a63ca5c87c9d4dd` as a checklist for ordering, negotiation, validation and terminal failures. Learning from that code is not permission to copy it. Record any copied implementation separately with its provenance and licensing; none is planned.
- Build the baseline and prototype into separate private Maven repositories with the same Jakarta API graph. Do not change SJM's production Angus pin or confuse pre-existing upgrade incompatibilities with feature regressions.

## Final diff review checklist

- [x] Canonical sources only; no generated copies, new runtime dependencies or unrelated formatting.
- [x] Java 11, appropriate visibility, neighboring style, complete hook/property documentation.
- [x] Sender/helper ownership, tracing, callbacks, failure cause and interruption checked.
- [x] Owned resource cleanup tested on success, rejection, timeout, disconnect and cancellation. The unresolved TLS key-update lock case is explicitly recorded, not marked safe.
- [x] Sequential compatibility, reply ordering, exception chains and events tested within the reported coverage.
- [x] Plain SMTP, STARTTLS and implicit TLS backpressure evidence retained, including the failing stress case.
- [x] JDK 11/17/21 verification, explicit SpotBugs baseline comparison and copyright checks recorded honestly. Full CI and copyright checks are not claimed green.
- [x] Generated Javadoc inspected; CHANGES/COMPAT format and pending release target noted.
- [x] Every changed file checked for license, attribution, whitespace and scope.
- [x] Draft PR explains limitations and meaningful AI assistance; no local-only research links in public text. No publication without a separate instruction.

Review completed for the local prototype on 2026-10-14. See the [per-file review and evidence](699-angus-pipelining-prototype-results.md#earlier-diffs-and-hygiene-review-2026-10-14). Checked items record review work, not production readiness.

Follow-up review on 2026-10-15 covers the actual-transport TLS fixture, corrected compatibility notes and removal of temporary measurement hooks. The full SJM on/off comparison, clean-artifact checks and narrower TLS conclusion are in the [updated local results](699-angus-pipelining-prototype-results.md#clean-artifact-confirmation-and-diff-hygiene). Research was initially excluded from Git; the user subsequently authorized committing it in SJM, but not in either Angus patch. Normal code, reproducible tests and API/lifecycle documentation remain in the proposed patches.

The separate SSLEngine branch is a test-only experiment. Use JUnit 4 and Java 11,
with child-process isolation for diagnostic TLS properties and bounded process
cleanup. Keep its server, certificate and run instructions self-contained. Do
not label the independent client as an integrated Angus Transport, or the
absence of reproduced stalls as proof of general progress. No production
provider source, dependency, release note or public API changes belong in this
experiment's patch.
