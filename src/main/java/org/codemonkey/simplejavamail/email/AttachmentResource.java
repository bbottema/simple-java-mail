package org.codemonkey.simplejavamail.email;

import javax.activation.DataSource;

/**
 * A named immutable email attachment information object. The name can be a simple name, a filename or a named embedded image (eg. &lt;cid:footer&gt;). Contains
 * a {@link DataSource} that is compatible with the javax.mail API.
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
	 * @param name       The name of the attachment which can be a simple name, a filename or a named embedded image (eg. &lt;cid:footer&gt;)
	 * @param dataSource The attachment data.
	 * @see DataSource
	 */
	public AttachmentResource(final String name, final DataSource dataSource) {
		this.name = name;
		this.dataSource = dataSource;
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
	public String getName() {
		return name;
	}

	@Override
	public int hashCode() {
		return 0;
	}

	@Override
	public boolean equals(Object o) {
		return (this == o) || (o != null && getClass() == o.getClass() &&
				EqualsHelper.equalsAttachmentResource(this, (AttachmentResource) o));
	}

	@Override
	public String toString() {
		return "AttachmentResource{" +
				"\n\t\tname='" + name + '\'' +
				",\n\t\tdataSource.name=" + dataSource.getName() +
				",\n\t\tdataSource.getContentType=" + dataSource.getContentType() +
				"\n\t}";
	}
}