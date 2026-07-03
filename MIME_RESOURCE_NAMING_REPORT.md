# MIME Resource Naming And Content-ID Report

This report scopes the long-running attachment, embedded image, resource name, filename, and Content-ID problem areas in Simple Java Mail. It is intended as the implementation guardrail for future fixes, so the next change can preserve the behavior that is already correct and address only the remaining ambiguity.

## Current Behavioral Contract

### Sending embedded images

Manual embedded images use the embedded resource name as the HTML `cid:` contract:

```java
EmailBuilder.startingBlank()
    .withHTMLText("<img src=\"cid:logo\">")
    .withEmbeddedImage("logo", dataSource);
```

The caller-facing name passed to `withEmbeddedImage(name, dataSource)` is the value that HTML must reference as `cid:name`. It is not the visible filename, and it must not be silently repaired with a datasource extension. The MIME header wraps it as `Content-ID: <name>`.

Current anchors:

- [EmailPopulatingBuilder.java](modules/core-module/src/main/java/org/simplejavamail/api/email/EmailPopulatingBuilder.java) documents that embedded image `name` is the body reference name.
- [EmailPopulatingBuilderImpl.java](modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/EmailPopulatingBuilderImpl.java) requires a non-empty name for byte-array embedded images and requires either an explicit name or a datasource name for datasource-backed embedded images.
- [MimeMessageHelper.java](modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageHelper.java) writes the final `Content-ID` header.

### Sending attachments

Attachments have two different identifiers:

- The visible/download filename.
- The MIME `Content-ID`.

For visible filenames, the current fallback order is:

1. explicit `AttachmentResource.getName()`
2. `DataSource.getName()`
3. generated `resource<UUID>`

For attachment Content-ID values, the current implementation starts with the same base name but appends `@<UUID>` for `Content-Disposition: attachment`. This prevents clients from treating multiple same-name attachments as the same body part. The generated attachment Content-ID is not the identifier that user HTML should reference.

Current anchor:

- [MimeMessageHelper.determineResourceName(...)](modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageHelper.java)

### Dynamic embedded image resolution

Dynamic embedded image resolution is a separate send-side path. If HTML contains an image source that is not already `cid:...`, the builder can resolve it from file, classpath, or URL settings, generate a random CID, add an embedded image under that CID, and rewrite the HTML to `cid:<generated>`.

Current anchor:

- [EmailPopulatingBuilderImpl.buildEmail()](modules/simple-java-mail/src/main/java/org/simplejavamail/email/internal/EmailPopulatingBuilderImpl.java)

### Receiving and parsing

Parsing is deliberately more permissive than sending because real messages from Gmail, Outlook, Apple Mail, and other clients disagree about `Content-Disposition`, `Content-ID`, filenames, and `multipart/related` structure.

Current parse rules:

- A body part with a Content-ID can enter the CID map.
- A body part without inline disposition, or without Content-ID, is also treated as an attachment.
- After parsing, CID-map entries not referenced by `cid:` in HTML are moved to attachments.
- Since `#491`, a part with `Content-Disposition: attachment` and a Content-ID can be both a downloadable attachment and an embedded resource when HTML references that Content-ID.

Current anchors:

- [MimeMessageParser.parseMimePartTree(...)](modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageParser.java)
- [MimeMessageParser.resolveInvalidEmbeddedImagesAsAttachments(...)](modules/simple-java-mail/src/main/java/org/simplejavamail/converter/internal/mimemessage/MimeMessageParser.java)
- [OutlookEmailConverter.java](modules/outlook-module/src/main/java/org/simplejavamail/internal/outlooksupport/converter/OutlookEmailConverter.java)

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
- [RFC 1341](https://www.rfc-editor.org/rfc/rfc1341) and [RFC 1342](https://www.rfc-editor.org/rfc/rfc1342): older MIME/header-encoding references cited in `#293`; they are historical context for MIME body and non-ASCII header handling.

Adjacent RFC references found in related code/docs:

- RFC 2822 / RFC 5322: message-id and email-address format references.
- RFC 2446: calendar method names.
- RFC 5751: S/MIME.
- RFC 8098: disposition notifications.
- RFC 5321: SMTP display-name comment in MIME producer support code.

## Target Invariants For Future Fixes

Any new implementation should preserve these invariants:

1. `withEmbeddedImage(name, dataSource)` means HTML references `cid:name`.
2. `Content-ID` headers are angle-bracket-wrapped on MIME output, but HTML `cid:` values are not.
3. Attachment filenames are display/download names and may duplicate.
4. Attachment Content-IDs must be unique enough for mail clients not to collapse same-name attachments.
5. Filename extension repair must not mutate embedded image CIDs.
6. Explicit API names win over datasource names.
7. Datasource names are fallbacks, not authoritative identity.
8. Parsed model values should be decoded; MIME headers should be encoded at output boundaries.
9. Receiving logic must allow `attachment` plus referenced Content-ID to classify as both attachment and embedded image.
10. Outlook conversion should prefer real ContentId attributes and use names/filenames only as fallbacks.

## Implementation Checklist

Before changing this area, re-check these surfaces together:

- Builder API docs and validation in `EmailPopulatingBuilder` and `EmailPopulatingBuilderImpl`.
- Send-side resource-name derivation in `MimeMessageHelper.determineResourceName(...)`.
- Content-Type parameters, Content-Disposition filename, Content-ID, Content-Description, and Content-Transfer-Encoding output in `MimeMessageHelper`.
- Parse-side Content-ID extraction, filename parsing, header decoding, and `cidMap` versus attachment-list population in `MimeMessageParser`.
- HTML `cid:` extraction and invalid-embedded-resource fallback.
- Outlook conversion through `OutlookEmailConverter` and the current `outlook-message-parser` version behavior.
- Duplicate-name tests and round-trip conversion tests, especially `#307`, `#332`, `#440`, `#480`, and `#491` cases.
