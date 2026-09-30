# ADR 0025: Factory-scoped locked configuration

- Status: Accepted
- Decision date: 2026-09-29
- Target: 10.0.0
- Implementation status: Planned, not implemented
- Tracking: [#740](https://github.com/bbottema/simple-java-mail/issues/740)

## Context and decision drivers

An application can receive centrally supplied mail configuration and still need to customize individual messages. Most settings should remain ordinary defaults: choose a different subject, add recipients, or use another timeout. A few settings may need to stay fixed within that configuration, such as the company relay, mandatory connection TLS, or an archive BCC recipient.

The existing defaults and overrides do not promise that restriction. Builder calls can replace ordinary configuration, Email suppression controls can skip templates, and exact messages deliberately bypass ordinary Email governance. Supplying more of those ordinary overrides through properties would make them easier to distribute, but would not make them non-removable.

The goal is to prevent unintended changes in applications that adopt a centrally supplied configuration. It is not to stop application code from deliberately choosing another configuration or transport.

The decision needs to balance the following:

- Centrally supplied values must survive later customization within the adopting factory, while ordinary settings remain customizable.
- Users should reuse familiar property names and types, without learning another public policy model.
- Message settings and operational settings must retain their existing owners rather than being forced into one Email template.
- Properties files and Spring YAML must support ordinary and locked values together, without a second runtime configuration loader.
- Required envelope behavior must survive the actual send path without changing exact or protected message bytes.
- The API must describe its local guarantee honestly, without implying control over application code or downstream delivery.

## Decision

Adopt factory-scoped locked configuration using a `simplejavamail.locked.` namespace. Store the resolved locks in the immutable configuration snapshot and reject conflicting customization through the existing configuration owners. This combines familiar configuration values with an explicit restriction that ordinary defaults and overrides do not provide.

### Declare locked values in a separate namespace

Support a locked counterpart for existing Simple Java Mail properties:

```properties
simplejavamail.locked.<existing-property-tail>=<value>
```

For example:

```properties
simplejavamail.smtp.port=587

simplejavamail.locked.smtp.host=relay.company.com
simplejavamail.locked.transportstrategy=SMTP_TLS
simplejavamail.locked.defaults.requiretls=true
simplejavamail.locked.defaults.bcc.address=archive@company.com
```

The locked entry contains the configured value, not a Boolean marker referring to a separate ordinary value. Reuse the existing property's type, parser, validation, sensitivity rules and metadata definition. The `defaults` part of a message property's name identifies its existing configuration route; a locked counterpart is not a suppressible Email default.

Ordinary and locked values coexist naturally in nested YAML:

```yaml
simplejavamail:
  smtp:
    port: 587
  locked:
    smtp:
      host: relay.company.com
    transportstrategy: SMTP_TLS
    defaults:
      requiretls: true
      bcc:
        address: archive@company.com
```

These examples describe the accepted target, not configuration syntax implemented at the time of this record.

### Keep locks on the factory's immutable snapshot

Locks belong to `SimpleJavaMailConfig`, alongside the resolved configuration captured by the factory. Every Mailer created from that factory inherits them. Creating another Mailer from the same factory does not remove a lock. Deliberately creating an independently configured factory remains possible.

Configuration-source resolution and subsequent customization are separate concerns. Keep the existing source precedence when resolving ordinary and locked entries into the snapshot. A lock is not a privileged configuration source or a trust ranking among property files, environment variables and application code. An ordinary property cannot displace its locked counterpart, regardless of where that ordinary value came from.

For a locked scalar, the locked value supplies the effective configuration. Reject explicit customization that conflicts with it; allow the same parsed value. Ordinary, unlocked settings remain customizable as before. Detect conflicts at the appropriate builder or preparation boundary, before an incompatible operation reaches submission, rather than silently accepting a customization that will not take effect.

### Use the existing configuration owners

Apply message-related locks through Email governance and operational locks through the existing Mailer configuration owners. Do not add a requirement enum, a third public Email template, or feature-specific Mailer setters for message settings.

Per-email default/override suppression must not remove a lock. Neither replacement of ordinary templates nor selection of a different send entry point may silently bypass it. Preserve the existing unlocked defaults/overrides behavior; locks are an explicit additional restriction on participating factories.

Collections retain their meaningful additive behavior. A locked archive BCC requires that recipient's inclusion in the actual SMTP submission envelope. It does not replace or freeze the complete recipient list. Checking only the Email's BCC getter is insufficient if an explicit envelope or receiver override later changes who receives the message. This requirement is about including the recipient in the submission, not guaranteeing delivery to the archive.

Do not assume every collection or compound value can use one generic merge rule. Use the existing setting's meaning and value boundary when deciding what must be retained and what constitutes a conflict.

### Preserve exact and protected content

Locks do not authorize rewriting exact EML, signed content or other protected message bytes. Honor compatible envelope-only locks without changing those bytes. If a path cannot honor a lock while preserving its other contracts, reject the incompatible operation instead of silently ignoring the lock or rewriting the content.

For example, an archive recipient must appear in the real envelope, but adding that recipient must not silently break the existing encryption-recipient contract. A content-related lock on an exact message cannot be implemented by reconstructing the message. The implementation must establish which requirements it can honor or verify and reject unsupported combinations clearly.

Apply the same reasoning at provider boundaries. A caller-owned Session, CustomMailer or third-party provider must not silently bypass an applicable lock. Establish what Simple Java Mail can guarantee before delegating, and reject an incompatible path where it cannot uphold the requirement. This decision does not claim that all such paths already support locks, or that a submission receipt proves downstream compliance.

### Keep Spring on the shared loader

Spring continues resolving values through `Environment` and `SpringEnvironmentConfigSource` into the shared configuration loader and immutable snapshot. Do not introduce a second runtime binding or resolution route for locks.

`SimpleJavaMailProperties` remains an IDE-metadata model, not the runtime configuration binder. Its leaf fields do not become wrapper objects. Reuse the existing property definitions to provide metadata for locked counterparts, without duplicating types, parsers or sensitivity rules. Preserve plain-Spring support without adding a Boot runtime dependency.

## Alternatives considered

### Ordinary property-backed overrides

This would reuse the existing override mechanism and make centrally supplied values easier to distribute without introducing locks. It is useful when those values should remain replaceable.

Rejected for this requirement because existing overrides can be replaced or suppressed, and exact messages bypass ordinary Email governance. Distribution is not the same as restricting later customization. Making ordinary overrides non-removable would instead change their established contract, including for callers that did not opt into locking.

### A third `policyOverrides` Email template

An additional non-suppressible Email template would resemble the existing defaults/overrides API and could reuse its message-configuration routes. It would also let applications express message policy using familiar Java builders.

Rejected because it introduces another public configuration layer and cannot naturally represent Mailer-owned settings such as the relay, connection TLS or timeouts. Covering those would require a second policy surface or moving operational settings onto Email. Reuse the internal governance routes without introducing a third public template.

### A dedicated requirement enum or policy model

Named requirements could offer a small vocabulary for selected guarantees, such as requiring onward TLS, and make those supported cases easy to discover.

Rejected because that vocabulary would duplicate existing configuration concepts and need to grow independently as new settings become relevant. It would also need additional mechanisms for values such as an archive address. The chosen scope is restrictions on existing configured values, not a separate language for evaluating compliance or minimum security levels.

### Suffix-based locks and wrapped property leaves

A suffix such as `simplejavamail.smtp.host.locked` puts the locked counterpart close to the ordinary property's name. A wrapper containing `value` and `locked` could express both parts explicitly.

Rejected because a scalar `smtp.host` and a nested `smtp.host.locked` cannot occupy that same location naturally in nested YAML. Wrapped leaves would change the configuration shape for ordinary values and multiply metadata structures. Spring does not require that redesign: `SimpleJavaMailProperties` is an IDE-metadata model, while the shared loader reads values through `Environment`.

### A separate `locked` namespace on the factory snapshot

Selected because it accommodates message and operational settings without changing their ownership, keeps ordinary and locked values distinct in properties and YAML, and reuses existing types and parsing. Factory ownership gives all participating Mailers the same restrictions without introducing process-global state.

The cost is explicit lock handling in the schema, diagnostics and customization paths. Reusing a property definition does not make its locking semantics automatic: scalar conflicts, additive collections, compound values and provider boundaries still need deliberate handling and tests.

## Consequences and tradeoffs

### Benefits

Applications can vary ordinary settings while keeping selected centrally supplied values fixed within one factory. Creating another Mailer from that factory does not accidentally discard those restrictions. The configuration stays recognizable, and Spring users keep the same runtime loading path.

The decision preserves the distinction between message and operational configuration. It also preserves the meaning of additive requirements: an archive BCC requires inclusion, not replacement of the application's complete recipient list.

### Costs and limitations

Implementation and maintenance become more demanding. Conflicts must be detected across builder customization, template replacement, suppression and send preparation. Future configuration additions need to account for lock behavior as well as ordinary precedence; reusing a parser alone is insufficient.

Diagnostics and IDE metadata must distinguish ordinary and locked entries without exposing secret values or drifting from the canonical property definitions. Source precedence explains where the configured value came from; it does not by itself explain why later customization was rejected.

Some operations that work with ordinary configuration will be rejected when locks are enabled. Exact content, recipient encryption and opaque provider paths can make a requirement impossible to honor. Preserving those contracts means rejecting incompatible operations rather than promising universal compatibility or quietly dropping the restriction. Unlocked configuration retains its existing behavior.

The guarantee remains local to the adopting factory. Independent factories are still possible, and including a requirement in an SMTP submission does not establish downstream compliance or final delivery.

## Boundaries

### Local configuration restrictions, not a security sandbox

Application code can use a different factory, another library, or a transport directly. Adopting the factory is the boundary of participation; the lock protects configuration within that boundary, not against code that intentionally leaves it. There is no server-verifiable configuration hash or authentication protocol in this decision.

At this boundary, the previously discussed names "locked" and "enforced" describe equivalent behavior: conflicting customization is rejected and the configured requirement remains applicable. The chosen name describes a local configuration restriction without suggesting organization-wide control. Renaming it does not make it stronger or weaker.

### Existing settings, not a new compliance framework

The namespace applies to existing Simple Java Mail configuration. It does not justify duplicating Angus settings or expanding provider-specific configuration merely to offer more locks. Do not introduce a general-purpose compliance evaluator or duplicate message settings on Mailer.

First-hop TLS and onward REQUIRETLS remain different settings. Locking either one does not establish final delivery or downstream behavior. Exact-message preservation and the existing configuration-owner boundaries remain in force.

## Related decisions

The following records retain their existing unlocked behavior. This ADR adds an opt-in restriction; it does not claim that those implementations already enforce locks.

| Record | Relationship |
| --- | --- |
| [0001: Email configuration scopes](0001-email-configuration-scopes-and-inheritance.md) | Keep message, recipient and operational ownership where it belongs; do not duplicate message setters on Mailer. |
| [0002: Email defaults and overrides](0002-email-defaults-and-overrides.md) | Retain ordinary template precedence, collection behavior and suppression. A lock is not another suppressible template. |
| [0003: Immutable configuration snapshots](0003-immutable-configuration-snapshots.md) | Keep source resolution and factory isolation. The snapshot additionally carries the restrictions on later customization. |
| [0004: Configuration provenance diagnostics](0004-configuration-provenance-diagnostics.md) | Preserve safe provenance and redaction, and distinguish locked declarations from ordinary values in diagnostics. |
| [0005: Spring integration](0005-spring-integration-and-boot-compatibility.md) | Keep Environment-based construction and the separation between runtime loading and IDE metadata. |
| [0013: Exact EML submission](0013-exact-eml-submission.md) | Preserve authoritative bytes and the ordinary template bypass. That bypass must not silently remove a lock; reject incompatible operations. |
| [0023: Per-message REQUIRETLS](0023-per-message-requiretls.md) | Keep onward TLS separate from connection TLS. A locked REQUIRETLS value is non-suppressible within the factory, unlike the existing ordinary default. |

## Implementation status and verification obligations

Implementation is planned under [#740](https://github.com/bbottema/simple-java-mail/issues/740), not delivered by this record. Follow the [API expansion workflow](../API_EXPANSION_WORKFLOW.md) and [coding guide](../CODING_STYLE_GUIDE.md). Before claiming support, cover:

- **Resolution and conflicts:** reuse schema parsing and validation, retain source precedence, allow equal parsed values, reject conflicting explicit customization, and keep unlocked behavior unchanged. Verify copying, clearing, builder reuse and replacement templates cannot accidentally lose a lock.
- **Factory isolation:** all Mailers from one snapshot inherit its locks; separately configured factories and concurrent loads remain isolated. Do not introduce process-global lock state.
- **Diagnostics:** retain the source of each locked value and distinguish it from an ordinary counterpart. Reuse redaction and safe display formatting; conflict messages and metadata must not expose secrets.
- **Spring and metadata:** cover properties files, nested YAML, profiles, placeholders and existing aliases or wildcard entries where applicable. Verify IDE metadata coverage without turning the metadata model into a runtime binder.
- **Message and envelope handling:** cover scalar, compound and additive collection settings through ordinary sends, batches, open connections, validation and rehearsal. Check the actual envelope after receiver overrides, including required archive recipients and relevant encryption-recipient handling.
- **Exact and protected messages:** prove unchanged bytes for compatible operations and clear rejection for incompatible requirements. Do not silently exempt exact submission or apply ordinary composition templates to it.
- **Ownership and providers:** characterize selected cluster configurations, caller-owned Sessions, CustomMailer and provider adapters. Keep checks with the responsible owner and reject unsupported combinations before submission rather than claiming a guarantee the chosen path cannot provide.

These are implementation and test obligations, not results of this documentation change. The decision is accepted for 10.0.0; locked configuration remains planned and not implemented.
