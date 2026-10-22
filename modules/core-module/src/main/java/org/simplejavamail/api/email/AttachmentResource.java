package org.simplejavamail.api.email;

import jakarta.activation.DataSource;
import jakarta.mail.EncodingAware;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;
import org.simplejavamail.api.mailer.MailFailureKind;
import org.simplejavamail.internal.util.MiscUtil;
import org.simplejavamail.internal.util.AttachmentDataSources;

import java.io.IOException;
import java.io.InputStream;
import java.io.InvalidObjectException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamException;
import java.io.OutputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.internal.util.Preconditions.verifyNonnull;

/**
 * A named immutable email attachment information object. The name can be a simple name, a filename or a named embedded image (eg.
 * &lt;cid:footer&gt;). Contains a {@link DataSource} that is compatible with the jakarta.mail API.
 * <p>MIME conversion preserves valid content-type parameters such as {@code text/plain; charset=ISO-8859-1}. The selected resource
 * filename remains authoritative when rendering; source naming parameters cannot override it. Eager and lazy import preserve equivalent
 * type metadata without changing the attachment bytes.</p>
 *
 * <p>Since 9.2.0, Java serialization reads and closes the data source's input stream and stores its bytes, name, content type and optional
 * {@link EncodingAware} value. Deserialization returns a repeatable, read-only, byte-backed data source. It deliberately does not restore the
 * original data-source class or any custom lazy loading, caching, network access, write support or other implementation-specific behavior.</p>
 *
 * <p>Serialized forms created before 9.2.0 contain this resource's metadata but no data-source content. They can still be inspected, but attempting
 * to read or send the unavailable content fails with an exception that identifies the legacy format.</p>
 *
 * @see DataSource
 */
public class AttachmentResource implements Serializable {

	private static final long serialVersionUID = 1234567L;
	private static final int SERIALIZATION_FORMAT_VERSION = 1;
	private static final String LEGACY_DATA_UNAVAILABLE_MESSAGE =
			"Attachment data is unavailable because this Email was serialized by Simple Java Mail before 9.2.0";
	private final int serializationFormatVersion;

	/**
	 * @see Builder#withName(String)
	 */
	private final String name;

	/**
	 * @see Builder#withDataSource(DataSource)
	 */
	private transient DataSource dataSource;

	/**
	 * @see Builder#withDescription(String)
	 */
	@Nullable
	private final String description;

	/**
	 * @see Builder#withContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	private final ContentTransferEncoding contentTransferEncoding;

	/**
	 * @see Builder#withPreEncodedContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	private final ContentTransferEncoding preEncodedContentTransferEncoding;

	/**
	 * @see Builder#withContentId(String)
	 */
	@Nullable
	private final String contentId;

	/** Creates a builder for attachment or embedded-image content and named MIME metadata. */
	@NotNull
	public static Builder builder() {
		return new Builder();
	}

	/** Copies this resource's metadata while retaining its borrowed or owned content source without reading it. */
	@NotNull
	public Builder toBuilder() {
		return new Builder().withDataSource(getDataSource()).withName(name).withDescription(description)
				.withContentId(contentId).withContentTransferEncoding(contentTransferEncoding)
				.withPreEncodedContentTransferEncoding(preEncodedContentTransferEncoding);
	}

	private AttachmentResource(final Builder builder) {
		if (builder.contentTransferEncoding != null && builder.preEncodedContentTransferEncoding != null) {
			throw new IllegalArgumentException("Choose either contentTransferEncoding or preEncodedContentTransferEncoding, not both");
		}
		this.serializationFormatVersion = SERIALIZATION_FORMAT_VERSION;
		this.dataSource = builder.resolveDataSource();
		this.name = builder.name == null && builder.path != null ? dataSource.getName() : builder.name;
		this.description = builder.description;
		this.contentTransferEncoding = builder.contentTransferEncoding;
		this.contentId = builder.contentId;
		this.preEncodedContentTransferEncoding = builder.preEncodedContentTransferEncoding;
	}

	/**
	 * Collects content and optional MIME metadata. Setting one content representation replaces the previous representation;
	 * metadata choices remain unchanged. Construction never opens streams or probes files.
	 * <p>Normal transfer encoding and already encoded content are distinct choices and cannot both be supplied.</p>
	 */
	public static final class Builder {
		private String name;
		private DataSource dataSource;
		private byte[] data;
		private String mimetype;
		private Path path;
		private String description;
		private ContentTransferEncoding contentTransferEncoding;
		private ContentTransferEncoding preEncodedContentTransferEncoding;
		private String contentId;

