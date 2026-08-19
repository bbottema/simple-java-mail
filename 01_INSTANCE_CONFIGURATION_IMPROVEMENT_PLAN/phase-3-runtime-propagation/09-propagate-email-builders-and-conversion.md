# Step 9: Propagate configuration through email builders and conversion

- Status: Done
- Depends on: Step 8
- Primary module: `simple-java-mail`
- Primary files: `EmailBuilder.java`, `EmailStartingBuilderImpl.java`, `EmailPopulatingBuilderImpl.java`, `EmailPopulatingBuilderFactoryImpl.java`, `EmailConverter.java`
- Optional-module seam: `EmailPopulatingBuilderFactory` and Outlook conversion

## Goal

Make embedded-image and other email-builder defaults come from the factory's snapshot through every blank, copy, reply, forward, MIME conversion, EML conversion, and Outlook conversion path.

## Tests first

1. Create configurations A and B with conflicting embedded-image enable flags, bases, outside-base permissions, and required-resolution behavior. Exercise every email starter through both factories.
2. Cover `copying(Email)` and `copying(EmailPopulatingBuilder)`, which currently instantiate a builder directly.
3. Cover replies and forwards from both `Email` and `MimeMessage`.
4. Cover static `EmailConverter` methods using the `fromDefaults()` snapshot and configured conversion methods using A/B.
5. Cover Outlook top-level and nested-message conversion through `EmailPopulatingBuilderFactory`.
6. Prove URL and classpath outside-base settings no longer cross in Spring-independent configured builders.
7. Change or replace the source config after creating a builder and prove the builder retains its original snapshot-derived values.
8. Assert a conversion path that should intentionally ignore embedded-image auto-resolution uses an explicit empty config rather than an accidental global default.

## Implementation

1. Add config/factory fields to `EmailStartingBuilderImpl` and `EmailPopulatingBuilderFactoryImpl`.
2. Seed `EmailPopulatingBuilderImpl` from the supplied immutable config instead of static getters.
3. Replace direct `new EmailPopulatingBuilderImpl()` calls with the propagated factory.
4. Add internal converter overloads that accept the configured email-builder factory.
5. Expose only the configured conversion surface approved in Step 3. Avoid duplicating every static overload if a configured converter object can cover the same grammar.
6. Pass the same factory through `OutlookModule` and nested Outlook messages.
7. Keep static converter methods on the `fromDefaults()` factory. They do not expose an alternative builder entry.

## Acceptance criteria

- [x] `EmailPopulatingBuilderImpl` contains no static ConfigLoader read.
- [x] Blank, copy, reply, forward, MIME/EML, and Outlook routes retain one config.
- [x] Nested Outlook builders receive the parent conversion config.
- [x] Config A and B produce independent embedded-image behavior concurrently.
- [x] Existing static converter APIs retain conventional behavior through `fromDefaults()`.
- [x] Email builders are obtained from `SimpleJavaMail`, not a new static replacement for `EmailBuilder`.
- [x] The URL/classpath correction has a focused test and migration entry.
- [x] `simple-java-mail` and `outlook-module` focused tests pass.

## Completion evidence

- `ConfiguredEmailConverter` and the configured email-builder factory retain one snapshot through blank, copy, reply, forward, MIME/EML, and nested Outlook routes.
- Existing conversion, Outlook, MIME parser/helper, serialization, and focused A/B embedded-resource tests passed in both full reactors.
- Source scans find no static `ConfigLoader` read or replacement static email-builder entry.
