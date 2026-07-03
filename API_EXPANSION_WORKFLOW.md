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
- **CLI Compatibility Rules**:
  - **Parameter Types**: Use simple types (`String`, `boolean`, `int`, `long`) or types that have an existing `ValueInterpreter` in the `cli-module` (e.g., `X509Certificate`, `File`, `URL`, `Date`).
  - **Avoid Collections**: Picocli mapping works best with individual values or arrays. Avoid `Collection` or `Map` in signatures intended for CLI use. Provide overloads if necessary.
  - **Javadoc**: Provide complete Javadoc for all new methods and parameters. The CLI module uses this to generate help text.
  - **Annotations**: Use `@Cli.ExcludeApi` for methods that should not be exposed to the CLI (e.g., those taking complex Java-only objects). Use `@Cli.OptionNameOverride` if the method name isn't ideal for a CLI flag.

## 3. Core Implementation (`simple-java-mail`)

Implement the new API methods and ensure data propagation.

- **Update Builder Implementations**: Update `EmailPopulatingBuilderImpl`, `RecipientsBuilder`, `RecipientBuilder`, etc.
- **CRITICAL: Data Propagation**:
  - Ensure that "copy" methods (e.g., `withRecipient(Recipient)`) and delegation methods correctly copy the new field.
  - Failure to do this will result in data being lost when `EmailBuilder.copying(email)` is used or when builders delegate to each other.
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
  - Update `SimpleJavaMailProperties` to include the new property.
  - Update `SimpleJavaMailSpringSupport` to map the Spring property to the `ConfigLoader` and builders.

## 6. Defaults & Overrides (EmailGovernance)

When a new field should participate in the defaults/overrides system (a source Email used to provide defaults and/or overrides), integrate it with the governance layer.

- Add EmailProperty entry (core-module)
  - If the field is on Email and needs default/override resolution, add a corresponding constant to org.simplejavamail.internal.config.EmailProperty.
  - Mark it as collection-based when the value is a collection so merging is applied instead of replacement.
- Apply default values (simple-java-mail)
  - In EmailGovernanceImpl.newDefaultsEmailWithDefaultDefaults(), derive a sensible default from ConfigLoader.Property if applicable and set it on the builder.
- Apply defaults/overrides to provided Email (simple-java-mail)
  - In EmailGovernanceImpl.produceEmailApplyingDefaultsAndOverrides(), resolve values using MiscUtil.overrideOrProvideOrDefaultProperty / overrideAndOrProvideAndOrDefaultCollection and apply them to the builder.
  - Ensure ignoringDefaults / ignoringOverrides and the per-property suppression sets are respected (this comes for free when using the MiscUtil helpers).
- Module-triggering fields
  - If the new field influences downstream processing (e.g., per-recipient S/MIME), make sure SpecializedMimeMessageProducer considers the presence of the field when deciding to trigger the corresponding module.
  - Ensure the corresponding module implementation tolerates null global config if the trigger is a per-item value.
- Per-recipient fields
  - Do not try to default/override sub-fields inside Recipient via governance. Instead, set them when building recipients (through IRecipientsBuilder / RecipientsBuilder) and let module logic act on their presence.

## 7. Configuration Support (`core-module`)

If the new field should be configurable via system properties or environment variables:

- **ConfigLoader**: Add a new entry to the `Property` enum.
- **Data Resolution**: Ensure the new property is used in `EmailGovernanceImpl` or wherever defaults are applied.

## 8. Verification Surface Areas

Always verify the following areas:

- **Builder Chain**: Verify the field is preserved across multiple builder calls.
- **Email Copying**: Use `EmailBuilder.copying(email).buildEmail()` and verify the field is still there.
- **CLI Help**: Run the CLI with `--help` for the relevant command to ensure the new option is documented and has the correct parameter labels.
- **End-to-End**: Verify the field actually affects the final `MimeMessage` (e.g., by inspecting the produced EML or using a dummy SMTP server).

There are junit tests available to verify the above or provide a blueprint for new tests.

---
*Blueprint version 1.0*