		private Builder() { }

		/**
		 * Borrows the supplied source by identity without reading its metadata or content. The application must keep it available
		 * and stable while rendering, sending or serializing. Replaces any previously selected bytes or path, retaining MIME metadata.
		 * @param dataSource The repeatable content source; must not be null.
		 * @return This builder.
		 */
		@NotNull
		public Builder withDataSource(@NotNull final DataSource dataSource) {
			checkNonEmptyArgument(dataSource, "dataSource");
			clearContent();
			this.dataSource = dataSource;
			return this;
		}

		/** @see #withDataSource(DataSource) */
		@Nullable
		public DataSource getDataSource() { return dataSource; }

		/** @see #withDataSource(DataSource) */
		@NotNull
		public Builder clearDataSource() { dataSource = null; return this; }

		/**
		 * Copies the supplied bytes immediately and retains their MIME type. Subsequent array changes do not affect this builder
		 * or built resources. Replaces a borrowed source or path, retaining MIME metadata. Empty content is permitted.
		 * @param data The content bytes; must not be null.
		 * @param mimetype The MIME content type, including valid parameters such as {@code text/plain; charset=ISO-8859-1}; must not be empty.
		 * @return This builder.
		 */
		@NotNull
		public Builder withData(final byte @NotNull [] data, @NotNull final String mimetype) {
			Objects.requireNonNull(data, "data");
			checkNonEmptyArgument(mimetype, "mimetype");
			clearContent();
			this.data = data.clone();
			this.mimetype = mimetype;
			return this;
		}

		/**
		 * Returns a defensive copy of supplied bytes, or null when another content representation is selected.
		 * @see #withData(byte[], String)
		 */
		public byte @Nullable [] getData() { return data == null ? null : data.clone(); }

		/** @see #withData(byte[], String) */
		@Nullable
		public String getMimetype() { return mimetype; }

		/** @see #withData(byte[], String) */
		@NotNull
		public Builder clearData() { data = null; mimetype = null; return this; }

		/**
		 * Borrows a file path lazily, replacing any other content representation while retaining MIME metadata. No filesystem
		 * inspection or content read occurs here or during build. Each consumption opens a fresh read-only stream, including on
		 * non-default filesystems. Missing files fail only when consumed; keep the filesystem open and content stable while in use.
		 * The source filename supplies the built resource's fallback name and MIME type, independently of the display name.
		 * @param path The content path; must not be null.
		 * @return This builder.
		 */
		@NotNull
		public Builder withPath(@NotNull final Path path) {
			Objects.requireNonNull(path, "path");
			clearContent();
			this.path = path;
			return this;
		}

		/** @see #withPath(Path) */
		@Nullable
		public Path getPath() { return path; }

		/** @see #withPath(Path) */
		@NotNull
		public Builder clearPath() { path = null; return this; }

		/**
		 * Sets the displayed filename or embedded-image resource name. Null (also the clear/reset value) falls back to the source name.
		 * An unnamed attachment receives a generated name during rendering. Embedded images require a name, source name or Content-ID.
		 * @param name The optional display name.
		 * @return This builder.
		 */
		@NotNull
		public Builder withName(@Nullable final String name) { this.name = name; return this; }

		/**
		 * Sets the optional MIME {@code Content-Description}. Null (also the clear/reset value) omits the description.
		 * @param description The description to render.
		 * @return This builder.
		 */
		@NotNull
		public Builder withDescription(@Nullable final String description) { this.description = description; return this; }

		/**
		 * Sets the MIME {@code Content-ID} without angle brackets, independently of the filename. HTML can reference it as {@code cid:value}.
		 * Null (also the clear/reset value) derives an embedded image's ID from its name or generates an attachment ID during rendering.
		 * @param contentId The optional Content-ID.
		 * @return This builder.
		 */
		@NotNull
		public Builder withContentId(@Nullable final String contentId) { this.contentId = contentId; return this; }

		/**
		 * Forces the MIME transfer encoding applied to ordinary, unencoded content. Null (also the clear/reset value) permits the
		 * established MIME encoding selection. Does not clear an already encoded choice; build rejects both being present.
		 * @param contentTransferEncoding The encoding to apply while rendering.
		 * @return This builder.
		 */
		@NotNull
		public Builder withContentTransferEncoding(@Nullable final ContentTransferEncoding contentTransferEncoding) {
			this.contentTransferEncoding = contentTransferEncoding; return this;
		}

