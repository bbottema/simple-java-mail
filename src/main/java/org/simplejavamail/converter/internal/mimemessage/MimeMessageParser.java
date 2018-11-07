package org.simplejavamail.converter.internal.mimemessage;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.Address;
import javax.mail.Header;
import javax.mail.Message.RecipientType;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.internet.AddressException;
import javax.mail.internet.ContentType;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimePart;
import javax.mail.internet.MimeUtility;
import javax.mail.internet.ParseException;
import javax.mail.util.ByteArrayDataSource;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static java.lang.String.format;
import static org.simplejavamail.internal.util.MiscUtil.extractCID;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

/**
 * Parses a MimeMessage and stores the individual parts such a plain text, HTML text and attachments.
 *
 * @version current: MimeMessageParser.java 2016-02-25 Benny Bottema
 */
public final class MimeMessageParser {

	/**
	 * Contains the headers we will ignore, because either we set the information differently (such as Subject) or we recognize the header as
	 * interfering or obsolete for new emails).
	 */
	private static final List<String> HEADERS_TO_IGNORE = new ArrayList<>();

	static {
		// taken from: protected javax.mail.internet.InternetHeaders constructor
		/*
		 * When extracting information to create an Email, we're NOT interested in the following headers:
         */
		// HEADERS_TO_IGNORE.add("Return-Path"); // bounceTo address
		HEADERS_TO_IGNORE.add("Received");
		HEADERS_TO_IGNORE.add("Resent-Date");
		HEADERS_TO_IGNORE.add("Resent-From");
		HEADERS_TO_IGNORE.add("Resent-Sender");
		HEADERS_TO_IGNORE.add("Resent-To");
		HEADERS_TO_IGNORE.add("Resent-Cc");
		HEADERS_TO_IGNORE.add("Resent-Bcc");
		HEADERS_TO_IGNORE.add("Resent-Message-Id");
		HEADERS_TO_IGNORE.add("Date");
		HEADERS_TO_IGNORE.add("From");
		HEADERS_TO_IGNORE.add("Sender");
		HEADERS_TO_IGNORE.add("Reply-To");
		HEADERS_TO_IGNORE.add("To");
		HEADERS_TO_IGNORE.add("Cc");
		HEADERS_TO_IGNORE.add("Bcc");
		HEADERS_TO_IGNORE.add("Message-Id");
		// The next two are needed for replying to
		// HEADERS_TO_IGNORE.add("In-Reply-To");
		// HEADERS_TO_IGNORE.add("References");
		HEADERS_TO_IGNORE.add("Subject");
		HEADERS_TO_IGNORE.add("Comments");
		HEADERS_TO_IGNORE.add("Keywords");
		HEADERS_TO_IGNORE.add("Errors-To");
		HEADERS_TO_IGNORE.add("MIME-Version");
		HEADERS_TO_IGNORE.add("Content-Type");
		HEADERS_TO_IGNORE.add("Content-Transfer-Encoding");
		HEADERS_TO_IGNORE.add("Content-MD5");
		HEADERS_TO_IGNORE.add(":");
		HEADERS_TO_IGNORE.add("Content-Length");
		HEADERS_TO_IGNORE.add("Status");
		// extra headers that should be ignored, which may originate from nested attachments
		HEADERS_TO_IGNORE.add("Content-Disposition");
		HEADERS_TO_IGNORE.add("size");
		HEADERS_TO_IGNORE.add("filename");
		HEADERS_TO_IGNORE.add("Content-ID");
		HEADERS_TO_IGNORE.add("name");
		HEADERS_TO_IGNORE.add("From");
	}

	/**
	 * Extracts the content of a MimeMessage recursively.
	 */
	public static ParsedMimeMessageComponents parseMimeMessage(@Nonnull final MimeMessage mimeMessage) {
		final ParsedMimeMessageComponents parsedComponents = new ParsedMimeMessageComponents();
		parsedComponents.messageId = parseMessageId(mimeMessage);
		parsedComponents.subject = parseSubject(mimeMessage);
		parsedComponents.toAddresses.addAll(parseToAddresses(mimeMessage));
		parsedComponents.ccAddresses.addAll(parseCcAddresses(mimeMessage));
		parsedComponents.bccAddresses.addAll(parseBccAddresses(mimeMessage));
		parsedComponents.fromAddress = parseFromAddress(mimeMessage);
		parsedComponents.replyToAddresses = parseReplyToAddresses(mimeMessage);
		parseMimePartTree(mimeMessage, parsedComponents);
		moveInvalidEmbeddedResourcesToAttachments(parsedComponents);
		return parsedComponents;
	}

