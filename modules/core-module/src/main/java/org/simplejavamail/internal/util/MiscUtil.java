package org.simplejavamail.internal.util;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Recipient;

import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.activation.URLDataSource;
import javax.mail.Message.RecipientType;
import javax.mail.internet.AddressException;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeUtility;
import javax.mail.util.ByteArrayDataSource;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.regex.Pattern;

import static java.lang.Integer.toHexString;
import static java.lang.String.format;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.regex.Pattern.compile;
import static java.util.regex.Pattern.quote;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.internal.util.SimpleOptional.ofNullable;

public final class MiscUtil {

	private static final Pattern MATCH_INSIDE_CIDBRACKETS = compile("<?([^>]*)>?");

	private static final Pattern COMMA_DELIMITER_PATTERN = compile("(@.*?>?)\\s*[,;]");
	private static final Pattern TRAILING_TOKEN_DELIMITER_PATTERN = compile("<\\|>$");
	private static final Pattern TOKEN_DELIMITER_PATTERN = compile("\\s*<\\|>\\s*");

	private static final Pattern ABSOLUTE_URL_PATTERN = compile(format("^(%s|%s|%s).*", quote("http://"), quote("https://"), quote("file:/")));

	private static final Random RANDOM = new Random();

	@SuppressFBWarnings(value = "NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_NULLABLE")
	public static <T> T checkNotNull(final @Nullable T value, final @Nullable String msg) {
		if (value == null) {
			throw new NullPointerException(msg);
		}
		return value;
	}

	public static <T> T checkArgumentNotEmpty(final @Nullable T value, final @Nullable String msg) {
		if (valueNullOrEmpty(value)) {
			throw new IllegalArgumentException(msg);
		}
		return value;
	}

	public static <T> boolean valueNullOrEmpty(final @Nullable T value) {
		return value == null ||
				(value instanceof String && ((String) value).isEmpty()) ||
				(value instanceof Collection && ((Collection<?>) value).isEmpty()) ||
				(value instanceof byte[] && ((byte[]) value).length == 0);
	}

