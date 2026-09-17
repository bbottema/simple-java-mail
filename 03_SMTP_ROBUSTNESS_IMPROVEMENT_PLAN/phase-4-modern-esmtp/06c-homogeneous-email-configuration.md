# Homogeneous Email configuration: implementation slices

- Decision: [ADR 0001](../../docs/adr/0001-email-configuration-scopes-and-inheritance.md) and [ADR 0002](../../docs/adr/0002-email-defaults-and-overrides.md)
- Branches: `codex/10.0.0` and website `codex/10.0.0-content`
- Delivery: Production batches, holistic corrections and the final coding-guide pass accepted on 17 September 2026. Selective semantic commits and delivery authorized for #737 and #738; #736 retains its separate ENVID scope. Earlier uncommitted checkpoints below are historical.

## Final review checkpoint: 17 September 2026

The final coding-guide pass clarified envelope preparation, validation predicates and provider-command lifecycle methods without changing the accepted contract. All 169 focused Java 11 tests passed after those changes. The preceding full non-live Java 21 reactor passed 1,343 tests with no failures/errors and one existing skip, including CLI, Spring/starter and packaged classpath/JPMS consumers. Generated license headers were removed. Website checks and clean builds passed; only the already-recorded unrelated Journal links remain outside this work.

The final delivery refresh regenerates both CLI metadata files after the last public Javadoc edits. The shared infographic remains unchanged because no owner, lock, worker or resource lifetime changed. Step 6 is complete; the rest of Phase 4 is not.

## Settled behavior

Email owns message configuration; Mailer message policy uses Email templates. Recipient/group fields participate only where they have recipient-specific meaning. Preserve the established rule: an explicit recipient value beats the governance-resolved Email fallback, even when that Email field came from Mailer overrides. Group-fixed values affect the group's produced recipients, not a separate global policy layer.

A supplied defaults template replaces the property-derived template; clearing it restores snapshot-derived defaults. Do not introduce implicit merging, change collection/header semantics, or make reference-template suppression flags into another hierarchy.

## Slice 1: One governance path for DKIM

Status: Implemented locally and verified; uncommitted for review.

