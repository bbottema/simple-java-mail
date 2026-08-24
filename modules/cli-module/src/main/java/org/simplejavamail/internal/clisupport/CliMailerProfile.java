package org.simplejavamail.internal.clisupport;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedOptionData;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.Certificate;
import java.security.cert.CertificateEncodingException;
import java.time.temporal.TemporalAccessor;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/**
 * Decides whether two CLI requests may safely share one daemon-owned Mailer.
 * The profile canonicalizes conventional configuration and explicit Mailer options, includes content fingerprints for
 * security files, and HMACs the result with a daemon-local key. Unknown mutable inputs deliberately produce unique
 * profiles; raw configuration and canonical bytes are never retained or exposed through diagnostics.
 */
public final class CliMailerProfile {
	private static final byte[] ONE_SHOT_KEY = "simple-java-mail-one-shot-profile".getBytes(StandardCharsets.UTF_8);

	private final byte[] lookupDigest;
	private final int hashCode;

	private CliMailerProfile(final byte[] lookupDigest) {
		this.lookupDigest = lookupDigest;
		this.hashCode = Arrays.hashCode(lookupDigest);
	}

	/** Uses the current working directory when resolving security-file configuration. */
	public static CliMailerProfile create(@NotNull final SimpleJavaMailConfig config,
			@NotNull final Collection<CliReceivedOptionData> receivedOptions,
			final byte[] daemonLocalKey) {
		return create(config, receivedOptions, daemonLocalKey, Path.of("").toAbsolutePath());
	}

	/**
	 * Produces an opaque cache key without retaining the configuration it represents.
	 * A {@code null} daemon key selects the stable one-shot key; relative security files are fingerprinted against the
	 * configuration working directory so identical path text with different content cannot share a Mailer.
	 */
	public static CliMailerProfile create(@NotNull final SimpleJavaMailConfig config,
			@NotNull final Collection<CliReceivedOptionData> receivedOptions,
			final byte[] daemonLocalKey, @NotNull final Path configurationWorkingDirectory) {
		try {
			final byte[] canonicalInputs = canonicalMailerInputs(config, receivedOptions, configurationWorkingDirectory);
			return new CliMailerProfile(hmac(daemonLocalKey == null ? ONE_SHOT_KEY : daemonLocalKey, canonicalInputs));
		} catch (IOException e) {
			throw new IllegalStateException("Unable to derive Mailer identity", e);
		}
	}

	private static byte[] canonicalMailerInputs(final SimpleJavaMailConfig config,
			final Collection<CliReceivedOptionData> receivedOptions, final Path configurationWorkingDirectory)
			throws IOException {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (DataOutputStream output = new DataOutputStream(bytes)) {
			config.asMap().entrySet().stream()
					.sorted(Comparator.comparing(entry -> entry.getKey().name()))
					.forEach(entry -> writeConfigEntry(output, entry, configurationWorkingDirectory));
			for (final CliReceivedOptionData option : receivedOptions) {
				if (option.determineTargetBuilderApi() == CliBuilderApiType.MAILER) {
					writeString(output, option.getDeclaredOptionSpec().getSourceMethod().toGenericString());
					for (final Object value : option.getProvidedOptionValues()) {
						writeValue(output, value);
					}
				}
			}
		}
		return bytes.toByteArray();
	}

	private static void writeConfigEntry(final DataOutputStream output, final Map.Entry<ConfigLoader.Property, Object> entry,
			final Path configurationWorkingDirectory) {
		try {
			writeString(output, entry.getKey().name());
			writeValue(output, entry.getValue());
			if (entry.getValue() instanceof String pathValue && isSecurityFileProperty(entry.getKey())) {
				writeSecurityFileFingerprint(output, configurationWorkingDirectory, pathValue);
			}
		} catch (IOException e) {
			throw new IllegalStateException("Unable to derive Mailer identity", e);
		}
	}

	private static void writeSecurityFileFingerprint(final DataOutputStream output, final Path workingDirectory,
			final String pathValue) throws IOException {
		try {
			final Path raw = Path.of(pathValue);
			final Path resolved = (raw.isAbsolute() ? raw : workingDirectory.resolve(raw)).normalize();
			if (Files.isRegularFile(resolved)) {
				output.writeBoolean(true);
				writeBytes(output, sha256(resolved));
				return;
			}
		} catch (RuntimeException ignored) {
			// Inline key/certificate data is already part of the canonical value.
		}
		output.writeBoolean(false);
	}

