package org.simplejavamail.converter.internal.mimemessage;

import com.sun.mail.handlers.text_plain;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.internal.util.MiscUtil;
import org.simplejavamail.internal.util.Preconditions;

import javax.activation.ActivationDataFlavor;
import javax.activation.CommandMap;
import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.MailcapCommandMap;
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
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.simplejavamail.internal.util.MiscUtil.extractCID;
import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;
import static org.simplejavamail.internal.util.SimpleOptional.ofNullable;

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

		MailcapCommandMap mc = (MailcapCommandMap)CommandMap.getDefaultCommandMap();
		mc.addMailcap("text/calendar;; x-java-content-handler=" + text_calendar.class.getName());
		CommandMap.setDefaultCommandMap(mc);
	}

	/**
	 * Extracts the content of a MimeMessage recursively.
	 */
	public static ParsedMimeMessageComponents parseMimeMessage(@NotNull final MimeMessage mimeMessage) {
		final ParsedMimeMessageComponents parsedComponents = new ParsedMimeMessageComponents();
		parsedComponents.messageId = parseMessageId(mimeMessage);
		parsedComponents.sentDate = parseSentDate(mimeMessage);
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

	private static void parseMimePartTree(@NotNull final MimePart currentPart, @NotNull final ParsedMimeMessageComponents parsedComponents) {
		for (final Header header : retrieveAllHeaders(currentPart)) {
			parseHeader(header, parsedComponents);
		}

		final String disposition = parseDisposition(currentPart);

		if (isMimeType(currentPart, "text/plain") && !Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
			//noinspection RedundantCast
			parsedComponents.plainContent.append((Object) parseContent(currentPart));
		} else if (isMimeType(currentPart, "text/html") && !Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
			//noinspection RedundantCast
			parsedComponents.htmlContent.append((Object) parseContent(currentPart));
		} else if (isMimeType(currentPart, "text/calendar") && parsedComponents.calendarContent == null && !Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
			parsedComponents.calendarContent = parseCalendarContent(currentPart);
			parsedComponents.calendarMethod = parseCalendarMethod(currentPart);
		} else if (isMimeType(currentPart, "multipart/*")) {
			final Multipart mp = parseContent(currentPart);
			for (int i = 0, count = countBodyParts(mp); i < count; i++) {
				parseMimePartTree(getBodyPartAtIndex(mp, i), parsedComponents);
			}
		} else {
			final DataSource ds = createDataSource(currentPart);
			// if the diposition is not provided, for now the part should be treated as inline (later non-embedded inline attachments are moved)
			if (Part.ATTACHMENT.equalsIgnoreCase(disposition)) {
				parsedComponents.attachmentList.put(parseResourceNameOrUnnamed(parseContentID(currentPart), parseFileName(currentPart)), ds);
			} else if (disposition == null || Part.INLINE.equalsIgnoreCase(disposition)) {
				if (parseContentID(currentPart) != null) {
					parsedComponents.cidMap.put(parseContentID(currentPart), ds);
				} else {
					// contentID missing -> treat as standard attachment
					parsedComponents.attachmentList.put(parseResourceNameOrUnnamed(null, parseFileName(currentPart)), ds);
				}
			} else {
				throw new IllegalStateException("invalid attachment type");
			}
		}
	}

	@SuppressWarnings("StatementWithEmptyBody")
	private static void parseHeader(final Header header, @NotNull final ParsedMimeMessageComponents parsedComponents) {
		if (isEmailHeader(header, "Disposition-Notification-To")) {
			parsedComponents.dispositionNotificationTo = createAddress(header.getValue(), "Disposition-Notification-To");
		} else if (isEmailHeader(header, "Return-Receipt-To")) {
			parsedComponents.returnReceiptTo = createAddress(header.getValue(), "Return-Receipt-To");
		} else if (isEmailHeader(header, "Return-Path")) {
			parsedComponents.bounceToAddress = createAddress(header.getValue(), "Return-Path");
		} else if (!HEADERS_TO_IGNORE.contains(header.getName())) {
			if (!parsedComponents.headers.containsKey(header.getName())) {
				parsedComponents.headers.put(header.getName(), new ArrayList<>());
			}
			parsedComponents.headers.get(header.getName()).add(header.getValue());
		} else {
			// header recognized, but not relevant (see #HEADERS_TO_IGNORE)
		}
	}

	private static boolean isEmailHeader(Header header, String emailHeaderName) {
		return header.getName().equals(emailHeaderName) &&
				!valueNullOrEmpty(header.getValue()) &&
				!valueNullOrEmpty(header.getValue().trim()) &&
				!header.getValue().equals("<>");
	}

	@SuppressWarnings("WeakerAccess")
	public static String parseFileName(@NotNull final Part currentPart) {
		try {
			return currentPart.getFileName();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_FILENAME, e);
		}
	}
	
	/**
     * @return Returns the "content" part as String from the Calendar content type
     */
    public static String parseCalendarContent(@NotNull MimePart currentPart) {
        Object content = parseContent(currentPart);
        if (content instanceof InputStream) {
            final InputStream calendarContent = (InputStream) content;
            try {
                return MiscUtil.readInputStreamToString(calendarContent, UTF_8);
            } catch (IOException e) {
                throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_CALENDAR_CONTENT, e);
            }
        }
        return String.valueOf(content);
    }

	/**
	 * @return Returns the "method" part from the Calendar content type (such as "{@code text/calendar; charset="UTF-8"; method="REQUEST"}").
	 */
	@SuppressWarnings("WeakerAccess")
	public static String parseCalendarMethod(@NotNull MimePart currentPart) {
		Pattern compile = Pattern.compile("method=\"?(\\w+)");
		final String contentType;
		try {
			contentType = currentPart.getDataHandler().getContentType();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_CALENDAR_CONTENTTYPE, e);
		}
		Matcher matcher = compile.matcher(contentType);
		Preconditions.assumeTrue(matcher.find(), "Calendar METHOD not found in bodypart content type");
		return matcher.group(1);
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public static String parseContentID(@NotNull final MimePart currentPart) {
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

	@SuppressWarnings({"WeakerAccess", "unchecked"})
	public static <T> T parseContent(@NotNull final MimePart currentPart) {
		try {
			return (T) currentPart.getContent();
		} catch (IOException | MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_CONTENT, e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public static String parseDisposition(@NotNull final MimePart currentPart) {
		try {
			return currentPart.getDisposition();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_DISPOSITION, e);
		}
	}

	@NotNull
	private static String parseResourceNameOrUnnamed(@Nullable final String possibleWrappedContentID, @NotNull final String fileName) {
		String resourceName = parseResourceName(possibleWrappedContentID, fileName);
		return valueNullOrEmpty(resourceName) ? "unnamed" : resourceName;
	}

	@NotNull
	private static String parseResourceName(@Nullable String possibleWrappedContentID, @NotNull String fileName) {
		if (valueNullOrEmpty(fileName) && !valueNullOrEmpty(possibleWrappedContentID)) {
			return possibleWrappedContentID.replaceAll("^<?(.*?)>?$", "$1"); // https://regex101.com/r/46ulb2/1
		} else {
			return fileName;
		}
	}

	@SuppressWarnings("WeakerAccess")
	@NotNull
	public static List<Header> retrieveAllHeaders(@NotNull final MimePart part) {
		try {
			return Collections.list(part.getAllHeaders());
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_ALL_HEADERS, e);
		}
	}

	@Nullable
	static InternetAddress createAddress(final String address, final String typeOfAddress) {
		try {
			return address.trim().isEmpty() ? null : new InternetAddress(address);
		} catch (final AddressException e) {
			if (e.getMessage().equals("Empty address")) {
				return null;
			}
			throw new MimeMessageParseException(format(MimeMessageParseException.ERROR_PARSING_ADDRESS, typeOfAddress, address), e);
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
	public static boolean isMimeType(@NotNull final MimePart part, @NotNull final String mimeType) {
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
	public static String retrieveContentType(@NotNull final MimePart part) {
		try {
			return part.getContentType();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_CONTENT_TYPE, e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	public static DataHandler retrieveDataHandler(@NotNull final MimePart part) {
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
	@NotNull
	private static DataSource createDataSource(@NotNull final MimePart part) {
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
	private static String parseDataSourceName(@NotNull final Part part, @NotNull final DataSource dataSource) {
		final String result = !valueNullOrEmpty(dataSource.getName()) ? dataSource.getName() : parseFileName(part);
		return !valueNullOrEmpty(result) ? decodeText(result) : null;
	}

	@NotNull
	private static String decodeText(@NotNull final String result) {
		try {
			return MimeUtility.decodeText(result);
		} catch (final UnsupportedEncodingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_DECODING_TEXT, e);
		}
	}

	@NotNull
	private static byte[] readContent(@NotNull final InputStream is) {
		try {
			return MiscUtil.readInputStreamToBytes(is);
		} catch (final IOException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_READING_CONTENT, e);
		}
	}

	/**
	 * @param fullMimeType the mime type from the mail api
	 * @return The real mime type
	 */
	@NotNull
	private static String parseBaseMimeType(@NotNull final String fullMimeType) {
		final int pos = fullMimeType.indexOf(';');
		if (pos >= 0) {
			return fullMimeType.substring(0, pos);
		}
		return fullMimeType;
	}


	@SuppressWarnings("WeakerAccess")
	@NotNull
	public static List<InternetAddress> parseToAddresses(@NotNull final MimeMessage mimeMessage) {
		return parseInternetAddresses(retrieveRecipients(mimeMessage, RecipientType.TO));
	}

	@SuppressWarnings("WeakerAccess")
	@NotNull
	public static List<InternetAddress> parseCcAddresses(@NotNull final MimeMessage mimeMessage) {
		return parseInternetAddresses(retrieveRecipients(mimeMessage, RecipientType.CC));
	}

	@SuppressWarnings("WeakerAccess")
	@NotNull
	public static List<InternetAddress> parseBccAddresses(@NotNull final MimeMessage mimeMessage) {
		return parseInternetAddresses(retrieveRecipients(mimeMessage, RecipientType.BCC));
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public static Address[] retrieveRecipients(@NotNull final MimeMessage mimeMessage, final RecipientType recipientType) {
		try {
			// return mimeMessage.getRecipients(recipientType); // can fail in strict mode, see https://github.com/bbottema/simple-java-mail/issues/227
			// workaround following (copied and modified from JavaMail internal code):
			String s = mimeMessage.getHeader(getHeaderName(recipientType), ",");
			return (s == null) ? null : InternetAddress.parseHeader(s, false);
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(format(MimeMessageParseException.ERROR_GETTING_RECIPIENTS, recipientType), e);
		}
	}

	private static String getHeaderName(RecipientType recipientType) {
		if (recipientType == RecipientType.TO) {
			return "To";
		} else if (recipientType == RecipientType.CC) {
			return "Cc";
		} else {
			Preconditions.assumeTrue(recipientType == RecipientType.BCC, "invalid recipient type: " + recipientType);
			return "Bcc";
		}
	}

	@NotNull
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
	public static InternetAddress parseFromAddress(@NotNull final MimeMessage mimeMessage) {
		try {
			final Address[] addresses = mimeMessage.getFrom();
			return (addresses == null || addresses.length == 0) ? null : (InternetAddress) addresses[0];
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_FROMADDRESS, e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public static InternetAddress parseReplyToAddresses(@NotNull final MimeMessage mimeMessage) {
		try {
			final Address[] addresses = mimeMessage.getReplyTo();
			return (addresses == null || addresses.length == 0) ? null : (InternetAddress) addresses[0];
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_PARSING_REPLY_TO_ADDRESSES, e);
		}
	}

	@NotNull
	public static String parseSubject(@NotNull final MimeMessage mimeMessage) {
		try {
			return ofNullable(mimeMessage.getSubject()).orElse("");
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_SUBJECT, e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public static String parseMessageId(@NotNull final MimeMessage mimeMessage) {
		try {
			return mimeMessage.getMessageID();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_MESSAGE_ID, e);
		}
	}

	@SuppressWarnings("WeakerAccess")
	@Nullable
	public static Date parseSentDate(@NotNull final MimeMessage mimeMessage) {
		try {
			return mimeMessage.getSentDate();
		} catch (final MessagingException e) {
			throw new MimeMessageParseException(MimeMessageParseException.ERROR_GETTING_SEND_DATE, e);
		}
	}

	static void moveInvalidEmbeddedResourcesToAttachments(ParsedMimeMessageComponents parsedComponents) {
		final String htmlContent = parsedComponents.htmlContent.toString();
		for (Iterator<Map.Entry<String, DataSource>> it = parsedComponents.cidMap.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<String, DataSource> cidEntry = it.next();
			String cid = extractCID(cidEntry.getKey());
			if (!htmlContent.contains("cid:" + cid)) {
				parsedComponents.attachmentList.put(cid, cidEntry.getValue());
				it.remove();
			}
		}
	}

	public static class ParsedMimeMessageComponents {
		final Map<String, DataSource> attachmentList = new TreeMap<>();
		final Map<String, DataSource> cidMap = new TreeMap<>();
		private final Map<String, Collection<Object>> headers = new HashMap<>();
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
		private final StringBuilder plainContent = new StringBuilder();
		final StringBuilder htmlContent = new StringBuilder();
		private String calendarMethod;
		private String calendarContent;
		private Date sentDate;

		@Nullable
		public String getMessageId() {
			return messageId;
		}

		public Map<String, DataSource> getAttachmentList() {
			return attachmentList;
		}

		public Map<String, DataSource> getCidMap() {
			return cidMap;
		}

		public Map<String, Collection<Object>> getHeaders() {
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

		@Nullable
		public String getSubject() {
			return subject;
		}

		@Nullable
		public InternetAddress getFromAddress() {
			return fromAddress;
		}

		@Nullable
		public InternetAddress getReplyToAddresses() {
			return replyToAddresses;
		}

		@Nullable
		public InternetAddress getDispositionNotificationTo() {
			return dispositionNotificationTo;
		}

		@Nullable
		public InternetAddress getReturnReceiptTo() {
			return returnReceiptTo;
		}

		@Nullable
		public InternetAddress getBounceToAddress() {
			return bounceToAddress;
		}

		@Nullable
		public String getPlainContent() {
			return plainContent.length() == 0 ? null : plainContent.toString();
		}

		@Nullable
		public String getHtmlContent() {
			return htmlContent.length() == 0 ? null : htmlContent.toString();
		}

		@Nullable
		public String getCalendarContent() {
			return calendarContent;
		}

		@Nullable
		public String getCalendarMethod() {
			return calendarMethod;
		}

		@Nullable
		public Date getSentDate() {
			return sentDate != null ? new Date(sentDate.getTime()) : null;
		}
	}

	/**
	 * DataContentHandler for text/calendar, based on {@link com.sun.mail.handlers.text_html}.
	 * <p>
	 * The unfortunate class name matches Java Mail's handler naming convention.
	 */
	static class text_calendar extends text_plain {
		private static final ActivationDataFlavor[] myDF = {
				new ActivationDataFlavor(String.class, "text/calendar", "iCalendar String")
		};

		@Override
		protected ActivationDataFlavor[] getDataFlavors() {
			return myDF;
		}
	}
}