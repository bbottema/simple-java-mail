package org.simplejavamail.internal.util;

import org.simplejavamail.email.Recipient;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.Message.RecipientType;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeUtility;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.Charset;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static java.lang.Integer.toHexString;
import static java.util.Arrays.asList;
import static java.util.regex.Pattern.compile;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

public final class MiscUtil {

	private static final Pattern MATCH_INSIDE_CIDBRACKETS = compile("<?([^>]*)>?");

	private static final Pattern COMMA_DELIMITER_PATTERN = compile("(@.*?>?)\\s*[,;]");
	private static final Pattern TRAILING_TOKEN_DELIMITER_PATTERN = compile("<\\|>$");
	private static final Pattern TOKEN_DELIMITER_PATTERN = compile("\\s*<\\|>\\s*");

	public static <T> T checkNotNull(final T value, final String msg) {
		if (value == null) {
			throw new NullPointerException(msg);
		}
		return value;
	}

	public static <T> T checkArgumentNotEmpty(final T value, final String msg) {
		if (valueNullOrEmpty(value)) {
			throw new IllegalArgumentException(msg);
		}
		return value;
	}

	public static <T> boolean valueNullOrEmpty(final T value) {
		return value == null ||
				(value instanceof String && ((String) value).isEmpty()) ||
				(value instanceof Collection && ((Collection<?>) value).isEmpty()) ||
				(value instanceof byte[] && ((byte[]) value).length == 0);
	}

	public static String buildLogString(final byte[] bytes, final boolean isReceived) {
		final StringBuilder debugMsg = new StringBuilder();
		debugMsg.append(isReceived ? "Received: " : "Sent: ");
		for (final byte aByte : bytes) {
			debugMsg.append(toHexString(toInt(aByte))).append(" ");
		}
		return debugMsg.toString();
	}

	public static int toInt(final byte b) {
		return b & 0xFF;
	}

	/**
	 * To make sure email clients can interpret text properly, we need to encode some values according to RFC-2047.
	 */
	public static String encodeText(@Nullable final String name) {
		if (name == null) {
			return null;
		}
		try {
			return MimeUtility.encodeText(name);
		} catch (final UnsupportedEncodingException e) {
			throw new IllegalArgumentException(e.getMessage(), e);
		}
	}

	@Nullable
	public static String extractCID(@Nullable final String cid) {
		return (cid != null) ? MATCH_INSIDE_CIDBRACKETS.matcher(cid).replaceAll("$1") : null;
	}

	/**
	 * Uses standard JDK java to read an inputstream to String using the given encoding (in {@link ByteArrayOutputStream#toString(String)}).
	 */
	@Nonnull
	public static String readInputStreamToString(@Nonnull final InputStream inputStream, @Nonnull final Charset charset)
			throws IOException {
		final BufferedInputStream bufferedInputStream = new BufferedInputStream(inputStream);
		final ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
		int result = bufferedInputStream.read();
		while (result != -1) {
			byteArrayOutputStream.write((byte) result);
			result = bufferedInputStream.read();
		}
		return byteArrayOutputStream.toString(checkNonEmptyArgument(charset, "charset").name());
	}

	/**
	 * Recognizes the tails of each address entry, so it can replace the [';] delimiters, thereby disambiguating the delimiters, since they can
	 * appear in names as well (making it difficult to split on [,;] delimiters.
	 *
	 * @param emailAddressList The delimited list of addresses (or single address) optionally including the name.
	 * @return Array of address entries optionally including the names, trimmed for spaces or trailing delimiters.
	 */
	@Nonnull
	public static String[] extractEmailAddresses(@Nonnull final String emailAddressList) {
		checkNonEmptyArgument(emailAddressList, "emailAddressList");
		// recognize value tails and replace the delimiters there, disambiguating delimiters
		final String unambiguousDelimitedList = COMMA_DELIMITER_PATTERN.matcher(emailAddressList).replaceAll("$1<|>");
		final String withoutTrailingDelimeter = TRAILING_TOKEN_DELIMITER_PATTERN.matcher(unambiguousDelimitedList).replaceAll("");
		return TOKEN_DELIMITER_PATTERN.split(withoutTrailingDelimeter, 0);
	}
	
	/**
	 * @param name         The name to use as fixed name or as default (depending on <code>fixedName</code> flag). Regardless of that flag, if a name
	 *                     is <code>null</code>, the other one will be used.
	 * @param fixedName    Determines if the given name should be used as override.
	 * @param emailAddress An RFC2822 compliant email address, which can contain a name inside as well.
	 */
	@Nonnull
	public static Recipient interpretRecipient(@Nullable final String name, boolean fixedName, @Nonnull final String emailAddress, @Nullable final RecipientType type) {
		try {
			final InternetAddress parsedAddress = InternetAddress.parse(emailAddress, false)[0];
			final String relevantName = (fixedName || parsedAddress.getPersonal() == null)
					? defaultTo(name, parsedAddress.getPersonal())
					: defaultTo(parsedAddress.getPersonal(), name);
			return new Recipient(relevantName, parsedAddress.getAddress(), type);
		} catch (final AddressException e) {
			// InternetAddress failed to parse the email address even in non-strict mode
			// just assume the address was too complex rather than plain wrong, and let our own email validation
			// library take care of it when sending the email
			return new Recipient(name, emailAddress, type);
		}
	}
	
	@Nullable
	public static <T> T defaultTo(@Nullable final T value, @Nullable final T defaultValue) {
		return value != null ? value : defaultValue;
	}

	@SuppressWarnings("unchecked")
	public static <T> T loadLibraryClass(@Nonnull String libraryClassWhichShouldBeAvailable,
										 @Nonnull String apiClassToLoad,
										 @Nonnull String libraryNotFoundMessage,
										 @Nonnull String otherExceptions) {
		if (!classAvailable(libraryClassWhichShouldBeAvailable)) {
			throw new LibraryLoaderException(libraryNotFoundMessage);
		}
		
		try {
			return (T) Class.forName(apiClassToLoad).newInstance();
		} catch (Exception | NoClassDefFoundError e) {
			throw new LibraryLoaderException(otherExceptions, e);
		}
	}
	
	public static boolean classAvailable(@Nonnull String className) {
		try {
			Class.forName(className);
			return true;
		} catch (ClassNotFoundException | NoClassDefFoundError e) {
			return false;
		}
	}
	
	@SuppressWarnings("unchecked")
	public static <T1,T2> Map.Entry<T1,T2>[] zip(T1[] zipLeft, T2[] zipRight) {
		return zip(asList(zipLeft), asList(zipRight)).toArray(new Map.Entry[] {});
	}
	
	public static <T1,T2> List<Map.Entry<T1,T2>> zip(List<T1> zipLeft, List<T2> zipRight) {
		assumeTrue(zipLeft.size() == zipRight.size(), "Can't zip lists, sizes are not equals");
		List<Map.Entry<T1,T2>> zipped = new ArrayList<>();
		for (int i = 0; i < zipLeft.size(); i++) {
			zipped.add(new AbstractMap.SimpleEntry<>(zipLeft.get(i), zipRight.get(i)));
		}
		return zipped;
	}
}