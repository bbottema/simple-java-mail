package org.simplejavamail.internal.util;

import jakarta.activation.DataSource;
import jakarta.activation.FileDataSource;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.ContentType;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeUtility;
import jakarta.mail.internet.ParseException;
import jakarta.mail.util.ByteArrayDataSource;
import lombok.SneakyThrows;
import lombok.val;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.internal.config.EmailProperty;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static java.lang.Integer.toHexString;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.Arrays.asList;
import static java.util.Optional.ofNullable;
import static java.util.regex.Pattern.compile;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

public final class MiscUtil {

	private static final Pattern MATCH_INSIDE_CIDBRACKETS = compile("<?([^>]*)>?");

	private static final Pattern COMMA_DELIMITER_PATTERN = compile("(@.*?>?)\\s*[,;]");
	private static final Pattern TRAILING_TOKEN_DELIMITER_PATTERN = compile("<\\|>$");
	private static final Pattern TOKEN_DELIMITER_PATTERN = compile("\\s*<\\|>\\s*");
	private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";
	private static final int MAX_URL_REDIRECTS = 10;

	private static final Random RANDOM = new Random();

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

	@Nullable
	public static <T> T emptyAsNull(final @Nullable T value) {
		return valueNullOrEmpty(value) ? null : value;
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
		ByteArrayOutputStream buffer = new ByteArrayOutputStream();
		byte[] data = new byte[1024];
		int read;
		while ((read = inputStream.read(data, 0, data.length)) != -1) {
			buffer.write(data, 0, read);
		}
		buffer.flush();
		return buffer.toByteArray();
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
	 *
	 * @deprecated this needs to be replaced with a version that only returns a pair with the name and email address
	 */
	@Deprecated
	@NotNull
	public static Recipient interpretRecipient(@Nullable final String name, boolean fixedName, @NotNull final String emailAddress, @Nullable final RecipientType type) {
		try {
			final InternetAddress parsedAddress = InternetAddress.parse(emailAddress, false)[0];
			final String relevantName = (fixedName || parsedAddress.getPersonal() == null)
					? defaultTo(name, parsedAddress.getPersonal())
					: defaultTo(parsedAddress.getPersonal(), name);
			return new Recipient(relevantName, parsedAddress.getAddress(), type, null);
		} catch (final AddressException e) {
			// InternetAddress failed to parse the email address even in non-strict mode
			// just assume the address was too complex rather than plain wrong, and let our own email validation
			// library take care of it when sending the email
			return new Recipient(name, emailAddress, type, null);
		}
	}
	
	@Nullable
	public static <T> T defaultTo(@Nullable final T value, @Nullable final T defaultValue) {
		return ofNullable(value).orElse(defaultValue);
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
			mandatoryParameterCount += !containsCliOptionalAnnotation(annotations) ? 1 : 0;
		}
		return mandatoryParameterCount;
	}

	private static boolean containsCliOptionalAnnotation(final Annotation[] annotations) {
		for (Annotation annotation : annotations.clone()) {
			if (annotation.annotationType() == Cli.Optional.class) {
				return true;
			}
		}
		return false;
	}

	@Nullable
	public static DataSource tryResolveImageFileDataSourceFromDisk(final @Nullable String baseDir, final boolean allowOutsideBaseDir, final @NotNull String srcLocation) {
		if (baseDir == null) {
			return tryLoadingFromDiskWithoutBase(srcLocation);
		}

		try {
			final Path configuredBasePath = Paths.get(baseDir).toAbsolutePath().normalize();
			final Path realBasePath = configuredBasePath.toRealPath();
			final Path configuredSourcePath = Paths.get(srcLocation);
			final Path directSourcePath = configuredSourcePath.toAbsolutePath().normalize();
			final Path sourcePath;

			if (configuredSourcePath.isAbsolute() || directSourcePath.startsWith(configuredBasePath)) {
				sourcePath = directSourcePath;
			} else {
				sourcePath = realBasePath.resolve(configuredSourcePath).normalize();
			}

			DataSource dataSource = tryLoadingContainedFile(realBasePath, sourcePath, allowOutsideBaseDir);
			if (dataSource == null && allowOutsideBaseDir && !sourcePath.equals(directSourcePath)) {
				dataSource = tryLoadingContainedFile(realBasePath, directSourcePath, true);
			}
			return dataSource;
		} catch (IOException | InvalidPathException e) {
			return allowOutsideBaseDir ? tryLoadingFromDiskWithoutBase(srcLocation) : null;
		}
	}

