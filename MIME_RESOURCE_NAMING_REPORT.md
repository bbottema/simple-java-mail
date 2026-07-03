# MIME Resource Naming And Content-ID Report

This report scopes the long-running attachment, embedded image, resource name, filename, and Content-ID problem areas in Simple Java Mail. It is intended as the implementation guardrail for future fixes, so the next change can preserve the behavior that is already correct and address only the remaining ambiguity.

## Current Behavioral Contract

### Sending embedded images

Manual embedded images still support the historical shorthand where the embedded resource name is the HTML `cid:` contract:

```java
EmailBuilder.startingBlank()
    .withHTMLText("<img src=\"cid:logo\">")
    .withEmbeddedImage("logo", dataSource);
```

The caller-facing name passed to `withEmbeddedImage(name, dataSource)` is the value that HTML must reference as `cid:name`. It must not be silently repaired with a datasource extension. The MIME header wraps it as `Content-ID: <name>`.

The corrected API also supports an explicit Content-ID:

```java
EmailBuilder.startingBlank()
    .withHTMLText("<img src=\"cid:logo-2026\">")
    .withEmbeddedImage("logo.png", dataSource, "logo-2026");
```

For this overload, the resource name remains filename/resource metadata and the explicit `contentId` is the HTML reference identity. Surrounding angle brackets are tolerated at the API boundary and stripped before MIME output; CRLF and interior angle brackets are rejected as invalid header content.

Current anchors:

- [EmailPopulatingBuilder.java](modules/core-module/src/main/java/org/simplejavamail/api/email/EmailPopulatingBuilder.java) documents that embedded image `name` is the body reference name.
- [EmailPopulatingBuilderImpl.java](modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/EmailPopulatingBuilderImpl.java) requires a non-empty name for byte-array embedded images and requires either an explicit name, datasource name, or explicit Content-ID for datasource-backed embedded images.
- [AttachmentResource.java](modules/core-module/src/main/java/org/simplejavamail/api/email/AttachmentResource.java) stores optional explicit Content-ID metadata separately from the resource name.
- [MimeMessageHelper.java](modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageHelper.java) writes the final `Content-ID` header.

### Sending attachments

Attachments have two different identifiers:

- The visible/download filename.
- The MIME `Content-ID`.

For visible filenames, the current fallback order is:

1. explicit `AttachmentResource.getName()`
2. `DataSource.getName()`
3. generated `resource<UUID>`

For attachment Content-ID values, explicit `AttachmentResource.getContentId()` wins. If no explicit Content-ID is provided, the send-side fallback is an opaque generated ID of the form `sjm-<UUID>@simplejavamail.generated`. This prevents clients from treating multiple same-name attachments as the same body part without deriving an invalid Content-ID from a user filename. Generated Simple Java Mail attachment IDs are transport-only and are dropped when parsing back into the clean `Email` model.

Current anchor:

- [MimeMessageHelper.java](modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageHelper.java)
- [EmailConverter.userProvidedContentId(...)](modules/simple-java-mail/src/main/java/org/simplejavamail/converter/EmailConverter.java)

### Dynamic embedded image resolution

Dynamic embedded image resolution is a separate send-side path. If HTML contains an image source that is not already `cid:...`, the builder can resolve it from file, classpath, or URL settings, generate a random CID, add an embedded image under that CID, and rewrite the HTML to `cid:<generated>`.

Current anchor:

- [EmailPopulatingBuilderImpl.buildEmail()](modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/EmailPopulatingBuilderImpl.java)

### Receiving and parsing

Parsing is deliberately more permissive than sending because real messages from Gmail, Outlook, Apple Mail, and other clients disagree about `Content-Disposition`, `Content-ID`, filenames, and `multipart/related` structure.

Current parse rules:

