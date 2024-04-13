package org.simplejavamail.api.internal.general;

import java.util.Arrays;
import java.util.List;

import static java.util.Collections.singletonList;

public class HeadersToIgnoreWhenParsingExternalEmails {

	@SuppressWarnings("BooleanMethodIsAlwaysInverted")
	public static boolean shouldIgnoreHeader(final String headerName) {
		return HEADERS_TO_IGNORE_CASE_INSENSITIVE.stream().map(MessageHeader::getName).anyMatch(headerName::equalsIgnoreCase) ||
				HEADERS_TO_IGNORE_CASE_SENSITIVE.stream().map(MessageHeader::getName).anyMatch(headerName::equals);
	}

	/**
	 * Contains the headers we will ignore, because either we set the information differently (such as Subject) or we
	 * recognize the header as interfering or obsolete for new emails.
	 */
	private static final List<MessageHeader> HEADERS_TO_IGNORE_CASE_INSENSITIVE = Arrays.asList(
            MessageHeader.RECEIVED,
            MessageHeader.RESENT_DATE,
            MessageHeader.RESENT_FROM,
            MessageHeader.RESENT_SENDER,
            MessageHeader.RESENT_TO,
            MessageHeader.RESENT_CC,
            MessageHeader.RESENT_BCC,
            MessageHeader.DATE,
            MessageHeader.FROM,
            MessageHeader.SENDER,
            MessageHeader.TO,
            MessageHeader.CC,
            MessageHeader.BCC,
            MessageHeader.SUBJECT,
            MessageHeader.CONTENT_MD5,
            MessageHeader.CONTENT_LENGTH,
            MessageHeader.COLON,
            MessageHeader.STATUS,
            MessageHeader.CONTENT_DISPOSITION,
            MessageHeader.SIZE,
            MessageHeader.FILENAME,
            MessageHeader.CONTENT_ID,
            MessageHeader.NAME,
            MessageHeader.RESENT_MESSAGE_ID,
            MessageHeader.COMMENTS,
            MessageHeader.KEYWORDS,
            MessageHeader.ERRORS_TO,
            MessageHeader.MIME_VERSION,
            MessageHeader.CONTENT_TYPE,
            MessageHeader.CONTENT_TRANSFER_ENCODING,
            MessageHeader.RESENT_MESSAGE_ID,
            MessageHeader.REPLY_TO
    );

	/**
	 * Similar to {@link #HEADERS_TO_IGNORE_CASE_INSENSITIVE}, but case-sensitive. Why? Well, that's a little
	 * complicated. These headers cause issues due to legacy code that is not case-insensitive. So we need to keep
	 * track of these headers separately to avoid issues.
	 * <p>
	 * In practice, this should not cause any issues for real-world usage / use cases.
	 */
	private static final List<MessageHeader> HEADERS_TO_IGNORE_CASE_SENSITIVE = singletonList(
            MessageHeader.MESSAGE_ID
    );
}