	@Nullable
	public static DataSource tryResolveFileDataSourceFromClassPath(final @Nullable String baseClassPath, final boolean allowOutsideBaseClassPath, final @NotNull String srcLocation)
			throws IOException {
		if (baseClassPath == null) {
			return tryLoadingFromClassPath(srcLocation);
		}

		final String normalizedBaseClassPath = normalizeResourcePath(baseClassPath);
		final String normalizedSourcePath = normalizeResourcePath(srcLocation);
		if (normalizedBaseClassPath == null) {
			return null;
		}

		final String sourceWithForwardSlashes = srcLocation.replace('\\', '/');
		final boolean explicitlyTargetsBase = pathStartsWithBase(normalizedBaseClassPath, sourceWithForwardSlashes)
				|| normalizedSourcePath != null && pathStartsWithBase(normalizedBaseClassPath, normalizedSourcePath);
		final String containedSourcePath;
		if (explicitlyTargetsBase) {
			containedSourcePath = normalizedSourcePath;
		} else {
			containedSourcePath = normalizeResourcePath(normalizedBaseClassPath + "/" + stripLeadingSlashes(sourceWithForwardSlashes));
		}

		DataSource dataSource = null;
		if (containedSourcePath != null && pathStartsWithBase(normalizedBaseClassPath, containedSourcePath)) {
			dataSource = tryLoadingFromClassPath(containedSourcePath);
		}
		if (dataSource == null && allowOutsideBaseClassPath && normalizedSourcePath != null) {
			dataSource = tryLoadingFromClassPath(normalizedSourcePath);
		}
		return dataSource;
	}

	@Nullable
	public static DataSource tryResolveUrlDataSource(@Nullable final URL baseUrl, final boolean allowOutsideBaseUrl, @NotNull final String srcLocation)
			throws IOException {
		if (baseUrl == null) {
			return isCorrectlyFormattedUrl(srcLocation)
					? tryLoadingFromUrl(new URL(srcLocation), null, true)
					: null;
		}

		final URL sourceUrl;
		if (isCorrectlyFormattedUrl(srcLocation)) {
			sourceUrl = new URL(srcLocation);
		} else {
			sourceUrl = resolveAgainstBaseUrl(baseUrl, srcLocation);
		}

		if (!allowOutsideBaseUrl && !urlIsWithinBase(baseUrl, sourceUrl)) {
			return null;
		}
		return tryLoadingFromUrl(sourceUrl, baseUrl, allowOutsideBaseUrl);
	}

	@Nullable
	private static DataSource tryLoadingContainedFile(@NotNull final Path realBasePath, @NotNull final Path sourcePath, final boolean allowOutsideBaseDir) {
		try {
			final Path realSourcePath = sourcePath.toRealPath();
			if (!allowOutsideBaseDir && !realSourcePath.startsWith(realBasePath)) {
				return null;
			}
			return tryLoadingFromDisk(realSourcePath.toFile());
		} catch (IOException | InvalidPathException e) {
			return null;
		}
	}

	@Nullable
	private static DataSource tryLoadingFromDiskWithoutBase(@NotNull final String srcLocation) {
		DataSource dataSource = tryLoadingFromDisk(new File(srcLocation));
		if (dataSource == null) {
			dataSource = tryLoadingFromDisk(new File(".", srcLocation));
		}
		return dataSource;
	}

	@Nullable
	private static DataSource tryLoadingFromDisk(@NotNull final File srcLocation) {
		if (srcLocation.exists()) {
			final FileDataSource fileDataSource = new FileDataSource(srcLocation);
			fileDataSource.setFileTypeMap(ImageMimeType.IMAGE_MIMETYPES_FILE_TYPE_MAP);
			return fileDataSource;
		}
		return null;
	}

