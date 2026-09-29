# ADR 0024: Treat builder interfaces as library-owned API, not extension SPIs

- Status: Accepted
- Decision recorded: 2026-09-18
- Applies to: All Simple Java Mail builder interfaces
- Implementation status: Existing architectural intent, now recorded as project policy

## Context

Simple Java Mail exposes fluent builders through interfaces while keeping their implementations in the library. This separates the clean API from implementation details and gives derived integrations, including the generated CLI, a stable API surface to inspect.

Because the interfaces are public, an API review can otherwise assume that applications are expected to provide their own implementations. That assumption would make each new abstract builder method a compatibility concern and encourage default methods whose only purpose is to keep unsupported external implementations compiling.

That is not the role of these interfaces. Applications are expected to call and type against the builder interfaces, but Simple Java Mail owns their implementations.

## Decision

Treat every Simple Java Mail builder interface, public or internal, as a library-owned API contract, not as an extension SPI. This applies to Mailer builders, Email builders, recipient builders and other fluent builder interfaces.

Applications may use public builder interfaces as types, but implementations outside Simple Java Mail are unsupported. Adding an abstract method to a builder interface therefore does not require a default implementation solely to preserve external custom implementations. Implement the method in the library's own builder classes and verify the affected Java behavior and generated integrations.

Continue to review compatibility for callers of the builder API. This decision does not make it acceptable to remove or rename existing methods, change their parameter or return types, or silently change their behavior. It only excludes external interface implementations from the project's compatibility contract.

## Alternatives and consequences

Declaring the builder interfaces extension SPIs would require a supported implementation contract, compatibility defaults and tests for third-party implementations. The project rejects that additional contract because it conflicts with the interfaces' purpose as a clean facade over library-owned implementations.

Default methods remain available when they express useful shared API behavior. They should not be introduced merely as compatibility shims for unsupported implementations.

This decision keeps builder API expansion direct, but the library must update every one of its affected implementations in the same change. Reflection-based consumers such as the CLI must also be regenerated or verified when the new method is in their supported surface.

## Implementation anchors

- [Mailer builder interfaces](../../modules/core-module/src/main/java/org/simplejavamail/api/mailer), [Email builder interfaces](../../modules/core-module/src/main/java/org/simplejavamail/api/email)
- [Mailer builder implementations](../../modules/simple-java-mail/src/main/java/org/simplejavamail/mailer/internal), [Email builder implementations](../../modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal), [recipient builder implementations](../../modules/simple-java-mail/src/main/java/org/simplejavamail/recipient)
- [ADR 0018: Generate the CLI from builder contracts](0018-cli-from-builder-contracts.md), [API expansion workflow](../API_EXPANSION_WORKFLOW.md)
