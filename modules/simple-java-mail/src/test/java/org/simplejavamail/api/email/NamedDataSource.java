/*
 * Copyright (C) 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.api.email;

import org.jetbrains.annotations.Nullable;

import javax.activation.DataSource;
import javax.mail.EncodingAware;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.simplejavamail.internal.util.MiscUtil.valueNullOrEmpty;

/**
 * Allows given datasource to be renamed (for {@code javax.activation.DataHandler} perspective).
 */
class NamedDataSource implements DataSource, EncodingAware {

	/**
	 * Original data source used for attachment.
	 */
	private final DataSource dataSource;

	/**
	 * The new name (optional), which will be applied as email attachment.
	 */
	private final String name;

	/**
	 * Used for wrapping data source in parameter. Method {@link NamedDataSource#getName()} will
	 * not use the original name, but it will use the name in the parameter instead (if provided).
	 *
	 * @param dataSource wrapped data source
	 * @param name       new name of data source
	 */
	public NamedDataSource(final String name, final DataSource dataSource) {
		this.dataSource = dataSource;
		this.name = name;
	}

	/**
	 * @return {@link DataSource#getInputStream()}
	 */
	@Override
	public InputStream getInputStream()
			throws IOException {
		return dataSource.getInputStream();
	}

	/**
	 * @return {@link DataSource#getOutputStream()}
	 */
	@Override
	public OutputStream getOutputStream()
			throws IOException {
		return dataSource.getOutputStream();
	}

	/**
	 * @return {@link DataSource#getContentType()}
	 */
	@Override
	public String getContentType() {
		return dataSource.getContentType();
	}

	/**
	 * {@link #name} if provided, {@link DataSource#getName()} of the original datasource otherwise.
	 *
	 * @return name of data source
	 */
	@Override
	public String getName() {
		return !valueNullOrEmpty(name) ? name : dataSource.getName();
	}
	
	/**
	 * Optimization to help Java Mail determine encoding for attachments.
	 *
	 * @return The encoding from the nested data source if it implements {@link EncodingAware} as well.
	 * @see <a href="https://github.com/bbottema/simple-java-mail/issues/131">Bug report #131</a>
	 */
	@Override
	@Nullable
	public String getEncoding() {
		return (this.dataSource instanceof EncodingAware) ? ((EncodingAware) this.dataSource).getEncoding() : null;
	}
}