	private static boolean isSecurityFileProperty(final ConfigLoader.Property property) {
		return property == ConfigLoader.Property.SMIME_SIGNING_KEYSTORE
				|| property == ConfigLoader.Property.SMIME_ENCRYPTION_CERTIFICATE
				|| property == ConfigLoader.Property.DKIM_PRIVATE_KEY_FILE_OR_DATA;
	}

	private static void writeValue(final DataOutputStream output, final Object value) throws IOException {
		if (value == null) {
			output.writeByte(0);
			return;
		}
		output.writeByte(1);
		writeString(output, value.getClass().getName());
		if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean
				|| value instanceof Character || value instanceof Enum<?> || value instanceof UUID
				|| value instanceof TemporalAccessor || value instanceof Date) {
			writeString(output, String.valueOf(value));
		} else if (value instanceof File file) {
			writeFile(output, file);
		} else if (value instanceof Certificate certificate) {
			try {
				writeBytes(output, certificate.getEncoded());
			} catch (CertificateEncodingException e) {
				throw new IOException("Unable to fingerprint certificate", e);
			}
		} else if (value instanceof byte[] bytes) {
			writeBytes(output, bytes);
		} else if (value instanceof InputStream) {
			// Input streams are consumed by builders and cannot safely be probed twice. Treat this request as unique.
			writeString(output, UUID.randomUUID().toString());
		} else if (value instanceof ConnectionPoolClusterConfig cluster) {
			writeValue(output, cluster.getCoreSize());
			writeValue(output, cluster.getMaxSize());
			writeValue(output, cluster.getClaimTimeoutMillis());
			writeValue(output, cluster.getExpireAfterMillis());
			writeValue(output, cluster.getLoadBalancingStrategy());
		} else if (value instanceof Map<?, ?> map) {
			output.writeInt(map.size());
			map.entrySet().stream()
					.sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
					.forEach(entry -> {
						try {
							writeValue(output, entry.getKey());
							writeValue(output, entry.getValue());
						} catch (IOException e) {
							throw new IllegalStateException(e);
						}
					});
		} else if (value instanceof Collection<?> collection) {
			output.writeInt(collection.size());
			for (final Object item : collection) {
				writeValue(output, item);
			}
		} else if (value.getClass().isArray()) {
			final int length = Array.getLength(value);
			output.writeInt(length);
			for (int i = 0; i < length; i++) {
				writeValue(output, Array.get(value, i));
			}
		} else {
			// Unknown mutable/custom inputs are deliberately non-reusable rather than guessed compatible.
			writeString(output, UUID.randomUUID().toString());
		}
	}

	private static void writeFile(final DataOutputStream output, final File file) throws IOException {
		final File resolved = file.getAbsoluteFile();
		writeString(output, resolved.toPath().normalize().toString());
		if (resolved.isFile()) {
			writeBytes(output, sha256(resolved.toPath()));
		} else {
			output.writeInt(-1);
		}
	}

	private static void writeString(final DataOutputStream output, final String value) throws IOException {
		writeBytes(output, value.getBytes(StandardCharsets.UTF_8));
	}

	private static void writeBytes(final DataOutputStream output, final byte[] value) throws IOException {
		output.writeInt(value.length);
		output.write(value);
	}

	private static byte[] hmac(final byte[] key, final byte[] value) {
		try {
			final Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(key, "HmacSHA256"));
			return mac.doFinal(value);
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("HmacSHA256 unavailable", e);
		}
	}

	private static byte[] sha256(final Path path) throws IOException {
		final MessageDigest digest = newSha256Digest();
		try (InputStream input = Files.newInputStream(path)) {
			final byte[] buffer = new byte[8192];
			for (int read; (read = input.read(buffer)) >= 0;) {
				digest.update(buffer, 0, read);
			}
		}
		return digest.digest();
	}

	private static MessageDigest newSha256Digest() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	@Override
	public boolean equals(final Object other) {
		return this == other || other instanceof CliMailerProfile profile
				&& MessageDigest.isEqual(lookupDigest, profile.lookupDigest);
	}

	@Override
	public int hashCode() {
		return hashCode;
	}

	@Override
	public String toString() {
		return "CliMailerProfile{<redacted>}";
	}

}
