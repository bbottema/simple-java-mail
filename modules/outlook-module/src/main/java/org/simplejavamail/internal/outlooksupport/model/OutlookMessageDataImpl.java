package org.simplejavamail.internal.outlooksupport.model;

import lombok.Builder;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.outlook.OutlookMessageData;

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
 */
@ApiStatus.Internal
public final class OutlookMessageDataImpl implements OutlookMessageData {

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

	@Builder
	private OutlookMessageDataImpl(
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
	}

	/** @see OutlookMessageData#getHeaders() */
	@Override
	@NotNull
	public Map<String, List<String>> getHeaders() {
		return headers;
	}

	/** @see OutlookMessageData#getHeaderValues(String) */
	@Override
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

	/** @see OutlookMessageData#getRawHeaders() */
	@Override
	@Nullable
	public String getRawHeaders() {
		return rawHeaders;
	}

	/** @see OutlookMessageData#getMessageClass() */
	@Override
	@Nullable
	public String getMessageClass() {
		return messageClass;
	}

	/** @see OutlookMessageData#getDisplayTo() */
	@Override
	@Nullable
	public String getDisplayTo() {
		return displayTo;
	}

	/** @see OutlookMessageData#getDisplayCc() */
	@Override
	@Nullable
	public String getDisplayCc() {
		return displayCc;
	}

	/** @see OutlookMessageData#getDisplayBcc() */
	@Override
	@Nullable
	public String getDisplayBcc() {
		return displayBcc;
	}

	/** @see OutlookMessageData#getDate() */
	@Override
	@Nullable
	public Date getDate() {
		return copyDate(date);
	}

	/** @see OutlookMessageData#getClientSubmitTime() */
	@Override
	@Nullable
	public Date getClientSubmitTime() {
		return copyDate(clientSubmitTime);
	}

	/** @see OutlookMessageData#getCreationDate() */
	@Override
	@Nullable
	public Date getCreationDate() {
		return copyDate(creationDate);
	}

	/** @see OutlookMessageData#getLastModificationDate() */
	@Override
	@Nullable
	public Date getLastModificationDate() {
		return copyDate(lastModificationDate);
	}

	/** @see OutlookMessageData#getLastModifierName() */
	@Override
	@Nullable
	public String getLastModifierName() {
		return lastModifierName;
	}

	/** @see OutlookMessageData#getPropertiesAsHex() */
	@Override
	@NotNull
	public Set<String> getPropertiesAsHex() {
		return propertiesAsHex;
	}

	/** @see OutlookMessageData#getPropertyCodes() */
	@Override
	@NotNull
	public Set<Integer> getPropertyCodes() {
		return propertyCodes;
	}

	/** @see OutlookMessageData#getPropertyListing() */
	@Override
	@Nullable
	public String getPropertyListing() {
		return propertyListing;
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
