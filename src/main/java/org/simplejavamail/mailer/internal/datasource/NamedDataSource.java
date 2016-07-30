package org.simplejavamail.mailer.internal.datasource;

import javax.activation.DataSource;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Data source used to fix bug, when user try to use different name for file in {@link javax.activation.FileDataSource},
 * than the actual name is.
 *
 * @author Lukas Kosina
 * @see DataSource
 * @see javax.activation.FileDataSource
 */
public class NamedDataSource implements DataSource {

    /**
     * Original data source used for attachment
     */
    private final DataSource dataSource;
    /**
     * The new name, which will be applied as email attachment
     */
    private final String name;

    /**
     * Constructor. Used for wrapping data source in parameter. Method {@link NamedDataSource#getName()} will
     * use original name of the data source
     *
     * @param dataSource wrapped data source
     */
    public NamedDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
        this.name = null;
    }

    /**
     * Constructor. Used for wrapping data source in parameter. Method {@link NamedDataSource#getName()} will
     * not use the original name, but it will use the name in the parameter instead.
     *
     * @param dataSource wrapped data source
     * @param name       new name of data source
     */
    public NamedDataSource(String name, DataSource dataSource) {
        this.dataSource = dataSource;
        this.name = name;
    }

    /**
     * Return the input stream from {@link #dataSource}
     *
     * @return input stream
     * @throws IOException if exception occurs during getting input stream from {@link #dataSource}
     */
    @Override
    public InputStream getInputStream() throws IOException {
        return dataSource.getInputStream();
    }

    /**
     * Return the output stream from {@link #dataSource}
     *
     * @return output stream
     * @throws IOException if exception occurs during getting output stream from {@link #dataSource}
     */
    @Override
    public OutputStream getOutputStream() throws IOException {
        return dataSource.getOutputStream();
    }

    /**
     * Return the original content type from {@link #dataSource}
     *
     * @return content type of data source
     */
    @Override
    public String getContentType() {
        return dataSource.getContentType();
    }

    /**
     * If parameter {@link #name} is set, then return the value of parameter name. Otherwise, return the name of the
     * {@link #dataSource}
     *
     * @return name of data source
     */
    @Override
    public String getName() {
        return name != null ? name : dataSource.getName();
    }
}
