# Step 4: Introduce the property-specific typed schema

- Status: Done
- Depends on: Step 3
- Primary module: `core-module`
- Primary files: `ConfigLoader.java`, new internal schema/descriptors, focused schema tests

## Goal

Replace `parsePropertyValue(String)` type guessing with one descriptor per known property. Keep the existing public property identifiers and literal keys while making internal resolution predictable and type-safe.

## Tests first

1. Add one parameterized test for every `ConfigLoader.Property` identifier and its expected resolved type.
2. Assert that numeric, boolean-like, and enum-like text remains text for string properties.
3. Assert accepted boolean spellings, integer boundaries, enum names, DSN values, content-transfer encodings, debug output, canonicalization, and load-balancing strategies.
4. Assert property-specific error messages for invalid values without echoing secret input.
5. Assert already typed object values from `Properties` and reject a value of the wrong type.
6. Assert the secret classification used by diagnostics.
7. Assert wildcard extra properties remain strings and cluster fields use their own parsers.
8. Assert `outside.base.url` and `outside.base.classpath` map to the correctly named descriptors.

## Implementation

1. Add an internal descriptor registry keyed by `ConfigLoader.Property`.
2. Give each descriptor its canonical key, resolved Java type, raw-value converter, blank policy, secret flag, and optional aliases.
3. Treat wildcard Session and cluster namespaces as typed namespace descriptors rather than fake scalar keys.
4. Parse only the final raw winner. A bad lower-priority value that is fully overridden must not fail.
5. Keep `ConfigLoader.Property.key()` public. Correct the two crossed embedded-image key mappings as approved in Step 3.
6. Remove the generic enum-guessing parser after all callers use descriptors.

The schema is internal infrastructure, not a second public builder API. Java callers continue to configure Mailers and Emails through their fluent builders or create snapshots from property sources.

## Secret classification

At minimum classify SMTP password, proxy password, S/MIME store password, S/MIME key password, and DKIM private-key data as secret. Diagnostics may name the property and winning source but never the raw or parsed value.

## Acceptance criteria

- [x] Every scalar and wildcard property is represented exactly once.
- [x] A test fails when a new `Property` identifier has no descriptor.
- [x] Parsing is based on the target property, not on the shape of the text.
- [x] Error messages identify source and key but redact secret values.
- [x] Existing effective integer, boolean, and enum consumers receive the same values.
- [x] Direct file keys for embedded-image URL and classpath behavior remain intuitive and Spring can map them without a swap.
- [x] `core-module` tests pass on Java 8.

## Completion evidence

- Internal `PropertySchema` declares the type, blank policy, aliases, wildcard namespace, and secret classification for the complete property catalogue.
- Descriptor completeness, typed-object validation, property-specific parsing, redaction, and corrected embedded URL/classpath tests pass on Java 8 and Java 21.
