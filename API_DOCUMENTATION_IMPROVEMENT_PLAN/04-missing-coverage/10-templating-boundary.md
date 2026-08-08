# Clarify the template-engine boundary

- Status: Done
- Priority: Low
- Work: Documentation

## Gap

The content documentation does not state whether Simple Java Mail includes a general message-template engine. It does not; applications render Thymeleaf, Freemarker, Mustache, or other templates externally and pass the resulting body.

## Plan

Add one short note with a generic render-then-build flow. Distinguish general content templates from reply quoting templates.

## Acceptance criteria

- [x] No built-in general template engine is implied.
- [x] The integration boundary is understandable without endorsing one engine.
- [x] Reply quoting templates are identified as a separate feature.

## Evidence

- Capabilities now shows the engine-neutral render-then-build flow beside basic usage, including paired plain-text and HTML output.
- The same note links reply quoting separately and explains the narrow `%s` substitution without presenting it as general message rendering.
- Future template support, an engine-neutral extension point, and optional integrations are tracked in [#695](https://github.com/bbottema/simple-java-mail/issues/695) for 10.0.0.
- Verification: website type/check task, production build, and 1,404-link internal link check passed.
- Website commit: `96bdfeb docs(content): explain template integration [skip ci]`.