		/**
		 * Declares that the supplied bytes or source already contain content encoded with this MIME transfer encoding. Rendering preserves
		 * those encoded bytes instead of encoding them again. Null (also the clear/reset value) means ordinary, unencoded input.
		 * Does not clear the normal encoding choice; build rejects both being present.
		 * @param preEncodedContentTransferEncoding The encoding already applied to the content.
		 * @return This builder.
		 */
		@NotNull
		public Builder withPreEncodedContentTransferEncoding(@Nullable final ContentTransferEncoding preEncodedContentTransferEncoding) {
			this.preEncodedContentTransferEncoding = preEncodedContentTransferEncoding; return this;
		}

		/** @see #withName(String) */
		@Nullable
		public String getName() { return name; }

		/** @see #withName(String) */
		@NotNull
		public Builder clearName() { name = null; return this; }

		/** @see #withName(String) */
		@NotNull
		public Builder resetName() { name = null; return this; }

		/** @see #withDescription(String) */
		@Nullable
		public String getDescription() { return description; }

		/** @see #withDescription(String) */
		@NotNull
		public Builder clearDescription() { description = null; return this; }

		/** @see #withDescription(String) */
		@NotNull
		public Builder resetDescription() { description = null; return this; }

		/** @see #withContentId(String) */
		@Nullable
		public String getContentId() { return contentId; }

		/** @see #withContentId(String) */
		@NotNull
		public Builder clearContentId() { contentId = null; return this; }

		/** @see #withContentId(String) */
		@NotNull
		public Builder resetContentId() { contentId = null; return this; }

		/** @see #withContentTransferEncoding(ContentTransferEncoding) */
		@Nullable
		public ContentTransferEncoding getContentTransferEncoding() { return contentTransferEncoding; }

		/** @see #withContentTransferEncoding(ContentTransferEncoding) */
		@NotNull
		public Builder clearContentTransferEncoding() { contentTransferEncoding = null; return this; }

		/** @see #withContentTransferEncoding(ContentTransferEncoding) */
		@NotNull
		public Builder resetContentTransferEncoding() { contentTransferEncoding = null; return this; }

		/** @see #withPreEncodedContentTransferEncoding(ContentTransferEncoding) */
		@Nullable
		public ContentTransferEncoding getPreEncodedContentTransferEncoding() { return preEncodedContentTransferEncoding; }

		/** @see #withPreEncodedContentTransferEncoding(ContentTransferEncoding) */
		@NotNull
		public Builder clearPreEncodedContentTransferEncoding() { preEncodedContentTransferEncoding = null; return this; }

		/** @see #withPreEncodedContentTransferEncoding(ContentTransferEncoding) */
		@NotNull
		public Builder resetPreEncodedContentTransferEncoding() { preEncodedContentTransferEncoding = null; return this; }

		/** Builds an immutable resource; content must be supplied, and normal and preencoded transfer encodings are mutually exclusive. */
		@NotNull
		public AttachmentResource build() { return new AttachmentResource(this); }

		private void clearContent() { dataSource = null; data = null; mimetype = null; path = null; }

		private DataSource resolveDataSource() {
			if (dataSource != null) { return dataSource; }
			if (path != null) { return AttachmentDataSources.fromPath(path); }
			if (data != null) { return AttachmentDataSources.byteSource().data(data).name(name).contentType(mimetype).build(); }
			throw new IllegalStateException("Attachment content must be supplied using withDataSource, withData or withPath");
		}
	}

	/**
	 * Writes a versioned, byte-backed snapshot of the data source. The concrete data-source implementation is intentionally not serialized.
	 */
	private void writeObject(@NotNull final ObjectOutputStream outputStream)
			throws IOException {
		outputStream.defaultWriteObject();
		outputStream.writeObject(DataSourceSnapshot.capture(dataSource));
	}