Tracking: [#737 — Unify DKIM and S/MIME configuration through Email defaults and overrides](https://github.com/bbottema/simple-java-mail/issues/737), a 10.0.0 enhancement under #722. Recipient NOTIFY and ORCPT in slices 2 and 3 are outside that issue's acceptance criteria.

- Remove the dedicated Mailer DKIM defaults setters, clear method, accessors, stored configuration/flag, and resolver branch.
- Resolve DKIM through `EmailProperty.DKIM_SIGNING_CONFIG` and the common defaults/explicit/override/suppression path.
- Allow an incomplete signing template without a From address; require a sender on the prepared message before sending or signing.
- Keep property-backed DKIM configuration and Spring metadata unchanged. Document how to materialize snapshot defaults when a Java template should retain them, and how to omit only DKIM from that template.
- Correct default/override/clear Javadocs; migrate production documentation, tests, and external API consumers. Remove the generated `--mailer:clearDefaultDkimSigning` option alongside its source API; property-driven signing and existing generic default opt-outs remain. Do not edit historical website versions or concurrent Journal work.
- Test precedence, suppression, template replacement/clear, configuration isolation, recipient-certificate precedence, incomplete templates, and sender validation. Regenerate CLI metadata and verify the affected library/CLI/website paths.

### Review and verification checkpoint — 2026-09-16

- Production review starts with `MailerGenericBuilder` / `MailerGenericBuilderImpl`, then `EmailGovernanceImpl`, then the small `EmailPopulatingBuilder` / `EmailPopulatingBuilderImpl` changes. No additional configuration abstraction or recipient hierarchy was introduced.
- ADRs 0001 and 0002 now state the existing cross-scope precedence as settled. Javadocs, README, release notes, website examples, and migration notes describe the shared DKIM path and actual template/clear behavior.
- Preserving other property defaults requires materializing and copying the defaults template. That reads key files and decodes inline key material before DKIM can be cleared. An explicit replacement template skips property-derived message defaults entirely. Tests cover this migration boundary, including malformed Base64 input.
- The optional DKIM header-exclusions property may be omitted; fixed the old unconditional non-empty check uncovered by this coverage.
- Java 11 non-live library `clean verify` passed, including Javadocs, classpath/JPMS consumers, Spring, and starter tests. The Java 21 full non-live `clean verify -Ppublish-cli` also passed, including CLI help/removal tests and metadata regeneration. No SpotBugs, benchmarks, or live-email demos ran.
- Website checks (24 helper tests, TypeScript, and templates) and clean build passed. Internal-link verification reports 12 broken links, all originating in the unrelated Journal work; no failing links originate on the changed documentation pages. Journal sources were left untouched, including their separate example migration work.
- Audited the touched Java changes against `CODING_STYLE_GUIDE.md`; ran `mvn license:remove`. Existing staged research and unrelated changes remain untouched. No commits, pushes, or issue state changes were made.
- Verification logs are in ignored `tmp/homogeneous-configuration-20260916/`.

## Slice 2: Recipient-level notification preferences

Status: Implemented locally and verified; uncommitted for review.

Tracking: [#738 — Add recipient DSN NOTIFY preferences and automatic ORCPT](https://github.com/bbottema/simple-java-mail/issues/738), a 10.0.0 enhancement under #722, In Progress. This issue owns slices 2 and 3; #736 remains ENVID-only and #737 owns the security-configuration consolidation.

Add only NOTIFY preferences to recipient/group configuration, retaining RET and ENVID as transaction fields. Follow existing group default/fixed/clear conventions, copy metadata through all paths, and keep explicit NEVER distinct from absence. Resolve complete preference sets without unioning in less-specific events. Preserve the Email fallback rule above.

## Slice 3: Provider submission and automatic ORCPT

Status: Implemented locally and verified; uncommitted for review.

Tracking: [#738](https://github.com/bbottema/simple-java-mail/issues/738), together with slice 2. Root and website migration notes now describe the automatic ORCPT wire behavior and its provider-support boundaries, alongside the feature documentation.

Derive ORCPT from actual envelope-recipient occurrences when DSN is supported, independently of requesting notifications. Carry effective recipient preferences through a verified provider path without changing MIME bytes, shared Session state, or unrelated transport lifecycle behavior. Cover exact/protected content, duplicates, unsupported providers, pooled reuse/concurrency, failures, and SMTP wire commands before claiming support.

The latter two slices use the settled hierarchy and Mailer ownership without adding another policy layer. Their API wiring, provider integration, tests and documentation are now implemented locally; all three slices still await review.

### Concrete implementation and support boundary

- Recipient carries only an immutable NOTIFY set; empty means inherit and NEVER is explicit. The four-argument constructor and serialization UID remain compatible. Recipient/group builders use the established default/fixed/clear semantics. Email copying, typed groups, override receivers and exact-EML recipient input preserve the metadata.
- `DeliveryEnvelope` carries ordered `DeliveryRecipient` entries, not an address-keyed map. The bundled adapter checks alignment before sending and mirrors Angus group expansion for policy/reporting only. `AngusRecipientCommands` augments RCPT parameters in `ManagedAngusTransport`'s existing hook; Angus retains its loop and response classification. Per-attempt policy is cleared in finally under the existing transport monitor. No new locks, pool, executor or lifecycle are introduced.
- Explicit recipient NOTIFY requires the managed Angus path and usable DSN on the actual connection. The SPI default declines recipient preferences until a third-party adapter opts in. Caller-owned ordinary Angus Sessions and custom socket factories retain shared NOTIFY/RET/ENVID, but bypass the managed command hook and cannot apply recipient preferences or automatic ORCPT. CustomMailer receives the immutable recipient data and owns mapping/enforcement.
- Capability failures before MAIL FROM use the now-shared SPI `MailTransportCompatibilityException`, allowing reuse of healthy pooled transports. SMTP transaction failures still invalidate leases as before. Fixed-ENVID preflight failures follow the same healthy-lease rule.
- ORCPT is automatic, independently of NOTIFY, and uses the actual envelope mailbox. ASCII uses xtext; negotiated UTF-8 commands use RFC 6533 unitext. Omit optional ORCPT rather than misrepresent unsupported addresses or exceed the 500-octet parameter bound. No override for gateway/forwarding use is exposed.
- Existing property/Spring settings still feed the shared Email fallback. Recipient-level policy remains Java builder configuration, like recipient S/MIME certificates; no new CLI recipient DSL or global property is added. The typed exact-recipient overload explicitly excludes CLI generation.

### Review and verification checkpoint — 2026-09-16

- Production review order: `Recipient` and the recipient/group builder interfaces; builder/copy propagation and exact recipients; `DeliveryRecipient` / `DeliveryEnvelope` / converter; then `AngusRecipientCommands`, the managed transport hook and the existing message facade. The new public SPI compatibility exception is the existing internal exception moved to the provider boundary, not a new error hierarchy.
- Focused tests cover immutable/replacing preferences, independent S/MIME and NOTIFY group policies, serialization, Email overrides, TO/CC/BCC ordering, mailbox normalization, exact/protected content, UTF-8/xtext and the 500-octet boundary. Wire tests cover sync/async, duplicates, simultaneous pooled sends, healthy lease reuse after capability rejection, partial-failure invalidation, a pool-size-one reentrant observer, lazy batches, open connections, post-STARTTLS capabilities, raw Sessions/socket factories and CustomMailer.
- Java 11 non-live library `clean verify` passed, including Javadocs, classpath/JPMS consumers and Spring/starter tests. The final additional mailbox-normalization regression also passed on Java 11. Java 21 `clean verify -Ppublish-cli` passed, including CLI exclusion/help tests and metadata generation. Existing queue, cancellation, deadline, observer and pooling suites ran as part of the reactors. No live-email demos, SpotBugs or opt-in benchmarks ran.
- One existing probe-output assertion was flaky because it excluded the digits `235` anywhere, including random port numbers. It now excludes the fixture's complete authentication reply, `235 authenticated`; credential checks are unchanged.
- Website checks (24 helper tests, TypeScript and templates) and clean build passed. Internal-link verification still reports 12 failures, all originating in unrelated Journal pages; the changed feature/configuration/provider/result pages introduce no reported failures. Journal work remains untouched. A separate file-target check passed for the 34 relative links in the new implementation checkpoint, characterization/ENVID plans and the two scope/governance ADRs.
- The coding-guide audit keeps validation/copying in one small shared helper and RCPT formatting in one immutable per-attempt helper. The existing group-policy enum is reused, with independent policy fields; there is no generic hierarchy or new concurrency mechanism. The plan summaries and infographic review ledger now reflect the local follow-up without marking Phase 4 complete or expanding #736.
- Final Java 11 regression pass: 47 tests, no failures/errors/skips. `mvn license:remove` passed afterward; no generated license headers remain in the added changes. The four pre-existing malformed headers are unchanged. Root and scoped website `git diff --check` passed, and the original five staged paths remain unchanged.
- Logs: `tmp/recipient-dsn-java11-final.log`, `tmp/recipient-dsn-java21-verify.log`, `tmp/recipient-dsn-final-regressions.log`, `tmp/recipient-dsn-license-remove.log` and `tmp/recipient-dsn-site-final-*.log`. Changes remain uncommitted; staged research and unrelated work are preserved.

### Selected review corrections — 2026-09-16

- Distinguish the selected ENVID from the identifier Angus consumes immediately before issuing MAIL FROM. The existing per-attempt message facade records that provider callback, so caller-owned Angus Sessions are covered without introducing pooled transport state. Local recipient/sender failures report no identifier; an attempted command or later SMTP failure retains it without claiming acceptance.
- Rehearsal, validation and sending now use the same local envelope preparation. Recipient-policy alignment errors are caught by rehearsal at either depth; negotiated DSN checks remain send-only. Exact-message rehearsal still preserves the supplied bytes.
- Added 15 regression cases covering generated/fixed identifiers, early local failures, command-write failure, managed/caller-owned connection reuse, observer receipts, both rehearsal depths and exact envelopes. Full non-live Java 11 `clean verify` passed (1,165 tests, one skipped); 113 focused tests passed on Java 21. Logs: `tmp/dsn-review-java11-verify.log`, `tmp/dsn-review-java21-focused.log`, `tmp/dsn-review-license-remove.log`.
- Coding-guide review retained the existing provider/conversion boundaries and added no public API or concurrency mechanism. The website's existing contracts remain accurate; no website or Journal edits were needed. Changes remain uncommitted for review.

### Typed Java API correction — 2026-09-16

- Removed the unnecessary new recipient/group string overloads; callers choose documented `DeliveryStatusNotification.NotifyOption` enum values. Added the missing typed notification/return methods on `ExactEmailBuilder`, delegating text inputs through the same implementation. Existing Email text-input methods remain for compatibility, not as the primary Java examples.
- Documented the enum values in delivery-notification terms, with success distinguished from SMTP acceptance and read receipts. Updated Java examples, migration notes, and #738. The API expansion workflow now explicitly requires discoverable typed choices and text conversion at integration boundaries.
- The typed recipient API, exact-byte preservation, replacement/absence/NEVER semantics and existing text inputs have focused coverage. Classpath/JPMS consumers compile against the typed methods; CLI tests retain existing text options and exclude Java-only typed overloads.
- Verification: 67 focused Java 11 tests passed, followed by full non-live Java 11 verification (1,169 tests) and Java 21 verification including CLI (1,313 tests), each with no failures/errors and one existing skip. Regenerated CLI metadata before verifying its text-input/help and cold-cache tests; ran `mvn license:remove` afterward. Logs: `tmp/typed-dsn-java11-focused.log`, `tmp/typed-dsn-java11-verify.log`, `tmp/typed-dsn-cli-metadata.log`, `tmp/typed-dsn-java21-verify.log` and `tmp/typed-dsn-license-remove.log`.
- Website checks and clean build passed. Internal-link verification still reports the same 12 unrelated Journal links, with no failing links originating from the edited feature/migration pages; see `tmp/typed-dsn-site-*.log`. Reviewed the touched Java changes against the coding guide: the public interfaces own the contracts, implementations use `@see`, and text conversion delegates to the existing typed value path.

### Review correction: typed CLI conversion

- Supersedes the earlier decision to retain Email String overloads for CLI input. Both composed and exact builders now expose only typed `withDeliveryStatusNotificationNotifyOptions` and `withDeliveryStatusNotificationReturnOption` methods.
- Registered `NotifyOption[]` and `ReturnOption` converters reuse the existing DSN parsers. Existing CLI flag names, required arguments, case-insensitive notification lists and return aliases are preserved; recipient/group policies remain Java-only.
- Property-backed defaults convert their text at the configuration boundary. The property/Spring schema and accepted values are unchanged. Migration notes document the Java source change, and the API expansion workflow explicitly directs future CLI support through typed converters.
- No provider, envelope, lifecycle or synchronization behavior changed; the shared concurrency infographic remains accurate. Changes remain uncommitted for review; Journal work is untouched.
- Migration wording is checked against release tags: the composed-builder String overloads already existed in 9.0.0; the exact-email builder is new in 10.0.0. The migration entry describes only the released API removal and changed automatic SMTP envelope parameters, not new recipient APIs or intermediate unreleased signatures.
- Verification: 132 focused Java 11 tests and 45 focused CLI tests passed, including unchanged aliases and required CLI values. An obsolete audit coupling Java nullability to CLI optionality was corrected to follow ADR 0009. CLI metadata regenerated; website checks/build and `mvn license:remove` passed. The probe assertions that mistook port digits for SMTP replies now check the fixture's authentication replies and sensitive payloads instead, including the equivalent CLI checks; 57 focused probe tests passed. Full non-live Java 21 verification including CLI then passed with 1,343 tests, no failures/errors and one existing skip (`tmp/probe-auth-assertion-full-verify.log`). The website link scan still reports only the 12 unrelated Journal links.

### Review correction: actionable envelope errors — 2026-09-17

- The local recipient-count mismatch now reports both counts and directs callers to `withRecipients(...)` instead of raw To/Cc/Bcc headers, or to `withOverrideReceivers(...)` for a separate delivery list. It no longer suggests multiple mailboxes per ordinary `Recipient` as the cause.
- Related Angus errors distinguish unsupported configuration from provider-integration failures. Session/socket-factory explanations are conditional; missing DSN support explicitly means the recipient requests cannot be sent. PreparedMail alignment instructions apply only to integrations constructing that SPI value; ordinary callers are directed to report an internal mismatch. Unexpected command-format/count errors no longer imply the caller configured recipient preferences.
- No sending behavior or public API changed. All 158 focused provider, recipient, envelope, governance and shared send/rehearsal/validation tests passed; message assertions cover the corrective guidance. Ran `mvn license:remove` and `git diff --check`. Logs: `tmp/dsn-message-clarity-focused.log` and `tmp/dsn-message-clarity-license-remove.log`. Changes remain uncommitted.

### Review correction: recipient-command readability — 2026-09-17

- Separate recipient-policy order validation from Angus RFC-address-group expansion. Explain that normal preparation already pairs the lists; the provider-boundary guard rejects inconsistent SPI input and never guesses, reorders or deduplicates recipients.
- Name and document the recipient NOTIFY override: Angus has already applied its single Email/Session fallback to every RCPT command, so only an explicit recipient preference replaces that parameter before it reaches the connection.
- Reuse the existing Angus xtext encoder for ASCII ORCPT. Keep RFC 6533 unitext separate, with four explicit character replacements, and retain control-character, malformed-Unicode and encoded-length checks. No transport lifecycle, public API or wire semantics changed.
- All 164 focused Java 11 tests passed, including six added regressions for reordered policies, empty address groups, NOTIFY parameter preservation, ASCII encoder reuse, unsafe characters and Unicode escape handling. Existing tests cover concurrent pooled attempts, connection reuse and shared send/rehearsal validation. Ran `mvn license:remove` and `git diff --check`; no generated headers remain on the touched Java files. Logs: `tmp/dsn-command-readability-focused.log` and `tmp/dsn-command-readability-license-remove.log`. This revised production batch remains uncommitted for review.
