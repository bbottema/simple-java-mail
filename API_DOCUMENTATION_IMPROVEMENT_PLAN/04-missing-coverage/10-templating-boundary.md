# Clarify the template-engine boundary

- Status: Planned
- Priority: Low
- Work: Documentation

## Gap

The content documentation does not state whether Simple Java Mail includes a general message-template engine. It does not; applications render Thymeleaf, Freemarker, Mustache, or other templates externally and pass the resulting body.

## Plan

Add one short note with a generic render-then-build flow. Distinguish general content templates from reply quoting templates.

## Acceptance criteria

- [ ] No built-in general template engine is implied.
- [ ] The integration boundary is understandable without endorsing one engine.
- [ ] Reply quoting templates are identified as a separate feature.

## Evidence

- Body APIs: `modules/core-module/src/main/java/org/simplejavamail/api/email/EmailPopulatingBuilder.java`
- Reply templates: `modules/core-module/src/main/java/org/simplejavamail/api/email/EmailStartingBuilder.java:107-145`