	@Nullable
	private static DataSource tryLoadingFromClassPath(final @NotNull String resourceName)
			throws IOException {
		final String cleanResourceName = resourceName.replaceAll("//", "/");
		final InputStream is = MiscUtil.class.getResourceAsStream(cleanResourceName);

		if (is != null) {
			try {
				final String mimeType = ImageMimeType.getContentType(resourceName);
				final ByteArrayDataSource ds = new ByteArrayDataSource(is, mimeType);
				// EMAIL-125: set the name of the DataSource to the normalized resource URL similar to other DataSource implementations, e.g. FileDataSource, URLDataSource
				ds.setName(MiscUtil.class.getResource(cleanResourceName).toString());
				return ds;
			} finally {
				is.close();
			}
		}
		return null;
	}

	@Nullable
	private static DataSource tryLoadingFromUrl(@NotNull final URL sourceUrl, @Nullable final URL baseUrl, final boolean allowOutsideBaseUrl) {
		URL currentUrl = sourceUrl;
		for (int redirectCount = 0; redirectCount <= MAX_URL_REDIRECTS; redirectCount++) {
			if (baseUrl != null && !allowOutsideBaseUrl && !urlIsWithinBase(baseUrl, currentUrl)) {
				return null;
			}

			URLConnection connection = null;
			try {
				connection = currentUrl.openConnection();
				if (connection instanceof HttpURLConnection) {
					final HttpURLConnection httpConnection = (HttpURLConnection) connection;
					httpConnection.setInstanceFollowRedirects(false);
					final int responseCode = httpConnection.getResponseCode();
					if (isRedirectResponse(responseCode)) {
						final String location = httpConnection.getHeaderField("Location");
						httpConnection.disconnect();
						if (location == null || redirectCount == MAX_URL_REDIRECTS) {
							return null;
						}
						currentUrl = new URL(currentUrl, location);
						continue;
					}
				}

				final String contentType = determineUrlContentType(connection, currentUrl);
				try (InputStream inputStream = connection.getInputStream()) {
					final ByteArrayDataSource dataSource = new ByteArrayDataSource(readInputStreamToBytes(inputStream), contentType);
					dataSource.setName(currentUrl.toExternalForm());
					return dataSource;
				} finally {
					if (connection instanceof HttpURLConnection) {
						((HttpURLConnection) connection).disconnect();
					}
				}
			} catch (IOException | IllegalArgumentException e) {
				if (connection instanceof HttpURLConnection) {
					((HttpURLConnection) connection).disconnect();
				}
				return null;
			}
		}
		return null;
	}

	private static URL resolveAgainstBaseUrl(@NotNull final URL baseUrl, @NotNull final String srcLocation)
			throws IOException {
		final String basePath = baseUrl.getPath().endsWith("/") ? baseUrl.getPath() : baseUrl.getPath() + "/";
		final URL directoryBaseUrl = new URL(baseUrl.getProtocol(), baseUrl.getHost(), baseUrl.getPort(), basePath);
		return new URL(directoryBaseUrl, stripLeadingSlashes(srcLocation.replace('\\', '/')));
	}

	private static boolean urlIsWithinBase(@NotNull final URL baseUrl, @NotNull final URL sourceUrl) {
		if (!baseUrl.getProtocol().equalsIgnoreCase(sourceUrl.getProtocol())
				|| !baseUrl.getHost().equalsIgnoreCase(sourceUrl.getHost())
				|| effectivePort(baseUrl) != effectivePort(sourceUrl)) {
			return false;
		}

		final String normalizedBasePath = normalizeUrlPath(baseUrl.getPath());
		final String normalizedSourcePath = normalizeUrlPath(sourceUrl.getPath());
		return normalizedBasePath != null
				&& normalizedSourcePath != null
				&& pathStartsWithBase(normalizedBasePath, normalizedSourcePath);
	}

	private static int effectivePort(@NotNull final URL url) {
		return url.getPort() >= 0 ? url.getPort() : url.getDefaultPort();
	}

