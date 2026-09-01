# API Expansion Workflow for Simple Java Mail

This document serves as a blueprint for developers and coding agents when adding new fields or features to the Simple Java Mail API. Following these steps ensures that the new functionality is correctly integrated across all modules, including CLI support, message conversion, and module-specific processing.

For surrounding mechanisms such as optional module loading, CLI data generation, MIME structure selection, and build instrumentation, see [PROJECT_MECHANISMS_CATALOGUE.md](PROJECT_MECHANISMS_CATALOGUE.md).

---

## 1. Core Model Expansion (`core-module`)

The foundation of any new feature usually starts with updating the core model objects.

- **Update Model Classes**: Add the new field to the relevant model class (e.g., `Email`, `Recipient`).
  - Use Lombok `@Value` for immutability where appropriate.
  - Ensure the class remains `Serializable`.
  - Update `serialVersionUID` if the change breaks binary compatibility.
- **Update Internal Interfaces**: If the model has an internal interface (e.g., `InternalEmail`), ensure it's updated if necessary.

## 2. API Interface Expansion (`core-module`)

New fields must be accessible through the fluent Builder API.

- **Update Builder Interfaces**: Add new methods to the public builder interfaces (e.g., `EmailPopulatingBuilder`, `IRecipientsBuilder`, `IRecipientBuilder`).
- **Javadoc Source of Truth**: Put the complete behavior contract on the public builder interface. Document convenience overloads well enough to stand on their own and link them to the full overload. Builder implementations, factories, Spring support, and other entry points should use `@see` links to the public builder method instead of maintaining a second copy of its semantics.
- **CLI Compatibility Rules**:
  - **Parameter Types**: Use simple types (`String`, `boolean`, `int`, `long`) or types that have an existing `ValueInterpreter` in the `cli-module` (e.g., `X509Certificate`, `File`, `URL`, `Date`).
  - **Avoid Collections**: Picocli mapping works best with individual values or arrays. Avoid `Collection` or `Map` in signatures intended for CLI use. Provide overloads if necessary.
  - **Javadoc**: Provide complete Javadoc for all new methods and parameters. The CLI module uses this to generate help text.
  - **Annotations**: Use `@Cli.ExcludeApi` for methods that should not be exposed to the CLI (e.g., those taking complex Java-only objects). Use `@Cli.OptionNameOverride` if the method name isn't ideal for a CLI flag. Use `@Cli.Optional` on parameters that may be omitted from the CLI; keep JetBrains `@Nullable` for Java/API nullability only.

## 3. Core Implementation (`simple-java-mail`)

Implement the new API methods and ensure data propagation.

- **Update Builder Implementations**: Update `EmailPopulatingBuilderImpl`, `RecipientsBuilder`, `RecipientBuilder`, etc.
- **CRITICAL: Data Propagation**:
  - Ensure that "copy" methods (e.g., `withRecipient(Recipient)`) and delegation methods correctly copy the new field.
  - Failure to do this will result in data being lost when `simpleJavaMail.emailBuilder().copying(email)` is used or when builders delegate to each other.
- **Update Email Constructor**: Ensure the `Email` constructor copies the new field from the builder.
- **Utility Classes**: Update `MiscUtil` if it contains helper methods for object creation or parsing (e.g., `interpretRecipient`).

## 4. Message Conversion & Processing (`simple-java-mail`)

The new field must eventually affect the produced `MimeMessage`.

- **MimeMessageHelper**: Update this class if the new field translates directly to a standard MimeMessage header or property (e.g., a new recipient type or a standard header).
- **SpecializedMimeMessageProducer**: Update the `populateMimeMessage` method if the new field requires logic to decide how the `MimeMessage` is constructed or if it triggers module-specific processing (like S/MIME or DKIM).

## 5. Module-Specific Integration

If the feature relates to a specific module, update that module.

- **S/MIME (`smime-module`)**:
  - Update `SMIMEModule` interface in `core-module`.
  - Implement the logic in `SMIMESupport`.
- **Outlook (`outlook-module`)**:
  - Update `OutlookEmailConverter` if the new field has an equivalent in Outlook `.msg` files.
- **Spring (`spring-module`)**:
  - Update the metadata-only `SimpleJavaMailProperties` model so Spring Boot generates an IDE hint for the new property. Mirror the canonical key's nested shape; this class is not used for runtime binding.
  - Do not add scalar properties individually to `SimpleJavaMailSpringSupport` or `SpringEnvironmentConfigSource`. The latter enumerates `ConfigLoader.Property`, resolves each value through Spring's `Environment`, and forwards it to the context-local `SimpleJavaMailConfig` snapshot automatically.
  - Add a compatibility alias to `SpringEnvironmentConfigSource` only when an existing Spring-facing property name must remain supported. Wildcard namespaces still need explicit discovery because Spring cannot enumerate them through ordinary scalar lookup.
  - Run `SpringModulePackagingTest` to prove the generated `spring-configuration-metadata.json` describes every `ConfigLoader.Property`. Never commit the generated `target` output.

## 6. Defaults & Overrides (EmailGovernance)

Public API configuration should have parity across the Java builder API and the Java defaults/overrides mechanism. When a new user-facing field is added to `Email` or a related model and that value can be represented on a source object, integrate it with the governance layer. This allows projects that centralize behavior through default or override `Email` objects to use the same feature without per-message Java code.

Only skip defaults/overrides integration when the value cannot sensibly be represented on the source model, when it depends on runtime state that cannot be copied, or when it is a per-recipient sub-field that should be set while constructing recipients instead. Document the reason in the implementing issue or PR.