	private static void parseMimePartTree(@Nonnull final MimePart currentPart, @Nonnull final ParsedMimeMessageComponents parsedComponents) {
		for (final Header header : retrieveAllHeaders(currentPart)) {
			parseHeader(header, parsedComponents);
		}

		final String disposition = parseDisposition(currentPart);

		if (isMimeType(currentPart, "text/plain") && parsedComponents.plainContent == null && !Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
			parsedComponents.plainContent = parseContent(currentPart);
		} else if (isMimeType(currentPart, "text/html") && parsedComponents.htmlContent == null && !Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
			parsedComponents.htmlContent = parseContent(currentPart);
		} else if (isMimeType(currentPart, "multipart/*")) {
			final Multipart mp = parseContent(currentPart);
			for (int i = 0, count = countBodyParts(mp); i < count; i++) {
				parseMimePartTree(getBodyPartAtIndex(mp, i), parsedComponents);
			}
		} else {
			final DataSource ds = createDataSource(currentPart);
			// if the diposition is not provided, for now the part should be treated as inline (later non-embedded inline attachments are moved)
			if (Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
				parsedComponents.attachmentList.put(parseResourceName(parseContentID(currentPart), parseFileName(currentPart)), ds);
			} else if (disposition == null || Part.INLINE.equalsIgnoreCase(disposition)) {
				if (parseContentID(currentPart) != null) {
					parsedComponents.cidMap.put(parseContentID(currentPart), ds);
				} else {
					// contentID missing -> treat as standard attachment
					parsedComponents.attachmentList.put(parseResourceName(null, parseFileName(currentPart)), ds);
				}
			} else {
				throw new IllegalStateException("invalid attachment type");
			}
		}
	}

	@SuppressWarnings("StatementWithEmptyBody")
	private static void parseHeader(final Header header, @Nonnull final ParsedMimeMessageComponents parsedComponents) {
		if (header.getName().equals("Disposition-Notification-To")) {
			parsedComponents.dispositionNotificationTo = createAddress(header, "Disposition-Notification-To");
		} else if (header.getName().equals("Return-Receipt-To")) {
			parsedComponents.returnReceiptTo = createAddress(header, "Return-Receipt-To");
		} else if (header.getName().equals("Return-Path")) {
			parsedComponents.bounceToAddress = createAddress(header, "Return-Path");
		} else if (!HEADERS_TO_IGNORE.contains(header.getName())) {
			parsedComponents.headers.put(header.getName(), header.getValue());
		} else {
			// header recognized, but not relevant (see #HEADERS_TO_IGNORE)
		}
	}