- A body part with a Content-ID can enter the CID map.
- A body part without inline disposition, or without Content-ID, is also treated as an attachment.
- Filename/name metadata and Content-ID metadata are parsed separately.
- A real filename wins as the resource name; Content-ID is only a name fallback when the filename is missing or the parser's placeholder attachment name.
- After parsing, CID-map entries not referenced by `cid:` in HTML are moved to attachments.
- Since `#491`, a part with `Content-Disposition: attachment` and a Content-ID can be both a downloadable attachment and an embedded resource when HTML references that Content-ID.
- Explicit/custom Content-ID values survive conversion into `AttachmentResource`; generated `sjm-...@simplejavamail.generated` values do not.

Current anchors:

- [MimeMessageParser.parseMimePartTree(...)](modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageParser.java)
- [MimeMessageParser.resolveInvalidEmbeddedImagesAsAttachments(...)](modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageParser.java)
- [OutlookEmailConverter.java](modules/outlook-module/src/main/java/org/simplejavamail/internal/outlooksupport/converter/OutlookEmailConverter.java)

## Implementation Status - 2026-07-03

The MIME resource naming repair is implemented for the central non-Outlook issues in this problem area:

- [#566](https://github.com/bbottema/simple-java-mail/issues/566): custom attachment Content-ID values now survive send/parse round trips instead of being overwritten by generated fallback IDs.
- [#597](https://github.com/bbottema/simple-java-mail/issues/597): embedded image filename/name and HTML Content-ID can now differ through `withEmbeddedImage(name, dataSource, contentId)`.
- [#602](https://github.com/bbottema/simple-java-mail/issues/602): parsing now keeps embedded filenames and Content-ID values separate; the parsed resource name no longer collapses to the CID when a real filename exists.
- [#607](https://github.com/bbottema/simple-java-mail/issues/607): generated attachment Content-ID values no longer derive from possibly invalid filenames. The generated form is now opaque, ASCII, and domain-qualified: `sjm-<UUID>@simplejavamail.generated`.

Related behavior deliberately preserved:

- `withEmbeddedImage(name, dataSource)` remains the convenience shorthand where `name` is the `cid:` body reference.
- Explicit API names still override datasource names for visible filenames/resource labels.
- Datasource names remain useful fallback metadata.
- Attachment filename fallback still uses explicit name, datasource name, then generated `resource<UUID>`.
- Attachment Content-ID fallback remains unique per MIME part, but is no longer filename-derived.
- Generated Simple Java Mail attachment Content-ID values are transport details and are not treated as caller-provided model data after parsing.

Deferred or out-of-scope issues:

- [#541](https://github.com/bbottema/simple-java-mail/issues/541): optional Content-Type `filename`/`name` parameter control is a separate output-parameter feature request.
- [#573](https://github.com/bbottema/simple-java-mail/issues/573): pre-encoded attachment body support is a separate body-encoding feature.
- [#599](https://github.com/bbottema/simple-java-mail/issues/599): SMTPUTF8 parsing/session behavior is separate from resource name and Content-ID identity.
- [#605](https://github.com/bbottema/simple-java-mail/issues/605): broader per-part Content-Transfer-Encoding fidelity remains adjacent. Parsed attachment CTE metadata is preserved by the current resource model, but text-part CTE policy is not part of this repair.
- [#606](https://github.com/bbottema/simple-java-mail/issues/606) and [#572](https://github.com/bbottema/simple-java-mail/issues/572): Outlook-specific items explicitly excluded from this repair pass.

Verification notes:

- New focused regression coverage was added to `EmailConverterTest` for `#566`, `#597`, `#602`, and `#607`.
- The full `modules/simple-java-mail -am test` suite passes under `C:\Program Files\Java\jdk1.8.0_152` with 303 tests run, 0 failures/errors, and 12 skipped.
- The test dependency `junit-pioneer` is kept at `1.9.1` because `2.3.0` ships Java 11 class files and breaks Java 8 test compilation.

## Problem Areas

### 1. API override name versus datasource name

Root problem: caller-provided names and `DataSource.getName()` both influenced MIME output. Some datasources, especially file and URL datasources, expose source names that are not the desired outgoing name.

Related issues and commits:

- [#47](https://github.com/bbottema/simple-java-mail/issues/47): URL datasource source name overrode requested attachment name.
- [#50](https://github.com/bbottema/simple-java-mail/pull/50), `f54794d9`, `b6bbe6a0`: introduced/finalized `NamedDataSource`.
- [#151](https://github.com/bbottema/simple-java-mail/issues/151), `80d031e4`: do not overwrite a caller-provided filename extension with an invalid datasource extension.
- [#175](https://github.com/bbottema/simple-java-mail/issues/175), `305a2d53`: parsing back attachment names with `<>` wrapping clarified that datasource override names are not always recoverable after MIME conversion.

Implementation rule:

- Treat explicit API names as stronger than datasource names.
- Treat datasource names as fallback metadata only.
- Do not assume a round trip can recover the original datasource name after an explicit override.

### 2. Embedded image CID versus filename

Root problem: embedded images were sometimes treated like attachments, causing file extensions, datasource names, or filename repair to mutate the CID that HTML references.

Related issues and commits:

- Old Issue 5, `d48055d1`: changed Content-ID to RFC-2387-style `<...>` wrapping.
- `9280e589`, `51e72ed5`, `d832cc45`, `dee4a9c0`, `7aada14d`: early 2016 split between Content-ID, name, and filename, including angle-bracket handling.
- [#68](https://github.com/bbottema/simple-java-mail/issues/68): embedded image name required/safeguarded.
- [#307](https://github.com/bbottema/simple-java-mail/issues/307), [#310](https://github.com/bbottema/simple-java-mail/issues/310), `7f9e4089`: stopped stripping extensions from resource names because Outlook embedded image CIDs were being mangled.
- [#332](https://github.com/bbottema/simple-java-mail/issues/332), `0392691a`: test coverage for Apple Mail-style embedded image Content-ID without filename extension.
- [#440](https://github.com/bbottema/simple-java-mail/issues/440), `d561ff9c`: stopped adding datasource-derived extensions to manually named embedded image CIDs.

Implementation rule:

- For inline/embedded resources, the Content-ID must remain the exact embedded image name selected by the API or parser.
- Filename extension repair belongs to attachment display names, not embedded image CIDs.
- HTML should reference the embedded resource name as `cid:<name>` without angle brackets.

### 3. Duplicate attachment names

Root problem: data structures and Content-ID generation previously assumed names were unique. Real mail can contain multiple attachments with the same visible filename.

Related issues and commits:

- [#219](https://github.com/bbottema/simple-java-mail/issues/219), [#249](https://github.com/bbottema/simple-java-mail/issues/249), [#310](https://github.com/bbottema/simple-java-mail/issues/310), [#351](https://github.com/bbottema/simple-java-mail/issues/351): parser rejected or collapsed duplicate names in several forms.
- `72256ce7`: first duplicate-name attempt.
- `9d8dda88`, `8632308e`: proper duplicate-name handling with entry identity beyond the display name.
- [#480](https://github.com/bbottema/simple-java-mail/issues/480), `e943d372`: attachment Content-ID now gets a generated unique suffix so clients do not render same-name attachments as the same content.

Implementation rule:

- Visible attachment filenames do not need to be unique.
- Internal body-part identity must not be keyed only by visible filename.
- Attachment Content-ID uniqueness is a transport/rendering safety measure, not a user-facing body-reference API.

### 4. Encoding and header decoding

Root problem: filenames, Content-ID values, Content-Description, and parsed header values crossed between raw model values, encoded MIME headers, and decoded parser output at different times.

Related issues and commits:

- [#58](https://github.com/bbottema/simple-java-mail/issues/58), `7096c326`: non-English attachment and embedded image names; early RFC-2047 encoding.
- [#131](https://github.com/bbottema/simple-java-mail/issues/131), `7f2eaa12`: `NamedDataSource` implements `EncodingAware`.
- [#226](https://github.com/bbottema/simple-java-mail/pull/226), `21fe8ed9`, `478c7566`: filenames with spaces handled through `ParameterList`.
- [#232](https://github.com/bbottema/simple-java-mail/issues/232), `ccd2ef12`: MIME text encoding moved from email-building to message-sending, keeping the `Email` model clean.
- [#248](https://github.com/bbottema/simple-java-mail/pull/248), `52bd831e`: Content-Type `name` should use the complete filename.
- [#271](https://github.com/bbottema/simple-java-mail/issues/271), `948555a4`: do not encode filenames in the model; scan names for CRLF injection instead.
- [#293](https://github.com/bbottema/simple-java-mail/issues/293), `5368d30f`: decode parsed MIME values and encode attachment descriptions on send.
- [#404](https://github.com/bbottema/simple-java-mail/issues/404), [#405](https://github.com/bbottema/simple-java-mail/issues/405), `c8b32ed8`: support attachment `Content-Description` and explicit attachment `Content-Transfer-Encoding`.
- [#416](https://github.com/bbottema/simple-java-mail/pull/416), [#456](https://github.com/bbottema/simple-java-mail/issues/456): lenient content-transfer-encoding handling for values found in the wild.

Implementation rule:

- Keep model values decoded and human-meaningful.
- Encode only at MIME output boundaries.
- Decode at MIME parse boundaries before validation or model population.
- Keep CRLF/header-injection checks after decode, not as a substitute for decode.

### 5. Inline-versus-attachment classification on receive

Root problem: `Content-Disposition` alone is not a reliable signal for whether a body part is an embedded resource, an attachment, or both.

Related issues and commits:

- [#34](https://github.com/bbottema/simple-java-mail/issues/34): missing disposition originally treated as attachment.
- [#83](https://github.com/bbottema/simple-java-mail/issues/83), `a1fc1b9f`: inline attachments without Content-ID parsed as regular attachments.
- [#103](https://github.com/bbottema/simple-java-mail/issues/103), `5727f431`: removed invalid `size=0` Content-Disposition hack.
- [#179](https://github.com/bbottema/simple-java-mail/issues/179), `c79dd605`: inline/CID resources not referenced in HTML are treated as attachments.
- [#202](https://github.com/bbottema/simple-java-mail/issues/202), `e3d90694`: fixed concurrent modification while moving invalid embedded images to attachments.
- [#346](https://github.com/bbottema/simple-java-mail/issues/346), `d417203a`: parse MimeMessage without fetching attachment data; still return named datasources.
- [#491](https://github.com/bbottema/simple-java-mail/issues/491), `5b704fac`: attachment-disposition parts with referenced Content-ID can be both attachment and embedded image.

Implementation rule:

- Parse `Content-Disposition` and `Content-ID` independently.
- Use HTML `cid:` references as evidence that a Content-ID part is embedded.
- Allow dual classification when the MIME source says attachment but the HTML references the Content-ID.

### 6. Outlook-specific CID and fallback behavior

Root problem: Outlook `.msg` files carry attachment names, long filenames, short DOS-like filenames, and ContentId attributes differently from MIME `.eml`.

Related issues and commits:

- [#200](https://github.com/bbottema/simple-java-mail/issues/200), `885f0ecf`: Outlook attachment name falls back to filename if proper name is empty.
- [simple-java-mail #307](https://github.com/bbottema/simple-java-mail/issues/307): Outlook MSG to EML failed because embedded image Content-ID was changed.
- [outlook-message-parser #10](https://github.com/bbottema/outlook-message-parser/issues/10): DOS-like short names misclassified embedded images as attachments; long filename needed as fallback.
- [outlook-message-parser #19](https://github.com/bbottema/outlook-message-parser/pull/19): use Outlook's real ContentId attribute for CID attachments.
- [outlook-message-parser #69](https://github.com/bbottema/outlook-message-parser/issues/69), [simple-java-mail #481](https://github.com/bbottema/simple-java-mail/issues/481): empty invalid embedded images from Outlook should not crash conversion unless they are actually used.
- Adjacent parser issues: [outlook-message-parser #3](https://github.com/bbottema/outlook-message-parser/issues/3), [#9](https://github.com/bbottema/outlook-message-parser/issues/9), [#17](https://github.com/bbottema/outlook-message-parser/issues/17), [#23](https://github.com/bbottema/outlook-message-parser/issues/23), [#26](https://github.com/bbottema/outlook-message-parser/issues/26), [#27](https://github.com/bbottema/outlook-message-parser/issues/27).

Implementation rule:

- Prefer a real Outlook ContentId when present.
- Keep filename and long-filename fallback logic as classification support only; do not let it mutate a known CID.
- Invalid or empty Outlook attachments need context: ignore/tolerate them only when they are not used as embedded images.

## RFCs Referenced In This Problem Area

Central RFC references:

- [RFC 2387](https://www.ietf.org/rfc/rfc2387.txt), MIME `multipart/related`: related parts form an aggregate object; related processing can take precedence over `Content-Disposition`; examples use body-part `Content-ID` references.
- [RFC 2183](https://www.ietf.org/rfc/rfc2183.txt), `Content-Disposition`: defines `inline`, `attachment`, and `filename`, but does not make disposition sufficient to classify every related body part in real messages.
- [RFC 2047](https://www.rfc-editor.org/rfc/rfc2047), non-ASCII text in message headers: referenced by code and by the non-English name fixes.
- [RFC 5322](https://www.rfc-editor.org/rfc/rfc5322), Internet Message Format: current standard for `msg-id` syntax behind `Content-ID`, replacing the older RFC 2822 reference.
- [RFC 1341](https://www.rfc-editor.org/rfc/rfc1341) and [RFC 1342](https://www.rfc-editor.org/rfc/rfc1342): older MIME/header-encoding references cited in `#293`; they are historical context for MIME body and non-ASCII header handling.

Adjacent RFC references found in related code/docs:

- RFC 2822: older message-id and email-address format reference superseded by RFC 5322.
- RFC 2446: calendar method names.
- RFC 5751: S/MIME.
- RFC 8098: disposition notifications.
- RFC 5321: SMTP display-name comment in MIME producer support code.

## Target Invariants For Future Fixes

Any new implementation should preserve these invariants:

1. `withEmbeddedImage(name, dataSource)` means HTML references `cid:name`.
2. `withEmbeddedImage(name, dataSource, contentId)` means HTML references `cid:contentId`, while `name` remains filename/resource metadata.
3. `Content-ID` headers are angle-bracket-wrapped on MIME output, but HTML `cid:` values are not.
4. Attachment filenames are display/download names and may duplicate.
5. Attachment Content-IDs must be unique enough for mail clients not to collapse same-name attachments.
6. Generated attachment Content-IDs must be valid opaque IDs, not filename-derived values.
7. Filename extension repair must not mutate embedded image CIDs.
8. Explicit API names win over datasource names for filenames/resource labels.
9. Explicit API Content-IDs win over all generated fallback IDs.
10. Datasource names are fallbacks, not authoritative identity.
11. Parsed model values should be decoded; MIME headers should be encoded at output boundaries.
12. Receiving logic must allow `attachment` plus referenced Content-ID to classify as both attachment and embedded image.
13. Outlook conversion should prefer real ContentId attributes and use names/filenames only as fallbacks.

## Implementation Checklist

Before changing this area, re-check these surfaces together:

- Builder API docs and validation in `EmailPopulatingBuilder` and `EmailPopulatingBuilderImpl`.
- Send-side filename/resource-label derivation and Content-ID derivation in `MimeMessageHelper`.
- Content-Type parameters, Content-Disposition filename, Content-ID, Content-Description, and Content-Transfer-Encoding output in `MimeMessageHelper`.
- Parse-side Content-ID extraction, filename parsing, header decoding, and `cidMap` versus attachment-list population in `MimeMessageParser`.
- HTML `cid:` extraction and invalid-embedded-resource fallback.
- Outlook conversion through `OutlookEmailConverter` and the current `outlook-message-parser` version behavior.
- Duplicate-name tests and round-trip conversion tests, especially `#307`, `#332`, `#440`, `#480`, and `#491` cases.
