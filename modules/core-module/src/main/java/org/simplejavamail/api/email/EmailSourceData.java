package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.io.Serializable;
import java.util.List;

/**
 * Immutable, enumerable source evidence, independent of outgoing composition and processing problems.
 * Part metadata includes successful content too. Complete raw EML/MSG input acquired during conversion is retained automatically
 * under the selected import limits and exposed through {@link EmailInspection#getOriginalMessage()} in its captured format.
 * Original attachment payloads remain available through {@link EmailInspection#getOriginalAttachment(int)} where retained.
 * The diagnostic tree shares captured storage where possible; it need not duplicate that original archive in its part metadata.
 * Additional selected evidence can retain original RTF alongside converted HTML without a processing problem, while equivalent
 * representations such as successful Base64 decoding need no duplicate payload. Generated output and acquired prefixes are not
 * complete original files. Reading this view does not reopen resources, decode content or run crypto.
 *
 * @since 10.0.0
 */
public interface EmailSourceData extends Serializable {
	/** Format actually exposed by the source reader, not a generated MIME structure attributed to an MSG input. */
	enum Format { MIME, OUTLOOK_MSG }

	/** Relationship of this view to the input being inspected. */
	enum Representation { RECEIVED, EXTRACTED, GENERATED }

	/** Input identity, or null for an empty view with no captured source. */
	@Nullable EmailSourceIdentity getOrigin();

	/** Captured source format, or null for an empty view. */
	@Nullable Format getFormat();

	/** Captured view relationship, or null for an empty view. */
	@Nullable Representation getRepresentation();

	/** Available root-header occurrences in source order, with original spelling and raw values. */
	@NotNull List<EmailSourceHeader> getHeaders();

	/** Reader-exposed native properties in their available order; never null. */
	@NotNull List<EmailSourceProperty> getProperties();

	/** Immediate source content components; containers expose their own children. Never null. */
	@NotNull List<EmailSourcePart> getParts();

	/** Extracted/generated views actually materialized by processing; enumeration starts no further processing. Never null. */
	@NotNull List<EmailSourceData> getDerivedSources();
}
