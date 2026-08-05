# Replace removed ConfigLoader overloads

- Status: Planned
- Priority: High
- Work: Documentation

## Problem

Examples call `loadProperties(File, boolean)` and `loadProperties(File)`, neither of which exists in 9.1.5.

## Plan

Use `Files.newInputStream(Path.of(...))` with an explicit `addProperties` boolean, or build a `Properties` object. Mention that `ConfigLoader` closes a supplied stream.

## Acceptance criteria

- [ ] Every ConfigLoader example uses a current public overload.
- [ ] Filesystem and classpath examples are clearly distinguished.
- [ ] Stream ownership is documented.
- [ ] Snippets compile against 9.1.5.

## Evidence

- Documentation: `simplejavamail.org/src/pages/configuration.hbs:299-306,591-612`
- API: `modules/core-module/src/main/java/org/simplejavamail/config/ConfigLoader.java:342-386`
