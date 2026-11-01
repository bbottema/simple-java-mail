package org.simplejavamail.email.internal;

import jakarta.activation.DataSource;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.internal.util.MiscUtil;

import java.nio.file.Path;

import static java.util.Objects.requireNonNull;

/**
 * Filesystem ownership for one synchronous CLI operation, including its nested conversion and governed Email builds.
 * Raw configuration choices stay intact for validation; only filesystem consumption uses these directories.
 */
@ApiStatus.Internal
public final class ImageFileResolutionContext implements AutoCloseable {
    private static final ThreadLocal<ImageFileResolutionContext> CURRENT = new ThreadLocal<>();

    private final Path requestDirectory;
    private final Path configurationDirectory;
    private final ImageFileResolutionContext previous;
    private boolean closed;

    private ImageFileResolutionContext(final Path requestDirectory, final Path configurationDirectory) {
        this.requestDirectory = requireNonNull(requestDirectory, "requestDirectory").toAbsolutePath().normalize();
        this.configurationDirectory = requireNonNull(configurationDirectory, "configurationDirectory").toAbsolutePath().normalize();
        this.previous = CURRENT.get();
    }

    /** Installs operation-local directories without changing the process working directory or retained builder state. */
    @NotNull
    public static ImageFileResolutionContext install(@NotNull final Path requestDirectory, @NotNull final Path configurationDirectory) {
        final ImageFileResolutionContext context = new ImageFileResolutionContext(requestDirectory, configurationDirectory);
        CURRENT.set(context);
        return context;
    }

    @Nullable
    static DataSource resolveFile(@Nullable final String baseDirectory, final boolean allowOutsideBaseDirectory, @NotNull final String source,
            final boolean configurationOwnsBaseDirectory) {
        final ImageFileResolutionContext context = CURRENT.get();
        if (context == null) {
            return MiscUtil.tryResolveImageFileDataSourceFromDisk(baseDirectory, allowOutsideBaseDirectory, source);
        }
        final Path baseOwner = configurationOwnsBaseDirectory ? context.configurationDirectory : context.requestDirectory;
        // A configured base also owns already-prefixed sources and its permitted outside-base fallback.
        final Path sourceOwner = baseDirectory == null ? context.requestDirectory : baseOwner;
        return MiscUtil.tryResolveImageFileDataSourceFromDisk(baseDirectory, allowOutsideBaseDirectory, source,
                baseOwner, sourceOwner);
    }

    /** Removes only this operation's context, including after construction, validation or sending fails. */
    @Override
    public void close() {
        if (!closed) {
            if (CURRENT.get() != this) {
                throw new IllegalStateException("Image file resolution contexts were closed out of order");
            }
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            closed = true;
        }
    }
}