	@Nullable
	private static String normalizeUrlPath(@Nullable final String urlPath) {
		if (urlPath == null) {
			return null;
		}
		String decodedPath = urlPath.replace('\\', '/');
		try {
			for (int decodeCount = 0; decodeCount < 10; decodeCount++) {
				final String nextDecodedPath = URLDecoder.decode(decodedPath.replace("+", "%2B"), UTF_8.name()).replace('\\', '/');
				if (nextDecodedPath.equals(decodedPath)) {
					return normalizeResourcePath(nextDecodedPath);
				}
				decodedPath = nextDecodedPath;
			}
			return null;
		} catch (IllegalArgumentException | UnsupportedEncodingException e) {
			return null;
		}
	}

	@Nullable
	private static String normalizeResourcePath(@NotNull final String resourcePath) {
		final Deque<String> normalizedSegments = new ArrayDeque<>();
		for (String segment : resourcePath.replace('\\', '/').split("/+")) {
			if (segment.isEmpty() || ".".equals(segment)) {
				continue;
			}
			if ("..".equals(segment)) {
				if (normalizedSegments.isEmpty()) {
					return null;
				}
				normalizedSegments.removeLast();
			} else {
				normalizedSegments.addLast(segment);
			}
		}
		return "/" + String.join("/", normalizedSegments);
	}

	private static boolean pathStartsWithBase(@NotNull final String basePath, @NotNull final String sourcePath) {
		final String baseWithoutTrailingSlash = basePath.length() > 1 && basePath.endsWith("/")
				? basePath.substring(0, basePath.length() - 1)
				: basePath;
		return "/".equals(baseWithoutTrailingSlash)
				|| sourcePath.equals(baseWithoutTrailingSlash)
				|| sourcePath.startsWith(baseWithoutTrailingSlash + "/");
	}

	private static String stripLeadingSlashes(@NotNull final String path) {
		return path.replaceFirst("^/+", "");
	}

	private static boolean isRedirectResponse(final int responseCode) {
		return responseCode == HttpURLConnection.HTTP_MOVED_PERM
				|| responseCode == HttpURLConnection.HTTP_MOVED_TEMP
				|| responseCode == HttpURLConnection.HTTP_SEE_OTHER
				|| responseCode == 307
				|| responseCode == 308;
	}

	private static String determineUrlContentType(@NotNull final URLConnection connection, @NotNull final URL sourceUrl) {
		final String connectionContentType = connection.getContentType();
		return connectionContentType != null ? connectionContentType : ImageMimeType.getContentType(sourceUrl.getPath());
	}