	public static String buildLogStringForSOCKSCommunication(final byte[] bytes, final boolean isReceived) {
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
	@Nullable
	public static String encodeText(@Nullable final String name) {
		if (name == null) {
			return null;
		}
		try {
			return MimeUtility.encodeText(name, UTF_8.name(), "B");
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
	@NotNull
	public static String readInputStreamToString(@NotNull final InputStream inputStream, @NotNull final Charset charset)
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
	 * Uses standard JDK java to read an inputstream to byte[].
	 */
	@NotNull
	public static byte[] readInputStreamToBytes(@NotNull final InputStream inputStream)
			throws IOException {
		byte[] targetArray = new byte[inputStream.available()];
		//noinspection ResultOfMethodCallIgnored
		inputStream.read(targetArray);
		return targetArray;
	}

	/**
	 * Recognizes the tails of each address entry, so it can replace the [';] delimiters, thereby disambiguating the delimiters, since they can
	 * appear in names as well (making it difficult to split on [,;] delimiters.
	 *
	 * @param emailAddressList The delimited list of addresses (or single address) optionally including the name.
	 * @return Array of address entries optionally including the names, trimmed for spaces or trailing delimiters.
	 */
	@NotNull
	public static String[] extractEmailAddresses(@NotNull final String emailAddressList) {
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
	@NotNull
	public static Recipient interpretRecipient(@Nullable final String name, boolean fixedName, @NotNull final String emailAddress, @Nullable final RecipientType type) {
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
	
	public static boolean classAvailable(@NotNull String className) {
		try {
			Class.forName(className);
			return true;
		} catch (ClassNotFoundException | NoClassDefFoundError e) {
			return false;
		}
	}
	
	@SuppressWarnings({"unchecked", "unused"})
	public static <T1,T2> Map.Entry<T1,T2>[] zip(T1[] zipLeft, T2[] zipRight) {
		return zip(asList(zipLeft), asList(zipRight)).toArray(new Map.Entry[] {});
	}
	
	@SuppressWarnings("WeakerAccess")
	public static <T1,T2> List<Map.Entry<T1,T2>> zip(List<T1> zipLeft, List<T2> zipRight) {
		assumeTrue(zipLeft.size() == zipRight.size(), "Can't zip lists, sizes are not equals");
		List<Map.Entry<T1,T2>> zipped = new ArrayList<>();
		for (int i = 0; i < zipLeft.size(); i++) {
			zipped.add(new AbstractMap.SimpleEntry<>(zipLeft.get(i), zipRight.get(i)));
		}
		return zipped;
	}
	
	@Nullable
	public static String normalizeNewlines(final @Nullable String text) {
		return text == null ? null : text.replaceAll("\\r\\n", "\n").replaceAll("\\r", "\n");
	}
	
	public static int countMandatoryParameters(final @NotNull Method m) {
		int mandatoryParameterCount = 0;
		for (Annotation[] annotations : m.getParameterAnnotations()) {
			mandatoryParameterCount += !containsNullableAnnotation(annotations) ? 1 : 0;
		}
		return mandatoryParameterCount;
	}

	private static boolean containsNullableAnnotation(final Annotation[] annotations) {
		for (Annotation annotation : annotations.clone()) {
			if (annotation.annotationType() == Nullable.class) {
				return true;
			}
		}
		return false;
	}

	public static String readFileContent(@NotNull final File file) throws IOException {
		if (!file.exists()) {
			throw new IllegalArgumentException(format("File not found: %s", file));
		}
		return new String(Files.readAllBytes(file.toPath()), UTF_8);
	}

	public static boolean inputStreamEqual(InputStream inputOrg1, InputStream inputOrg2) {
		ByteArrayInputStream input1 = copyInputstream(inputOrg1);
		ByteArrayInputStream input2 = copyInputstream(inputOrg2);

		int ch;
		ch = input1.read();
		while (ch != -1) {
			int ch2 = input2.read();
			if (ch != ch2) {
				return false;
			}
			ch = input1.read();
		}

		int ch2 = input2.read();
		return (ch2 == -1);
	}

	public static ByteArrayInputStream copyInputstream(InputStream input) {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		byte[] buffer = new byte[1024];
		int len;
		try {
			while ((len = input.read(buffer)) > -1 ) {
				baos.write(buffer, 0, len);
			}
			baos.flush();

			if (input instanceof FileInputStream) {
				((FileInputStream) input).getChannel().position(0);
			} else {
				input.reset();
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		return new ByteArrayInputStream(baos.toByteArray());
	}

	@Nullable
	public static  DataSource tryResolveImageFileDataSource(@Nullable final String baseDir, @Nullable final String baseClassPath, @NotNull final String srcLocation)
			throws IOException {
		DataSource fileSource = tryResolveImageFileDataSourceFromDisk(baseDir, srcLocation);
		return (fileSource != null) ? fileSource : tryResolveFileDataSourceFromClassPath(baseClassPath, srcLocation);
	}

	@Nullable
	private static DataSource tryResolveImageFileDataSourceFromDisk(final @Nullable String baseDir, final @NotNull String srcLocation) {
		File file = new File(srcLocation);
		if (!file.exists() && !file.isAbsolute()) {
			file = new File(ofNullable(baseDir).orElse("."), srcLocation);
		}
		if (file.exists()) {
			final FileDataSource fileDataSource = new FileDataSource(file);
			fileDataSource.setFileTypeMap(ImageMimeType.IMAGE_MIMETYPES_FILE_TYPE_MAP);
			return fileDataSource;
		}
		return null;
	}

	@Nullable
	private static DataSource tryResolveFileDataSourceFromClassPath(final @Nullable String baseClassPath, final @NotNull String srcLocation)
			throws IOException {
		final String resourceName = (ofNullable(baseClassPath).orElse("") + srcLocation).replaceAll("//", "/");
		final InputStream is = MiscUtil.class.getResourceAsStream(resourceName);

		if (is != null) {
			try {
				final String mimeType = ImageMimeType.getContentType(srcLocation);
				final ByteArrayDataSource ds = new ByteArrayDataSource(is, mimeType);
				// EMAIL-125: set the name of the DataSource to the normalized resource URL similar to other DataSource implementations, e.g. FileDataSource, URLDataSource
				ds.setName(MiscUtil.class.getResource(resourceName).toString());
				return ds;
			} finally {
				is.close();
			}
		}
		return null;
	}

	@NotNull
	public static DataSource resolveUrlDataSource(@Nullable final URL baseUrl, @NotNull final String srcLocation)
			throws IOException {
		final URL url = (valueNullOrEmpty(baseUrl) || ABSOLUTE_URL_PATTERN.matcher(srcLocation).matches())
				? new URL(srcLocation)
				: new URL(baseUrl, srcLocation.replaceAll("&amp;", "&"));

		DataSource result = new URLDataSource(url);
		result.getInputStream();
		return result;
	}

	public static String randomCid10() {
		final int start = ' ';
		final int end = 'z' + 1;
		final int gap = end - start;

		final StringBuilder buffer = new StringBuilder();

		while (buffer.length() < 10) {
			final char ch = (char) (RANDOM.nextInt(gap) + start);
			if (Character.isLetter(ch)) {
				buffer.append(ch);
			}
		}

		return buffer.toString().toLowerCase();
	}
}