# Step 2: Lock source precedence, parsing, wildcard, and failure behavior

- Status: Done
- Depends on: Step 1
- Primary modules: `core-module`, `simple-java-mail`
- Primary tests: `ConfigLoaderTest.java` plus new source-contract tests

## Goal

Describe the existing source resolver as data, then decide which behaviors are compatibility contracts and which are defects. This becomes the test suite for the instance loader rather than a line-for-line port of `readProperties(...)`.

## Tests first

Build a table-driven suite covering:

1. File or supplied `Properties`, environment, and system collisions for every supported value family: string, integer, boolean, enum, wildcard extra property, and wildcard cluster field.
2. A missing classpath resource with environment-only and system-only values.
3. Null, empty, and whitespace-only values at each priority level. A blank high-priority value must not hide a useful lower-priority value.
4. Already typed values in `Properties`, including valid and invalid types for the target property.
5. Values whose text resembles another type, such as numeric passwords, `SMTP` as a subject, `true` as a sender name, `0` as a port, and enum-like extra Session values.
6. Unknown keys in strict supplied sources versus unrelated keys in process-wide sources.
7. Wildcard cluster aliases, direct UUID aliases, partial entries, global fallbacks, duplicate cluster keys, invalid UUIDs, invalid integers, and invalid load-balancing values.
8. Scalar environment lookup through the exact uppercase, dot-to-underscore key mapping, plus wildcard environment behavior for literal dotted keys and the currently unsupported uppercase-underscore wildcard form.
9. `addProperties=true` for ordinary values, wildcard maps, removal/absence, and a later system/environment pass.
10. Caller-provided stream ownership, malformed streams, and failures while closing.
11. Mutation of the source `Properties` and of maps returned by the current API after a load.
12. Concurrent reads and writes to expose the current unsynchronized `Properties` overload without making that race a compatibility requirement.

Use subprocesses or narrow system-property guards. Do not call `System.getProperties().clear()`. Environment tests should be replaceable with injected maps once `ConfigSource` exists.

## Preserve

- Conventional `system > environment > file` precedence.
- Builder calls winning over configuration defaults.
- Missing files not disabling runtime sources.
- Blank values acting as absent.
- Typed `Properties` input.
- Strict validation of supplied Simple Java Mail property sources.
- Existing wildcard names, cluster fallback rules, and the precedence fixed in #685.

## Correct deliberately

- Parse by property descriptor instead of guessing from text.
- Return detached, deeply immutable snapshot data instead of a live unmodifiable view.
- Make concurrent snapshots safe.
- Never print secret values in diagnostics.
- Fix the crossed embedded-image URL/classpath descriptor mapping while preserving the intended literal-key behavior.

## Acceptance criteria

- [x] The matrix names the source, raw value, winning value, resolved type, and expected failure where relevant.
- [x] Ordinary and wildcard resolution use the same precedence model.
- [x] Invalid lower-priority values do not fail when a valid higher-priority value wins, matching current effective behavior.
- [x] Every intentional correction is entered in Step 3's migration matrix.
- [x] Tests distinguish configuration-key compatibility from direct static-map compatibility.
- [x] The baseline suite passes on Java 8.

## Completion evidence

- `ConfigLoaderTest` and `ConfigLoaderInstanceTest` cover later-source-wins precedence, blanks, typed values, wildcard Session and cluster keys, unknown-key policy, stream closing, and parse-only-the-winner behavior.
- The complete Java 8 reactor passed with the same matrix in place.