	@SuppressWarnings("WeakerAccess")
	public static String parseFileName(@Nonnull final Part currentPart) {
		try {
			return currentPart.getFileName();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_FILENAME, e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public static String parseContentID(@Nonnull final MimePart currentPart) {
		try {
			return currentPart.getContentID();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_CONTENT_ID, e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	public static MimeBodyPart getBodyPartAtIndex(final Multipart parentMultiPart, final int index) {
		try {
			return (MimeBodyPart) parentMultiPart.getBodyPart(index);
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(format(MimeMessageParseException.ERROR_GETTING_BODYPART_AT_INDEX, index), e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	public static int countBodyParts(final Multipart mp) {
		try {
			return mp.getCount();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_MULTIPART_COUNT, e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	public static <T> T parseContent(@Nonnull final MimePart currentPart) {
		try {
			//noinspection unchecked
			return (T) currentPart.getContent();
		} catch (IOException | MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_CONTENT, e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	public static String parseDisposition(@Nonnull final MimePart currentPart) {
		try {
			return currentPart.getDisposition();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_DISPOSITION, e);
		}
	}

	@Nonnull
	private static String parseResourceName(@Nullable final String possibleWrappedContentID, @Nonnull final String fileName) {
		if (!valueNullOrEmpty(possibleWrappedContentID)) {
			// https://regex101.com/r/46ulb2/1
			String unwrappedContentID = possibleWrappedContentID.replaceAll("^<?(.*?)>?$", "$1");
			String extension = (!valueNullOrEmpty(fileName) && fileName.contains("."))
					? fileName.substring(fileName.lastIndexOf("."))
					: "";
			return (unwrappedContentID.endsWith(extension)) ? unwrappedContentID : unwrappedContentID + extension;
		} else {
			return fileName;
		}
	}

	@SuppressWarnings("WeakerAccess")
	@Nonnull
	public static List<Header> retrieveAllHeaders(@Nonnull final MimePart part) {
		try {
			return Collections.list(part.getAllHeaders());
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_ALL_HEADERS, e);
		}
	}

	@Nonnull
	private static InternetAddress createAddress(final Header header, final String typeOfAddress) {
		try {
			return new InternetAddress(header.getValue());
		} catch (final AddressException e) {
			throw new MimeMessageParseException(format(MimeMessageParseException.ERROR_PARSING_ADDRESS, typeOfAddress), e);
		}
	}

	/**
	 * Checks whether the MimePart contains an object of the given mime type.
	 *
	 * @param part     the current MimePart
	 * @param mimeType the mime type to check
	 * @return {@code true} if the MimePart matches the given mime type, {@code false} otherwise
	 */
	@SuppressWarnings("WeakerAccess")
	public static boolean isMimeType(@Nonnull final MimePart part, @Nonnull final String mimeType) {
		// Do not use part.isMimeType(String) as it is broken for MimeBodyPart
		// and does not really check the actual content type.

		try {
			final ContentType contentType = new ContentType(retrieveDataHandler(part).getContentType());
			return contentType.match(mimeType);
		} catch (final ParseException ex) {
			return retrieveContentType(part).equalsIgnoreCase(mimeType);
		}
	}

	@SuppressWarnings("WeakerAccess")
	public static String retrieveContentType(@Nonnull final MimePart part) {
		try {
			return part.getContentType();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_CONTENT_TYPE, e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	public static DataHandler retrieveDataHandler(@Nonnull final MimePart part) {
		try {
			return part.getDataHandler();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_DATAHANDLER, e);
		}
	}

	/**
	 * Parses the MimePart to create a DataSource.
	 *
	 * @param part the current part to be processed
	 * @return the DataSource
	 */
	@Nonnull
	private static DataSource createDataSource(@Nonnull final MimePart part) {
		final DataHandler dataHandler = retrieveDataHandler(part);
		final DataSource dataSource = dataHandler.getDataSource();
		final String contentType = parseBaseMimeType(dataSource.getContentType());
		final byte[] content = readContent(retrieveInputStream(dataSource));
		final ByteArrayDataSource result = new ByteArrayDataSource(content, contentType);
		final String dataSourceName = parseDataSourceName(part, dataSource);

		result.setName(dataSourceName);
		return result;
	}

	@SuppressWarnings("WeakerAccess")
	public static InputStream retrieveInputStream(final DataSource dataSource) {
		try {
			return dataSource.getInputStream();
		} catch (final IOException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_INPUTSTREAM, e);
		}
	}

	@Nullable
	private static String parseDataSourceName(@Nonnull final Part part, @Nonnull final DataSource dataSource) {
		final String result = !valueNullOrEmpty(dataSource.getName()) ? dataSource.getName() : parseFileName(part);
		return !valueNullOrEmpty(result) ? decodeText(result) : null;
	}

	@Nonnull
	private static String decodeText(@Nonnull final String result) {
		try {
			return MimeUtility.decodeText(result);
		} catch (final UnsupportedEncodingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_DECODING_TEXT, e);
		}
	}

	@Nonnull
	private static byte[] readContent(@Nonnull final InputStream is) {
		final BufferedInputStream isReader = new BufferedInputStream(is);
		final ByteArrayOutputStream os = new ByteArrayOutputStream();
		final BufferedOutputStream osWriter = new BufferedOutputStream(os);

		int ch;
		try {
			while ((ch = isReader.read()) != -1) {
				osWriter.write(ch);
			}
			osWriter.flush();
			final byte[] result = os.toByteArray();
			osWriter.close();
			return result;
		} catch (final IOException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_READING_CONTENT, e);
		}
	}

	/**
	 * @param fullMimeType the mime type from the mail api
	 * @return The real mime type
	 */
	@Nonnull
	private static String parseBaseMimeType(@Nonnull final String fullMimeType) {
		final int pos = fullMimeType.indexOf(';');
		if (pos >= 0) {
			return fullMimeType.substring(0, pos);
		}
		return fullMimeType;
	}


	@SuppressWarnings("WeakerAccess")
	@Nonnull
	public static List<InternetAddress> parseToAddresses(@Nonnull final MimeMessage mimeMessage) {
		return parseInternetAddresses(retrieveRecipients(mimeMessage, RecipientType.TO));
	}

	@SuppressWarnings("WeakerAccess")
	@Nonnull
	public static List<InternetAddress> parseCcAddresses(@Nonnull final MimeMessage mimeMessage) {
		return parseInternetAddresses(retrieveRecipients(mimeMessage, RecipientType.CC));
	}

	@SuppressWarnings("WeakerAccess")
	@Nonnull
	public static List<InternetAddress> parseBccAddresses(@Nonnull final MimeMessage mimeMessage) {
		return parseInternetAddresses(retrieveRecipients(mimeMessage, RecipientType.BCC));
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public static Address[] retrieveRecipients(@Nonnull final MimeMessage mimeMessage, final RecipientType recipientType) {
		try {
			return mimeMessage.getRecipients(recipientType);
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(format(MimeMessageParseException.ERROR_GETTING_RECIPIENTS, recipientType), e);
		}
	}

	@Nonnull
	private static List<InternetAddress> parseInternetAddresses(@Nullable final Address[] recipients) {
		final List<Address> addresses = (recipients != null) ? Arrays.asList(recipients) : new ArrayList<Address>();
		final List<InternetAddress> mailAddresses = new ArrayList<>();
		for (final Address address : addresses) {
			if (address instanceof InternetAddress) {
				mailAddresses.add((InternetAddress) address);
			}
		}
		return mailAddresses;
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public static InternetAddress parseFromAddress(@Nonnull final MimeMessage mimeMessage) {
		try {
			final Address[] addresses = mimeMessage.getFrom();
			return (addresses == null || addresses.length == 0) ? null : (InternetAddress) addresses[0];
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_FROMADDRESS, e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public static InternetAddress parseReplyToAddresses(@Nonnull final MimeMessage mimeMessage) {
		try {
			final Address[] addresses = mimeMessage.getReplyTo();
			return (addresses == null || addresses.length == 0) ? null : (InternetAddress) addresses[0];
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_REPLY_TO_ADDRESSES, e);
		}
	}

	@Nullable
	public static String parseSubject(@Nonnull final MimeMessage mimeMessage) {
		try {
			return mimeMessage.getSubject();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_SUBJECT, e);
		}
	}


	@SuppressWarnings("WeakerAccess")
	@Nullable
	public static String parseMessageId(@Nonnull final MimeMessage mimeMessage) {
		try {
			return mimeMessage.getMessageID();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_MESSAGE_ID, e);
		}
	}

	private static void moveInvalidEmbeddedResourcesToAttachments(ParsedMimeMessageComponents parsedComponents) {
		final String htmlContent = parsedComponents.htmlContent;
		for (Map.Entry<String, DataSource> cidEntry : parsedComponents.cidMap.entrySet()) {
			String cid = extractCID(cidEntry.getKey());
			if (htmlContent == null || !htmlContent.contains("cid:" + cid)) {
				parsedComponents.attachmentList.put(cid, cidEntry.getValue());
				parsedComponents.cidMap.remove(cidEntry.getKey());
			}
		}
	}

	public static class ParsedMimeMessageComponents {
		private final Map<String, DataSource> attachmentList = new TreeMap<>();
		private final Map<String, DataSource> cidMap = new TreeMap<>();
		private final Map<String, Object> headers = new HashMap<>();
		private final List<InternetAddress> toAddresses = new ArrayList<>();
		private final List<InternetAddress> ccAddresses = new ArrayList<>();
		private final List<InternetAddress> bccAddresses = new ArrayList<>();
		private String messageId;
		private String subject;
		private InternetAddress fromAddress;
		private InternetAddress replyToAddresses;
		private InternetAddress dispositionNotificationTo;
		private InternetAddress returnReceiptTo;
		private InternetAddress bounceToAddress;
		private String plainContent;
		private String htmlContent;

		public String getMessageId() {
			return messageId;
		}

		public Map<String, DataSource> getAttachmentList() {
			return attachmentList;
		}

		public Map<String, DataSource> getCidMap() {
			return cidMap;
		}

		public Map<String, Object> getHeaders() {
			return headers;
		}

		public List<InternetAddress> getToAddresses() {
			return toAddresses;
		}

		public List<InternetAddress> getCcAddresses() {
			return ccAddresses;
		}

		public List<InternetAddress> getBccAddresses() {
			return bccAddresses;
		}

		public String getSubject() {
			return subject;
		}

		public InternetAddress getFromAddress() {
			return fromAddress;
		}

		public InternetAddress getReplyToAddresses() {
			return replyToAddresses;
		}

		public InternetAddress getDispositionNotificationTo() {
			return dispositionNotificationTo;
		}

		public InternetAddress getReturnReceiptTo() {
			return returnReceiptTo;
		}

		public InternetAddress getBounceToAddress() {
			return bounceToAddress;
		}

		public String getPlainContent() {
			return plainContent;
		}

		public String getHtmlContent() {
			return htmlContent;
		}
	}
}