	public static boolean isCorrectlyFormattedUrl(final String srcLocation) {
		try {
			new URL(srcLocation);
			return true;
		} catch (IOException e) {
			return false;
		}
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
	
	/**
	 * @param fullMimeType the mime type from the mail api
	 * @return The real mime type
	 */
	@NotNull
	public static String parseBaseMimeType(@Nullable final String fullMimeType) {
		if (valueNullOrEmpty(fullMimeType)) {
			return "";
		}
		int end = fullMimeType.length();
		final int parameterStart = fullMimeType.indexOf(';');
		if (parameterStart >= 0) {
			end = Math.min(end, parameterStart);
		}
		final int crStart = fullMimeType.indexOf('\r');
		if (crStart >= 0) {
			end = Math.min(end, crStart);
		}
		final int lfStart = fullMimeType.indexOf('\n');
		if (lfStart >= 0) {
			end = Math.min(end, lfStart);
		}
		return fullMimeType.substring(0, end).trim();
	}

	@NotNull
	public static String parseBaseMimeTypeOrDefault(@Nullable final String fullMimeType) {
		final String baseMimeType = parseBaseMimeType(fullMimeType);
		return isValidMimeType(baseMimeType) ? baseMimeType : DEFAULT_CONTENT_TYPE;
	}

	private static boolean isValidMimeType(final String contentType) {
		if (valueNullOrEmpty(contentType)) {
			return false;
		}
		try {
			final ContentType parsedContentType = new ContentType(contentType);
			return !valueNullOrEmpty(parsedContentType.getPrimaryType()) && !valueNullOrEmpty(parsedContentType.getSubType());
		} catch (final ParseException e) {
			return false;
		}
	}

	@Nullable
	public static <Out> Out overrideOrProvideOrDefaultProperty(@Nullable Email provided, @Nullable Email defaults, @Nullable Email overrides, @NotNull EmailProperty emailProperty) {
		return ofNullable(overrideAllowedForProperty(provided, emailProperty) ? overrides : null)
				.map(emailProperty.<Out>getGetter())
				.orElse(ofNullable(provided)
						.map(emailProperty.<Out>getGetter())
						.orElse(ofNullable(defaultAllowedForProperty(provided, emailProperty) ? defaults : null)
								.map(emailProperty.<Out>getGetter())
								.orElse(null)));
	}

	@NotNull
	public static <Out> List<Out> overrideAndOrProvideAndOrDefaultCollection(@Nullable Email provided, @Nullable Email defaults, @Nullable Email overrides, @NotNull EmailProperty emailProperty) {
		val listOut = new ArrayList<Out>();
		if (overrides != null && overrideAllowedForProperty(provided, emailProperty)) {
			listOut.addAll(emailProperty.<Collection<Out>>getGetter().apply(overrides));
		}
		if (provided != null) {
			listOut.addAll(emailProperty.<Collection<Out>>getGetter().apply(provided));
		}
		if (defaults != null && defaultAllowedForProperty(provided, emailProperty)) {
			listOut.addAll(emailProperty.<Collection<Out>>getGetter().apply(defaults));
		}
		return listOut;
	}

	@NotNull
	public static Map<String, Collection<String>> overrideAndOrProvideAndOrDefaultHeaders(@Nullable Email provided, @Nullable Email defaults, @Nullable Email overrides) {
		val collectedHeaders = new HashMap<String, Collection<String>>();

		if (defaults != null && defaultAllowedForProperty(provided, EmailProperty.HEADERS)) {
			addOrOverrideHeaders(collectedHeaders, defaults.getHeaders());
		}
		if (provided != null) {
			addOrOverrideHeaders(collectedHeaders, provided.getHeaders());
		}
		if (overrides != null && overrideAllowedForProperty(provided, EmailProperty.HEADERS)) {
			addOrOverrideHeaders(collectedHeaders, overrides.getHeaders());
		}

		return collectedHeaders;
	}

	private static boolean defaultAllowedForProperty(@Nullable Email provided, @NotNull final EmailProperty emailProperty) {
		return provided == null || !provided.isIgnoreDefaults() &&
				(provided.getPropertiesNotToApplyDefaultValueFor() == null ||
						!provided.getPropertiesNotToApplyDefaultValueFor().contains(emailProperty));
	}

	private static boolean overrideAllowedForProperty(@Nullable Email provided, @NotNull final EmailProperty emailProperty) {
		return provided == null || !provided.isIgnoreOverrides() &&
				(provided.getPropertiesNotToApplyOverrideValueFor() == null ||
						!provided.getPropertiesNotToApplyOverrideValueFor ().contains(emailProperty));
	}

	private static void addOrOverrideHeaders(HashMap<String, Collection<String>> collectedHeaders, @NotNull Map<String, Collection<String>> headers) {
		headers.forEach((headerKey, headerValues) -> {
			collectedHeaders.putIfAbsent(headerKey, new ArrayList<>());
			/*
				we don't merge header values that have the same key from defaults or overrides;
				instead, we assume the use will always want to override the entire header
			 */
			collectedHeaders.get(headerKey).clear();
			collectedHeaders.get(headerKey).addAll(headerValues);
		});
	}

	@SneakyThrows
	public static void assignToInstanceField(Object subject, String fieldName, Object newValue) {
		Field field = subject.getClass().getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(subject, newValue);
	}

	@NotNull
	public static List<InternetAddress> asInternetAddresses(@NotNull List<Recipient> recipient, @NotNull Charset charset) {
		return recipient.stream()
				.map(r -> asInternetAddress(r, charset))
				.collect(Collectors.toList());
	}

	@NotNull
	@SneakyThrows
	public static InternetAddress asInternetAddress(@NotNull Recipient recipient, @NotNull Charset charset) {
		return new InternetAddress(recipient.getAddress(), recipient.getName(), charset.name());
	}

	@NotNull
	public static Optional<String> findFirstMatch(@NotNull Pattern pattern, @NotNull String input) {
		Matcher matcher = pattern.matcher(input);
		return matcher.find() ? Optional.of(matcher.group(1)) : Optional.empty();
	}
}
