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

	@Nullable
	Date getDate();

	@Nullable
	Date getClientSubmitTime();

	@Nullable
	Date getCreationDate();

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
}
