# API documentation improvement plan

This backlog turns the 9.1.5 API-documentation audit into small, verifiable work items. Each finding has its own Markdown file so it can be discussed, implemented, reviewed, and closed independently.

Audit baseline: Simple Java Mail 9.1.5, 5 August 2026.

## Working method

1. Work through the phases in order unless an item explicitly has no dependency.
2. Change an item's `Status` from `Planned` to `In progress` before editing code or documentation.
3. If documentation exposed a code defect, fix and test the code before describing the resulting behavior.
4. Check every acceptance criterion in the item file.
5. Run the phase gate, mark the item `Done`, and check it off below.

Status vocabulary: `Planned`, `In progress`, `Blocked`, `Done`.

## Phase 1 — Security and correctness

These items correct claims that could lead users to deploy an insecure or operationally unsafe configuration.

- [x] [Bind the authenticated SOCKS bridge to loopback](01-security-and-correctness/01-authenticated-socks-loopback.md)
- [x] [Document the TLS trust model accurately](01-security-and-correctness/02-tls-trust-model.md)
- [x] [Make embedded-image resolution a real containment boundary](01-security-and-correctness/03-embedded-image-containment.md)
- [x] [Replace unsafe DKIM examples](01-security-and-correctness/04-dkim-safe-defaults.md)
- [x] [Define S/MIME signature verification precisely](01-security-and-correctness/05-smime-verification-semantics.md)
- [x] [Remove unsupported cluster failover claims](01-security-and-correctness/06-cluster-failover-claims.md)

Phase gate: targeted security tests pass, security examples use safe defaults, and no page promises behavior the implementation does not provide.

## Phase 2 — Copyable API examples

These items make the examples compile against 9.1.5 and behave as their surrounding text promises.

- [x] [Correct the validator reset example](02-copyable-api-examples/01-validator-reset-example.md)
- [x] [Replace removed ConfigLoader overloads](02-copyable-api-examples/02-configloader-overloads.md)
- [x] [Repair the delivery-receipt example](02-copyable-api-examples/03-delivery-receipt-example.md)
- [x] [Update both reply content alternatives](02-copyable-api-examples/04-reply-content-alternatives.md)
- [x] [Correct Outlook MSG conversion calls](02-copyable-api-examples/05-outlook-msg-conversion.md)
- [x] [Restore dedicated CLI recipient flags](02-copyable-api-examples/06-cli-recipient-flags.md)
- [x] [Add the CLI argument-file subcommand](02-copyable-api-examples/07-cli-argument-file-subcommand.md)
- [x] [Correct the testConnection overload](02-copyable-api-examples/08-test-connection-overload.md)
- [x] [Correct DKIM private-key overloads](02-copyable-api-examples/09-dkim-private-key-overloads.md)
- [x] [Define the DKIM property key format](02-copyable-api-examples/10-dkim-property-key-format.md)
- [x] [Use a valid S/MIME content cipher](02-copyable-api-examples/11-smime-content-cipher.md)
- [x] [Repair remaining Java snippet syntax](02-copyable-api-examples/12-java-snippet-syntax.md)

Phase gate: concrete Java examples have been reviewed for syntax and current API usage, and current CLI examples pass a smoke test.

## Phase 3 — Behavioral and operational accuracy

These items align explanations with actual defaults, lifecycle, conversion, module, and runtime behavior.

