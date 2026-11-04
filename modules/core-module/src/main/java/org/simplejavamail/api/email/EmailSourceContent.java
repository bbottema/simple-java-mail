package org.simplejavamail.api.email;

import org.jetbrains.annotations.NotNull;

import java.io.InputStream;
import java.io.Serializable;

/**
 * Immutable captured evidence, not a live attachment or source-file callback. Reads are repeatable and perform no external I/O,
 * decoding, retries or crypto. Unavailable/uncaptured content is represented by a null content reference, not an empty capture.
 *
 * @since 10.0.0
 */
public interface EmailSourceContent extends Serializable {

	/** Whether these bytes preserve source encoding or are already transformed bytes supplied by a reader. */
	enum Representation { SOURCE_BYTES, READER_BYTES }

	/** Origin of the captured byte representation. */
	@NotNull Representation getRepresentation();

	/** False when an actual acquisition failure left only a prefix; a complete empty payload is true with size zero. */
	boolean isFullyCaptured();

	/** Number of retained bytes, not an estimated or declared full size. */
	long getSize();

	/** Opens a new stream over the retained bytes only; the caller closes it. A partial capture yields only its retained prefix. */
	@NotNull InputStream openStream();

	/** Returns a defensive copy of all retained bytes; a partial capture yields only its retained prefix. */
	@NotNull byte[] readAllBytes();
}