	/**
	 * Restores a byte-backed data source, or an unavailable placeholder for streams produced before attachment data was included.
	 */
	private void readObject(@NotNull final ObjectInputStream inputStream)
			throws IOException, ClassNotFoundException {
		inputStream.defaultReadObject();
		if (serializationFormatVersion == 0) {
			dataSource = new LegacyUnavailableDataSource(name);
			return;
		}
		if (serializationFormatVersion != SERIALIZATION_FORMAT_VERSION) {
			throw invalidSerializedForm("Unsupported AttachmentResource serialization format version: " + serializationFormatVersion, null);
		}

		final Object serializedDataSource = inputStream.readObject();
		if (!(serializedDataSource instanceof DataSourceSnapshot)) {
			throw invalidSerializedForm("Serialized AttachmentResource does not contain a data-source snapshot", null);
		}
		dataSource = ((DataSourceSnapshot) serializedDataSource).restore();
	}

	private void readObjectNoData()
			throws ObjectStreamException {
		dataSource = new LegacyUnavailableDataSource(name);
	}

	@NotNull
	private static InvalidObjectException invalidSerializedForm(@NotNull final String message, @Nullable final Exception cause) {
		final InvalidObjectException exception = new InvalidObjectException(message);
		if (cause != null) {
			exception.initCause(cause);
		}
		return exception;
	}

	/**
	 * Reads all attachment content as UTF-8 text and closes the stream opened for this call, including when reading fails.
	 * The DataSource itself remains application-owned.
	 *
	 * @return The attachment content decoded as UTF-8.
	 * @throws MailException With kind {@link MailFailureKind#PREPARATION} if opening, reading or closing the stream fails with an
	 *         {@link IOException}. The original I/O failure is retained as the cause, including any suppressed closing failure.
	 * @see #readAllData(Charset)
	 */
	@NotNull
	public String readAllData() {
		return readAllData(UTF_8);
	}

	/**
	 * Reads all attachment bytes and closes the stream opened for this call, including when reading fails.
	 * The DataSource itself remains application-owned.
	 *
	 * @return The complete attachment content.
	 * @throws MailException With kind {@link MailFailureKind#PREPARATION} if opening, reading or closing the stream fails with an
	 *         {@link IOException}. The original I/O failure is retained as the cause, including any suppressed closing failure.
	 */
	@NotNull
	public byte[] readAllBytes() {
		try (InputStream input = openStream()) {
			return MiscUtil.readInputStreamToBytes(input);
		} catch (IOException e) {
			throw new AttachmentResourceException("Error reading attachment content", e);
		}
	}

	/**
	 * Reads the attachment using the supplied charset and closes the stream opened for this call, including when reading fails.
	 * The DataSource itself remains application-owned.
	 *
	 * @param charset The charset used to decode the attachment content.
	 * @return The complete attachment content decoded with the supplied charset.
	 * @throws IllegalArgumentException If {@code charset} is {@code null}.
	 * @throws MailException With kind {@link MailFailureKind#PREPARATION} if opening, reading or closing the stream fails with an
	 *         {@link IOException}. The original I/O failure is retained as the cause, including any suppressed closing failure.
	 */
	@SuppressWarnings({"WeakerAccess" })
	@NotNull
	public String readAllData(@SuppressWarnings("SameParameterValue") @NotNull final Charset charset) {
		checkNonEmptyArgument(charset, "charset");
		try (InputStream input = openStream()) {
			return MiscUtil.readInputStreamToString(input, charset);
		} catch (IOException e) {
			throw new AttachmentResourceException("Error reading attachment content", e);
		}
	}

	/**
	 * Opens and returns the DataSource's ordinary input stream for the caller to consume and close.
	 * Opening, reading and closing retain Java's checked {@link IOException} contract. The DataSource itself remains application-owned.
	 * Use {@link #readAllBytes()} or {@link #readAllData()} when the entire content is needed without managing a stream.
	 *
	 * @return The stream supplied by the DataSource, owned by the caller.
	 * @throws IOException If the DataSource cannot open its stream.
	 */
	@NotNull
	public InputStream openStream() throws IOException {
		return getDataSource().getInputStream();
	}

	/**
	 * @see Builder#withDataSource(DataSource)
	 */
	@NotNull
	public DataSource getDataSource() {
		return verifyNonnull(dataSource);
	}

	/**
	 * @see Builder#withName(String)
	 */
	@Nullable
	public String getName() {
		return name;
	}

	/**
	 * @see Builder#withDescription(String)
	 */
	@Nullable
	public String getDescription() {
		return description;
	}

