package org.simplejavamail.api.outlook;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Outlook-specific source data captured while converting a {@code .msg} message.
 * <p>
 * This data is useful for archival and inspection use cases where source headers or MAPI properties
 * should remain visible without copying those headers into {@link org.simplejavamail.api.email.Email#getHeaders()}.
 */
public interface OutlookMessageData extends Serializable {

	/**
	 * @return Raw source headers grouped by header name, preserving the names exposed by the Outlook parser.
	 */
	@NotNull
	Map<String, List<String>> getHeaders();

	/**
	 * Looks up source header values case-insensitively.
	 */
	@NotNull
	List<String> getHeaderValues(@NotNull final String headerName);

	/**
	 * @return Raw source header text as exposed by the Outlook parser.
	 */
	@Nullable
	String getRawHeaders();

	@Nullable
	String getMessageClass();

	@Nullable
	String getDisplayTo();

	@Nullable
	String getDisplayCc();

	@Nullable
	String getDisplayBcc();

	/**
	 * @return A defensive copy of the source date selected by the Outlook reader, or null when unavailable.
	 * This is separate from the client-submit time; the reader can use the stored creation time as its source-date fallback.
	 */
	@Nullable
	Date getDate();

	/** @return A defensive copy of the native client-submit time, or null when absent. */
	@Nullable
	Date getClientSubmitTime();

	/** @return A defensive copy of the stored creation time, or null when absent. */
	@Nullable
	Date getCreationDate();

	/** @return A defensive copy of the stored last-modification time, or null when absent. */
	@Nullable
	Date getLastModificationDate();

	/**
	 * @return Outlook source/store last modifier name (PR_LAST_MODIFIER_NAME / 0x3FFA), or {@code null} if the property is not present.
	 * This is metadata about the stored Outlook item and must not be treated as sender identity.
	 */
	@Nullable
	String getLastModifierName();

	@NotNull
	Set<String> getPropertiesAsHex();

	@NotNull
	Set<Integer> getPropertyCodes();

	@Nullable
	String getPropertyListing();

	/**
	 * Returns native HTML exposed by the Outlook reader, without RTF fallback. This describes the native MSG input,
	 * independently of protected content selection or later Email edits. Empty remains empty; null means unavailable
	 * or uncaptured, including older snapshots. Missing native HTML does not establish an originally plain-text message.
	 *
	 * @return Captured native HTML, or null. Performs no I/O or conversion.
	 */
	@Nullable
	String getBodyHTML();

	/**
	 * Returns reader-decompressed and decoded RTF without further processing. It may contain encapsulated HTML,
	 * encapsulated text or rich text; it is neither the compressed RTF property nor the original MSG bytes.
	 * Like {@link #getBodyHTML()}, this describes native input independently of the final interpreted Email.
	 *
	 * @return Captured reader-provided RTF text, or null when unavailable or uncaptured. Empty remains empty.
	 * Performs no I/O or conversion.
	 */
	@Nullable
	String getBodyRTF();
}
