package org.simplejavamail.email;

import org.simplejavamail.internal.util.MiscUtil;

import javax.activation.DataSource;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.Charset;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * A named immutable email attachment information object. The name can be a simple name, a filename or a named embedded image (eg.
 * &lt;cid:footer&gt;). Contains a {@link DataSource} that is compatible with the javax.mail API.
 *
 * @author Benny Bottema
 * @see DataSource
 */
public class AttachmentResource {

	/**
	 * @see #AttachmentResource(String, DataSource)
	 */
	private final String name;

	/**
	 * @see #AttachmentResource(String, DataSource)
	 */
	private final DataSource dataSource;

	/**
	 * Constructor; initializes the attachment resource with a name and data.
	 *
	 * @param name       The name of the attachment which can be a simple name, a filename or a named embedded image (eg. &lt;cid:footer&gt;). Leave
	 *                   <code>null</code> to fall back on {@link DataSource#getName()}.
	 * @param dataSource The attachment data. If no name was provided, the name of this datasource is used if provided.
	 * @see DataSource
	 */
	public AttachmentResource(@Nullable final String name, @Nonnull final DataSource dataSource) {
		this.name = name;
		this.dataSource = checkNonEmptyArgument(dataSource, "dataSource");
	}

	/**
	 * @return The content of the datasource as UTF-8 encoded String.
	 * @throws IOException See {@link #readAllData(Charset)}
	 */
	@Nonnull
	public String readAllData()
			throws IOException {
		return readAllData(UTF_8);
	}

	/**
	 * @return The content of the datasource as String, using IOUtils#toByteArray.
	 * @throws IOException See {@link #readAllData(Charset)}
	 */
	@SuppressWarnings("WeakerAccess")
	@Nonnull
	public String readAllData(@SuppressWarnings("SameParameterValue") @Nonnull final Charset charset)
			throws IOException {
		checkNonEmptyArgument(charset, "charset");
		return MiscUtil.readInputStreamToString(dataSource.getInputStream(), charset);
	}

	/**
	 * @return {@link #dataSource}
	 */
	public DataSource getDataSource() {
		return dataSource;
	}

	/**
	 * @return {@link #name}
	 */
	@Nullable
	public String getName() {
		return name;
	}

	@Override
	public int hashCode() {
		return 0;
	}

	@Override
	public boolean equals(final Object o) {
		return (this == o) || (o != null && getClass() == o.getClass() &&
				EqualsHelper.equalsAttachmentResource(this, (AttachmentResource) o));
	}

	@Override
	@Nonnull
	public String toString() {
		return "AttachmentResource{" +
				"\n\t\tname='" + name + '\'' +
				",\n\t\tdataSource.name=" + dataSource.getName() +
				",\n\t\tdataSource.getContentType=" + dataSource.getContentType() +
				"\n\t}";
	}
}