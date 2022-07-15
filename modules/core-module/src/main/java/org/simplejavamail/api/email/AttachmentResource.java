package org.simplejavamail.api.email;

import jakarta.activation.DataSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.MailException;
import org.simplejavamail.internal.util.MiscUtil;

import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.nio.charset.Charset;
import java.util.Objects;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * A named immutable email attachment information object. The name can be a simple name, a filename or a named embedded image (eg.
 * &lt;cid:footer&gt;). Contains a {@link DataSource} that is compatible with the jakarta.mail API.
 *
 * @see DataSource
 */
public class AttachmentResource implements Serializable {

	private static final long serialVersionUID = 1234567L;

	/**
	 * @see #AttachmentResource(String, DataSource, String)
	 */
	private final String name;

	/**
	 * @see #AttachmentResource(String, DataSource, String)
	 */
	// data source is not serializable, so transient (Kryo can do it though, see SerializationUtil in the OutlookModule)
	private transient final DataSource dataSource;

	/**
	 * @see #AttachmentResource(String, DataSource, String)
	 */
	@Nullable
	private final String description;

	/**
	 * @see #AttachmentResource(String, DataSource, String)
	 */
	@Nullable
	private final ContentTransferEncoding contentTransferEncoding;

	/**
	 * Delegates to {@link AttachmentResource#AttachmentResource(String, DataSource, String, ContentTransferEncoding)} with null-description and no forced content transfer encoding
	 */
	public AttachmentResource(@Nullable final String name, @NotNull final DataSource dataSource) {
		this(name, dataSource, null, null);
	}

	/**
	 * Delegates to {@link AttachmentResource#AttachmentResource(String, DataSource, String, ContentTransferEncoding)} with no forced content transfer encoding
	 */
	public AttachmentResource(@Nullable final String name, @NotNull final DataSource dataSource, @Nullable final String description) {
		this(name, dataSource, description, null);
	}

	/**
	 * Constructor; initializes the attachment resource with a name and data.
	 *
	 * @param name                    The name of the attachment which can be a simple name, a filename or a named embedded image (eg. &lt;cid:footer&gt;). Leave
	 *                                <code>null</code> to fall back on {@link DataSource#getName()}.
	 * @param dataSource              The attachment data. If no name was provided, the name of this datasource is used if provided.
	 * @param description             An optional description that will find its way in the MimeMEssage with the Content-Description header. This is rarely needed.
	 * @param contentTransferEncoding An optional encoder option to force the data encoding while in MimeMessage/EML format.
	 *
	 * @see DataSource
	 */
	public AttachmentResource(@Nullable final String name, @NotNull final DataSource dataSource, @Nullable final String description, @Nullable final ContentTransferEncoding contentTransferEncoding) {
		this.name = name;
		this.dataSource = checkNonEmptyArgument(dataSource, "dataSource");
		this.description = description;
		this.contentTransferEncoding = contentTransferEncoding;
	}

	/**
	 * @return The content of the datasource as UTF-8 encoded String.
	 * @throws IOException See {@link #readAllData(Charset)}
	 */
	@NotNull
	public String readAllData()
			throws IOException {
		return readAllData(UTF_8);
	}

	/**
	 * Delegates to {@link MiscUtil#readInputStreamToBytes(InputStream)} with data source input stream.
	 */
	@NotNull
	public byte[] readAllBytes()
			throws IOException {
		return MiscUtil.readInputStreamToBytes(getDataSourceInputStream());
	}

	/**
	 * Delegates to {@link MiscUtil#readInputStreamToString(InputStream, Charset)} with data source input stream.
	 */
	@SuppressWarnings({"WeakerAccess" })
	@NotNull
	public String readAllData(@SuppressWarnings("SameParameterValue") @NotNull final Charset charset)
			throws IOException {
		checkNonEmptyArgument(charset, "charset");
		return MiscUtil.readInputStreamToString(getDataSourceInputStream(), charset);
	}

	/**
	 * Delegates to {@link DataSource#getInputStream}
	 */
	@NotNull
	public InputStream getDataSourceInputStream() {
		try {
			return dataSource.getInputStream();
		} catch (IOException e) {
			throw new AttachmentResourceException("Error getting input stream from attachment's data source", e);
		}
	}

	/**
	 * @see #AttachmentResource(String, DataSource, String)
	 */
	@NotNull
	public DataSource getDataSource() {
		return dataSource;
	}

	/**
	 * @see #AttachmentResource(String, DataSource, String)
	 */
	@Nullable
	public String getName() {
		return name;
	}

	/**
	 * @see #AttachmentResource(String, DataSource, String)
	 */
	@Nullable
	public String getDescription() {
		return description;
	}

	/**
	 * @see #AttachmentResource(String, DataSource, String)
	 */
	@Nullable
	public ContentTransferEncoding getContentTransferEncoding() {
		return contentTransferEncoding;
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
				Objects.equals(contentTransferEncoding, that.contentTransferEncoding);
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
				"\n\t}";
	}

	private static class AttachmentResourceException extends MailException {
		protected AttachmentResourceException(final String message, final Throwable cause) {
			super(message, cause);
		}
	}
}