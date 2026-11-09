package org.simplejavamail.api.outlook;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * Outlook-specific source data captured while converting a {@code .msg} message.
 * <p>
 * This data is useful for archival and inspection use cases where source headers or MAPI properties
 * should remain visible without copying those headers into {@link org.simplejavamail.api.email.Email#getHeaders()}.
 * Native body values describe the MSG input, independently of content selected by S/MIME processing
 * or later changes to the converted email builder.
 */
public class OutlookMessageData implements Serializable {

	private static final long serialVersionUID = 1L;

	private final Map<String, List<String>> headers;
	private final String rawHeaders;
	private final String messageClass;
	private final String displayTo;
	private final String displayCc;
	private final String displayBcc;
	private final Date date;
	private final Date clientSubmitTime;
	private final Date creationDate;
	private final Date lastModificationDate;
	private final String lastModifierName;
	private final Set<String> propertiesAsHex;
	private final Set<Integer> propertyCodes;
	private final String propertyListing;
	private final String bodyHTML;
	private final String bodyRTF;

	@SuppressWarnings("ConstructorWithTooManyParameters")
	public OutlookMessageData(
			@Nullable final Map<String, ? extends Collection<String>> headers,
			@Nullable final String rawHeaders,
			@Nullable final String messageClass,
			@Nullable final String displayTo,
			@Nullable final String displayCc,
			@Nullable final String displayBcc,
			@Nullable final Date date,
			@Nullable final Date clientSubmitTime,
			@Nullable final Date creationDate,
			@Nullable final Date lastModificationDate,
			@Nullable final Set<String> propertiesAsHex,
			@Nullable final Set<Integer> propertyCodes,
			@Nullable final String propertyListing) {
		this(headers, rawHeaders, messageClass, displayTo, displayCc, displayBcc, date, clientSubmitTime, creationDate, lastModificationDate,
				null, propertiesAsHex, propertyCodes, propertyListing);
	}

	@SuppressWarnings("ConstructorWithTooManyParameters")
	public OutlookMessageData(
			@Nullable final Map<String, ? extends Collection<String>> headers,
			@Nullable final String rawHeaders,
			@Nullable final String messageClass,
			@Nullable final String displayTo,
			@Nullable final String displayCc,
			@Nullable final String displayBcc,
			@Nullable final Date date,
			@Nullable final Date clientSubmitTime,
			@Nullable final Date creationDate,
			@Nullable final Date lastModificationDate,
			@Nullable final String lastModifierName,
			@Nullable final Set<String> propertiesAsHex,
			@Nullable final Set<Integer> propertyCodes,
			@Nullable final String propertyListing) {
		this(headers, rawHeaders, messageClass, displayTo, displayCc, displayBcc, date, clientSubmitTime, creationDate, lastModificationDate,
				lastModifierName, propertiesAsHex, propertyCodes, propertyListing, null, null);
	}

	/**
	 * Creates a detached snapshot of Outlook source data. Body values are stored without processing.
	 *
	 * @param headers Source headers grouped by name.
	 * @param rawHeaders Source header text.
	 * @param messageClass Outlook message class.
	 * @param displayTo Outlook display text for TO recipients.
	 * @param displayCc Outlook display text for CC recipients.
	 * @param displayBcc Outlook display text for BCC recipients.
	 * @param date Source message date.
	 * @param clientSubmitTime Source client submission time.
	 * @param creationDate Source item creation time.
	 * @param lastModificationDate Source item modification time.
	 * @param lastModifierName Source/store last modifier name.
	 * @param propertiesAsHex Source property identifiers in hexadecimal form.
	 * @param propertyCodes Source property identifiers.
	 * @param propertyListing Source property listing.
	 * @param bodyHTML Native HTML exposed by the Outlook reader, without RTF fallback; null when unavailable or not captured.
	 * @param bodyRTF Reader-decompressed and decoded RTF text; null when unavailable or not captured.
	 */
	@SuppressWarnings("ConstructorWithTooManyParameters")
	public OutlookMessageData(
			@Nullable final Map<String, ? extends Collection<String>> headers,
			@Nullable final String rawHeaders,
			@Nullable final String messageClass,
			@Nullable final String displayTo,
			@Nullable final String displayCc,
			@Nullable final String displayBcc,
			@Nullable final Date date,
			@Nullable final Date clientSubmitTime,
			@Nullable final Date creationDate,
			@Nullable final Date lastModificationDate,
			@Nullable final String lastModifierName,
			@Nullable final Set<String> propertiesAsHex,
			@Nullable final Set<Integer> propertyCodes,
			@Nullable final String propertyListing,
			@Nullable final String bodyHTML,
			@Nullable final String bodyRTF) {
		this.headers = copyHeaders(headers);
		this.rawHeaders = rawHeaders;
		this.messageClass = messageClass;
		this.displayTo = displayTo;
		this.displayCc = displayCc;
		this.displayBcc = displayBcc;
		this.date = copyDate(date);
		this.clientSubmitTime = copyDate(clientSubmitTime);
		this.creationDate = copyDate(creationDate);
		this.lastModificationDate = copyDate(lastModificationDate);
		this.lastModifierName = lastModifierName;
		this.propertiesAsHex = copySet(propertiesAsHex);
		this.propertyCodes = copySet(propertyCodes);
		this.propertyListing = propertyListing;
		this.bodyHTML = bodyHTML;
		this.bodyRTF = bodyRTF;
	}

	/**
	 * @return Raw source headers grouped by header name, preserving the names exposed by the Outlook parser.
	 */
	@NotNull
	public Map<String, List<String>> getHeaders() {
		return headers;
	}

	/**
	 * Looks up source header values case-insensitively.
	 */
	@NotNull
	public List<String> getHeaderValues(@NotNull final String headerName) {
		final String normalizedHeaderName = checkNonEmptyArgument(headerName, "headerName").toLowerCase(Locale.ROOT);
		final List<String> values = new ArrayList<>();
		for (Map.Entry<String, List<String>> header : headers.entrySet()) {
			if (header.getKey() != null && header.getKey().toLowerCase(Locale.ROOT).equals(normalizedHeaderName)) {
				values.addAll(header.getValue());
			}
		}
		return Collections.unmodifiableList(values);
	}

	/**
	 * @return Raw source header text as exposed by the Outlook parser.
	 */
	@Nullable
	public String getRawHeaders() {
		return rawHeaders;
	}

	@Nullable
	public String getMessageClass() {
		return messageClass;
	}

	@Nullable
	public String getDisplayTo() {
		return displayTo;
	}

	@Nullable
	public String getDisplayCc() {
		return displayCc;
	}

	@Nullable
	public String getDisplayBcc() {
		return displayBcc;
	}

	@Nullable
	public Date getDate() {
		return copyDate(date);
	}

	@Nullable
	public Date getClientSubmitTime() {
		return copyDate(clientSubmitTime);
	}

	@Nullable
	public Date getCreationDate() {
		return copyDate(creationDate);
	}

	@Nullable
	public Date getLastModificationDate() {
		return copyDate(lastModificationDate);
	}

	/**
	 * @return Outlook source/store last modifier name (PR_LAST_MODIFIER_NAME / 0x3FFA), or {@code null} if the property is not present.
	 * This is metadata about the stored Outlook item and must not be treated as sender identity.
	 */
	@Nullable
	public String getLastModifierName() {
		return lastModifierName;
	}

	@NotNull
	public Set<String> getPropertiesAsHex() {
		return propertiesAsHex;
	}

	@NotNull
	public Set<Integer> getPropertyCodes() {
		return propertyCodes;
	}

	@Nullable
	public String getPropertyListing() {
		return propertyListing;
	}

	/**
	 * Returns the native HTML body exposed by the Outlook reader, without its RTF-to-HTML fallback.
	 * This is source data, not necessarily the HTML returned by the converted Email after S/MIME processing or builder changes.
	 * An empty body remains empty; null means unavailable or not captured, including legacy constructors and serialized snapshots.
	 * Absence of native HTML does not establish that the message was originally plain text.
	 *
	 * @return The captured native HTML, or null when unavailable or not captured. This getter performs no I/O or conversion.
	 */
	@Nullable
	public String getBodyHTML() {
		return bodyHTML;
	}

	/**
	 * Returns the source RTF body text after the Outlook reader's decompression and decoding, without further processing.
	 * This is not the compressed RTF property or the original MSG bytes. RTF may contain plain text, encapsulated HTML or rich text.
	 * An empty body remains empty; null means unavailable or not captured, including legacy constructors and serialized snapshots.
	 * Like {@link #getBodyHTML()}, this describes the native MSG, independently of the final converted Email.
	 *
	 * @return The captured RTF text, or null when unavailable or not captured. This getter performs no I/O or conversion.
	 */
	@Nullable
	public String getBodyRTF() {
		return bodyRTF;
	}

	@NotNull
	private static Map<String, List<String>> copyHeaders(@Nullable final Map<String, ? extends Collection<String>> headers) {
		if (headers == null) {
			return Collections.emptyMap();
		}
		final Map<String, List<String>> copiedHeaders = new LinkedHashMap<>();
		for (Map.Entry<String, ? extends Collection<String>> header : headers.entrySet()) {
			copiedHeaders.put(header.getKey(), copyCollection(header.getValue()));
		}
		return Collections.unmodifiableMap(copiedHeaders);
	}

	@NotNull
	private static <T> List<T> copyCollection(@Nullable final Collection<T> values) {
		if (values == null) {
			return Collections.emptyList();
		}
		return Collections.unmodifiableList(new ArrayList<>(values));
	}

	@NotNull
	private static <T> Set<T> copySet(@Nullable final Set<T> values) {
		if (values == null) {
			return Collections.emptySet();
		}
		return Collections.unmodifiableSet(new LinkedHashSet<>(values));
	}

	@Nullable
	private static Date copyDate(@Nullable final Date date) {
		return date != null ? new Date(date.getTime()) : null;
	}
}
