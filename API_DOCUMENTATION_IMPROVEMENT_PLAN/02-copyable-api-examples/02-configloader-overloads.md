# Replace removed ConfigLoader overloads

- Status: Done
- Priority: High
- Work: Documentation

## Problem

Examples call `loadProperties(File, boolean)` and `loadProperties(File)`, neither of which exists in 9.1.5.

## Plan

Use `Files.newInputStream(Path.of(...))` with an explicit `addProperties` boolean, or build a `Properties` object. Mention that `ConfigLoader` closes a supplied stream.

## Acceptance criteria

- [x] Every ConfigLoader example uses a current public overload.
- [x] Filesystem and classpath examples are clearly distinguished.
- [x] Stream ownership is documented.
- [x] Snippets compile against 9.1.5.

## Evidence

- Documentation: `simplejavamail.org/src/pages/configuration.hbs` now uses the current `String`, `InputStream`, and `Properties` overloads, with `Path.of(...)` for filesystem input.
- API: `modules/core-module/src/main/java/org/simplejavamail/config/ConfigLoader.java:342-386` confirms the published overloads and stream-closing behavior.
- Verification: `npm run check`, `npm run build`, and `npm run verifyLinks:internal` pass; the internal-link check covers 1,280 links across 20 generated pages.