- [x] [Clarify ConfigLoader replacement semantics](03-behavior-and-operations/01-configloader-replacement-semantics.md)
- [x] [Resolve extra-property precedence](03-behavior-and-operations/02-extra-properties-precedence.md)
- [ ] [Explain proxying with a custom Session](03-behavior-and-operations/03-custom-session-proxy.md)
- [ ] [Document the SMTPS proxy restriction](03-behavior-and-operations/04-smtps-proxy-compatibility.md)
- [ ] [Define Mailer.validate scope](03-behavior-and-operations/05-mailer-validation-scope.md)
- [x] [Define clearEmailValidator scope](03-behavior-and-operations/06-clear-validator-scope.md)
- [ ] [Separate startingBlank from mailer defaults](03-behavior-and-operations/07-starting-blank-and-defaults.md)
- [ ] [Describe converter header preservation accurately](03-behavior-and-operations/08-converter-header-preservation.md)
- [ ] [Document generated attachment Content-IDs](03-behavior-and-operations/09-generated-attachment-content-ids.md)
- [ ] [Update serialization transient fields](03-behavior-and-operations/10-serialization-transient-fields.md)
- [ ] [Update bounce-address implementation details](03-behavior-and-operations/11-bounce-address-implementation.md)
- [ ] [Explain synchronous and asynchronous failures](03-behavior-and-operations/12-future-failure-semantics.md)
- [ ] [Remove batch retry claims](03-behavior-and-operations/13-batch-retry-claims.md)
- [ ] [Update Outlook module dependencies](03-behavior-and-operations/14-outlook-module-dependencies.md)
- [ ] [Remove the obsolete Karaf artifact note](03-behavior-and-operations/15-karaf-artifact-note.md)
- [ ] [Describe Spring dependencies as provided](03-behavior-and-operations/16-spring-provided-dependencies.md)
- [ ] [Describe Log4j configuration as an example](03-behavior-and-operations/17-log4j-example-wording.md)
- [ ] [Update the CLI exec-plugin version](03-behavior-and-operations/18-cli-exec-plugin-version.md)
- [ ] [Remove Base64 security terminology](03-behavior-and-operations/19-base64-terminology.md)

Phase gate: every default and operational claim has a source or test anchor, and dependency descriptions match current POMs.

## Phase 4 — Missing API coverage

These items add important public behavior that is currently absent from the guides.

- [x] [Add Mailer lifecycle and ownership guidance](04-missing-coverage/01-mailer-lifecycle.md)
- [x] [Cover reply and body-editing APIs](04-missing-coverage/02-reply-and-body-api.md)
- [x] [Support refresh-aware OAuth2 access-token providers](04-missing-coverage/03-oauth2-token-lifecycle.md)
- [ ] [Document withOpenConnection limitations](04-missing-coverage/04-open-connection-custom-mailer.md)
- [ ] [Explain ConfigLoader snapshot timing](04-missing-coverage/05-configloader-snapshot-timing.md)
- [ ] [Document environment-variable syntax](04-missing-coverage/06-environment-variable-syntax.md)
- [ ] [Document proxy bridge-port collisions](04-missing-coverage/07-proxy-bridge-port-collisions.md)
- [ ] [Document withDebugPrinter](04-missing-coverage/08-custom-debug-printer.md)
- [ ] [Add the version 8 migration guide](04-missing-coverage/09-version-8-migration-guide.md)
- [ ] [Clarify the template-engine boundary](04-missing-coverage/10-templating-boundary.md)

Phase gate: the newly covered APIs are reachable from the left navigation or a closely related section and are indexed by site search.

## Phase 5 — Code and source-Javadoc defects

These items repair implementation or source-Javadoc defects found while validating the website.

- [ ] [Fix resetConnectionPoolMaxSize](05-code-and-javadoc-defects/01-reset-connection-pool-max-size.md)
- [ ] [Fix resetConnectionPoolClaimTimeoutMillis](05-code-and-javadoc-defects/02-reset-connection-pool-claim-timeout.md)
- [ ] [Correct resetDisableAllClientValidations Javadoc](05-code-and-javadoc-defects/03-reset-disable-client-validation-javadoc.md)
- [ ] [Replace stale source-Javadoc website links](05-code-and-javadoc-defects/04-stale-source-javadoc-links.md)

Phase gate: targeted unit tests cover reset contracts, core and facade Javadocs generate, and source-Javadoc links resolve to current routes.

## Final verification

From `simplejavamail.org`:

```text
npm run check
npm run verifyLinks
```

From the repository root, run the targeted module tests for every code change, then the complete Maven test suite in a release-capable environment.

The plan is complete when every item is `Done`, all checkboxes above are checked, website and Javadoc builds pass, concrete examples have been reviewed against the current API, and the website contains no claims contradicted by the 9.1.5 implementation.
