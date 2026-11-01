package org.simplejavamail.internal.clisupport;

/** A packaged CLI resource cannot be used; ordinary startup never repairs it. */
@SuppressWarnings("serial")
public final class CliMetadataException extends IllegalStateException {
    public CliMetadataException(final String resource, final Throwable cause) {
        super("CLI metadata '" + resource + "' cannot be used. Reinstall Simple Java Mail, or rebuild the CLI with Maven when working from source. "
                + (cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage()), cause);
    }
}