- Add EmailProperty entry (core-module)
  - If the field is on Email and needs default/override resolution, add a corresponding constant to org.simplejavamail.internal.config.EmailProperty.
  - Mark it as collection-based when the value is a collection so merging is applied instead of replacement.
- Apply default values (simple-java-mail)
  - In `EmailGovernanceImpl.newDefaultsEmailWithDefaultDefaults()`, derive a sensible default from the Mailer's `SimpleJavaMailConfig` snapshot if applicable and set it on the builder.
- Apply defaults/overrides to provided Email (simple-java-mail)
  - In EmailGovernanceImpl.produceEmailApplyingDefaultsAndOverrides(), resolve values using MiscUtil.overrideOrProvideOrDefaultProperty / overrideAndOrProvideAndOrDefaultCollection and apply them to the builder.
  - Ensure ignoringDefaults / ignoringOverrides and the per-property suppression sets are respected (this comes for free when using the MiscUtil helpers).
- Module-triggering fields
  - If the new field influences downstream processing (e.g., per-recipient S/MIME), make sure SpecializedMimeMessageProducer considers the presence of the field when deciding to trigger the corresponding module.
  - Ensure the corresponding module implementation tolerates null global config if the trigger is a per-item value.
- Per-recipient fields
  - Do not try to default/override sub-fields inside Recipient via governance. Instead, set them when building recipients (through IRecipientsBuilder / RecipientsBuilder) and let module logic act on their presence.

## 7. Mailer Configuration API Expansion

Mailer configuration API changes are separate from Email model/defaults/overrides governance. Use this path for SMTP/session/runtime behavior such as connection settings, proxy behavior, debug output, transport mode, trust settings, and other Mailer-owned state.

- **Public API**: Add methods to `MailerGenericBuilder`, `MailerRegularBuilder`, or `MailerFromSessionBuilder` based on ownership. Provide complete Javadoc and CLI annotations because the CLI help text is generated from these builder APIs.
- **Operational Ownership**: Add state to `OperationalConfig`, `ServerConfig`, `ProxyConfig`, or another existing Mailer config interface according to the behavior being configured.
- **Property Defaults**: If the setting is property-friendly, add a `ConfigLoader.Property` entry and resolve it when creating the builder/config object. This keeps property-file driven projects configurable without Java code.
- **Typed Schema**: Register the property's declared value type in `PropertySchema`. Do not infer a type from the text shape or parse values independently at individual read points.
- **Transport Strategy Mapping**: When a Mailer setting maps to Jakarta Mail properties, keep the `mail.smtp.*` / `mail.smtps.*` names behind `TransportStrategy` helper methods and apply them only after the effective strategy is known.
- **Spring Mapping**: If the property belongs to the public configuration surface, represent it in the metadata-only `SimpleJavaMailProperties` model. Runtime scalar discovery follows `ConfigLoader.Property` automatically; only compatibility aliases and new wildcard namespaces require changes in `SpringEnvironmentConfigSource`.
- **Verification**: Test the Java builder path, property/config path, Spring mapping when applicable, and the final `Session` properties.
- **Governance Boundary**: Do not wire Mailer connection/session settings into Email defaults/overrides. That mechanism applies to Email/message state and related model values.

## 8. Configuration Support (`core-module`)

Public API configuration should also have parity with property-backed configuration. When a Java API option represents configurable behavior and can be expressed as strings, booleans, numbers, enums, files, or other property-friendly values, expose it through configuration properties as well. This keeps property-file driven projects from needing a Java-only escape hatch for the same feature.

Only skip property configuration when the value cannot be expressed safely or clearly in properties, has no sensible global default, or would require complex object construction that belongs in Java code. Document the reason in the implementing issue or PR.

- **Property Identifier**: Add a new entry to `ConfigLoader.Property` using the canonical public key.
- **Typed Resolution**: Register the property in `PropertySchema`, then read it from the injected `SimpleJavaMailConfig` snapshot in `EmailGovernanceImpl`, the Mailer builder/config object, or wherever defaults are applied. Do not add a static read or a second parser.
- **Factory Propagation**: Prove that builders from `SimpleJavaMail.withConfig(config)` retain the snapshot through copy, reply, conversion, Session creation, governance, and any applicable optional-module route.
- **Spring Mapping**: If the property belongs to the public configuration surface, add its IDE-hint shape to `SimpleJavaMailProperties` and verify `SpringModulePackagingTest`. `SpringEnvironmentConfigSource` already exposes every scalar `ConfigLoader.Property` through the context-local snapshot while retaining Spring's precedence and placeholder resolution.
- **Dynamic Property Collections**: For collection-style namespaces such as `simplejavamail.defaults.connectionpool.clusters.*`, keep parsing and validation centralized in `ConfigLoader`. Spring support should forward the whole namespace into `ConfigLoader` and Spring Boot metadata should describe the nested shape, rather than duplicating alias/key resolution.

## 9. Verification Surface Areas

Always verify the following areas:

- **Builder Chain**: Verify the field is preserved across multiple builder calls.
- **Email Copying**: Use `simpleJavaMail.emailBuilder().copying(email).buildEmail()` and verify the field is still there.
- **CLI Help**: Run the CLI with `--help` for the relevant command to ensure the new option is documented and has the correct parameter labels.
- **End-to-End**: Verify the field actually affects the final `MimeMessage` (e.g., by inspecting the produced EML or using a dummy SMTP server).

There are junit tests available to verify the above or provide a blueprint for new tests.

---
*Blueprint version 1.0*