	/**
	 * @see Builder#withContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	public ContentTransferEncoding getContentTransferEncoding() {
		return contentTransferEncoding;
	}

	/**
	 * @return The transfer encoding that has already been applied to this resource's data, or {@code null} when the data should be encoded normally.
	 * @see Builder#withPreEncodedContentTransferEncoding(ContentTransferEncoding)
	 */
	@Nullable
	public ContentTransferEncoding getPreEncodedContentTransferEncoding() {
		return preEncodedContentTransferEncoding;
	}

	/**
	 * @return Optional MIME {@code Content-ID} without angle brackets.
	 * @see Builder#withContentId(String)
	 */
	@Nullable
	public String getContentId() {
		return contentId;
	}

	@SuppressWarnings("SameReturnValue")
	@Override
	public int hashCode() {
		return 0;
	}

	@Override
	public boolean equals(@Nullable Object o) {
		if (this == o) return true;
		if (o == null || getClass() != o.getClass()) return false;
		AttachmentResource that = (AttachmentResource) o;
		return Objects.equals(name, that.name) &&
				EqualsHelper.isEqualDataSource(dataSource, that.dataSource) &&
				Objects.equals(description, that.description) &&
				Objects.equals(contentTransferEncoding, that.contentTransferEncoding) &&
				Objects.equals(preEncodedContentTransferEncoding, that.preEncodedContentTransferEncoding) &&
				Objects.equals(contentId, that.contentId);
	}

	@Override
	@NotNull
	public String toString() {
		return "AttachmentResource{" +
				"\n\t\tname='" + name + "'" +
				",\n\t\tdataSource.name=" + dataSource.getName() +
				",\n\t\tdataSource.getContentType=" + dataSource.getContentType() +
				",\n\t\tdescription=" + (description != null ? "'" + description + "'" : "null") +
				",\n\t\tcontentTransferEncoding=" + (contentTransferEncoding != null ? "'" + contentTransferEncoding + "'" : "null") +
				",\n\t\tpreEncodedContentTransferEncoding=" + (preEncodedContentTransferEncoding != null ? "'" + preEncodedContentTransferEncoding + "'" : "null") +
				",\n\t\tcontentId=" + (contentId != null ? "'" + contentId + "'" : "null") +
				"\n\t}";
	}

	private static class AttachmentResourceException extends MailException {
		protected AttachmentResourceException(final String message, final Throwable cause) {
			super(MailFailureKind.PREPARATION, message, cause, null);
		}
	}

	private static final class DataSourceSnapshot implements Serializable {

		private static final long serialVersionUID = 1L;

		@NotNull
		private final byte[] data;
		@Nullable
		private final String name;
		@Nullable
		private final String contentType;
		@Nullable
		private final String encoding;

		private DataSourceSnapshot(byte @NotNull [] data, @Nullable final String name, @Nullable final String contentType, @Nullable final String encoding) {
			this.data = data.clone();
			this.name = name;
			this.contentType = contentType;
			this.encoding = encoding;
		}

		@NotNull
		private static DataSourceSnapshot capture(@NotNull final DataSource dataSource)
				throws IOException {
			final String encoding = dataSource instanceof EncodingAware
					? ((EncodingAware) dataSource).getEncoding()
					: null;
			try (InputStream inputStream = dataSource.getInputStream()) {
				return new DataSourceSnapshot(
						MiscUtil.readInputStreamToBytes(inputStream),
						dataSource.getName(),
						dataSource.getContentType(),
						encoding);
			}
		}

		@NotNull
		private DataSource restore() {
			return AttachmentDataSources.byteSource().data(data).name(name).contentType(contentType).encoding(encoding).build();
		}
	}

	private static final class LegacyUnavailableDataSource implements DataSource, EncodingAware {

		@Nullable
		private final String name;

		private LegacyUnavailableDataSource(@Nullable final String name) {
			this.name = name;
		}

		@Override
		@NotNull
		public InputStream getInputStream()
				throws IOException {
			throw new IOException(LEGACY_DATA_UNAVAILABLE_MESSAGE);
		}

		@Override
		@NotNull
		public OutputStream getOutputStream()
				throws IOException {
			throw new IOException(LEGACY_DATA_UNAVAILABLE_MESSAGE);
		}

		@Override
		@NotNull
		public String getContentType() {
			return "application/octet-stream";
		}

		@Override
		@Nullable
		public String getName() {
			return name;
		}

		@Override
		@Nullable
		public String getEncoding() {
			return null;
		}
	}
}
