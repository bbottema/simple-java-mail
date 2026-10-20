package org.simplejavamail.api.email.config;

import lombok.Value;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import java.net.URL;
import java.net.MalformedURLException;
import java.io.Serializable;

import static java.util.Objects.requireNonNull;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;

/**
 * Immutable automatic embedded-image resolution settings. Unset choices inherit the factory snapshot.
 * This value records choices without reading configuration sources or message content.
 * @see EmailPopulatingBuilder#withImageResolutionConfig(ImageResolutionConfig)
 */
@Value
public class ImageResolutionConfig implements Serializable {
    private static final long serialVersionUID = 1L;
    @Nullable Boolean embeddedImageAutoResolutionForFiles;
    @Nullable Boolean embeddedImageAutoResolutionForClassPathResources;
    @Nullable Boolean embeddedImageAutoResolutionForURLs;
    @Nullable String embeddedImageBaseDir;
    @Nullable String embeddedImageBaseClassPath;
    @Nullable URL embeddedImageBaseUrl;
    @Nullable Boolean allowEmbeddedImageOutsideBaseDir;
    @Nullable Boolean allowEmbeddedImageOutsideBaseClassPath;
    @Nullable Boolean allowEmbeddedImageOutsideBaseUrl;
    @Nullable Boolean embeddedImageAutoResolutionMustBeSuccesful;
    boolean embeddedImageBaseDirConfigured;
    boolean embeddedImageBaseClassPathConfigured;
    boolean embeddedImageBaseUrlConfigured;

    private ImageResolutionConfig(final Builder builder) {
        this.embeddedImageAutoResolutionForFiles = builder.embeddedImageAutoResolutionForFiles;
        this.embeddedImageAutoResolutionForClassPathResources = builder.embeddedImageAutoResolutionForClassPathResources;
        this.embeddedImageAutoResolutionForURLs = builder.embeddedImageAutoResolutionForURLs;
        this.embeddedImageBaseDir = builder.embeddedImageBaseDir;
        this.embeddedImageBaseClassPath = builder.embeddedImageBaseClassPath;
        this.embeddedImageBaseUrl = builder.embeddedImageBaseUrl;
        this.allowEmbeddedImageOutsideBaseDir = builder.allowEmbeddedImageOutsideBaseDir;
        this.allowEmbeddedImageOutsideBaseClassPath = builder.allowEmbeddedImageOutsideBaseClassPath;
        this.allowEmbeddedImageOutsideBaseUrl = builder.allowEmbeddedImageOutsideBaseUrl;
        this.embeddedImageAutoResolutionMustBeSuccesful = builder.embeddedImageAutoResolutionMustBeSuccesful;
        this.embeddedImageBaseDirConfigured = builder.embeddedImageBaseDirConfigured;
        this.embeddedImageBaseClassPathConfigured = builder.embeddedImageBaseClassPathConfigured;
        this.embeddedImageBaseUrlConfigured = builder.embeddedImageBaseUrlConfigured;
    }

    /** @return A builder with no supplied choices. */
    @NotNull public static Builder builder() { return new Builder(); }
    /** @return An independent builder retaining these supplied choices. */
    @NotNull public Builder toBuilder() { return new Builder(this); }

    /** @see Builder#withEmbeddedImageAutoResolutionForFiles(boolean) */
    @Nullable public Boolean getEmbeddedImageAutoResolutionForFiles() { return embeddedImageAutoResolutionForFiles; }
    /** @see Builder#withEmbeddedImageAutoResolutionForClassPathResources(boolean) */
    @Nullable public Boolean getEmbeddedImageAutoResolutionForClassPathResources() { return embeddedImageAutoResolutionForClassPathResources; }
    /** @see Builder#withEmbeddedImageAutoResolutionForURLs(boolean) */
    @Nullable public Boolean getEmbeddedImageAutoResolutionForURLs() { return embeddedImageAutoResolutionForURLs; }
    /** @see Builder#withEmbeddedImageBaseDir(String) */
    @Nullable public String getEmbeddedImageBaseDir() { return embeddedImageBaseDir; }
    /** @see Builder#withEmbeddedImageBaseDir(String) */
    public boolean isEmbeddedImageBaseDirConfigured() { return embeddedImageBaseDirConfigured; }
    /** @see Builder#withEmbeddedImageBaseClassPath(String) */
    @Nullable public String getEmbeddedImageBaseClassPath() { return embeddedImageBaseClassPath; }
    /** @see Builder#withEmbeddedImageBaseClassPath(String) */
    public boolean isEmbeddedImageBaseClassPathConfigured() { return embeddedImageBaseClassPathConfigured; }
    /** @see Builder#withEmbeddedImageBaseUrl(URL) */
    @Nullable public URL getEmbeddedImageBaseUrl() { return embeddedImageBaseUrl; }
    /** @see Builder#withEmbeddedImageBaseUrl(URL) */
    public boolean isEmbeddedImageBaseUrlConfigured() { return embeddedImageBaseUrlConfigured; }
    /** @see Builder#allowingEmbeddedImageOutsideBaseDir(boolean) */
    @Nullable public Boolean getAllowEmbeddedImageOutsideBaseDir() { return allowEmbeddedImageOutsideBaseDir; }
    /** @see Builder#allowingEmbeddedImageOutsideBaseClassPath(boolean) */
    @Nullable public Boolean getAllowEmbeddedImageOutsideBaseClassPath() { return allowEmbeddedImageOutsideBaseClassPath; }
    /** @see Builder#allowingEmbeddedImageOutsideBaseUrl(boolean) */
    @Nullable public Boolean getAllowEmbeddedImageOutsideBaseUrl() { return allowEmbeddedImageOutsideBaseUrl; }
    /** @see Builder#embeddedImageAutoResolutionMustBeSuccesful(boolean) */
    @Nullable public Boolean getEmbeddedImageAutoResolutionMustBeSuccesful() { return embeddedImageAutoResolutionMustBeSuccesful; }

    /** Collects choices; build an immutable value and supply it to the Email builder. */
    public static final class Builder {
        private Boolean embeddedImageAutoResolutionForFiles;
        private Boolean embeddedImageAutoResolutionForClassPathResources;
        private Boolean embeddedImageAutoResolutionForURLs;
        private String embeddedImageBaseDir;
        private String embeddedImageBaseClassPath;
        private URL embeddedImageBaseUrl;
        private Boolean allowEmbeddedImageOutsideBaseDir;
        private Boolean allowEmbeddedImageOutsideBaseClassPath;
        private Boolean allowEmbeddedImageOutsideBaseUrl;
        private Boolean embeddedImageAutoResolutionMustBeSuccesful;
        private boolean embeddedImageBaseDirConfigured;
        private boolean embeddedImageBaseClassPathConfigured;
        private boolean embeddedImageBaseUrlConfigured;

        private Builder() { }
        private Builder(final ImageResolutionConfig config) {
            embeddedImageAutoResolutionForFiles = config.embeddedImageAutoResolutionForFiles;
            embeddedImageAutoResolutionForClassPathResources = config.embeddedImageAutoResolutionForClassPathResources;
            embeddedImageAutoResolutionForURLs = config.embeddedImageAutoResolutionForURLs;
            embeddedImageBaseDir = config.embeddedImageBaseDir;
            embeddedImageBaseClassPath = config.embeddedImageBaseClassPath;
            embeddedImageBaseUrl = config.embeddedImageBaseUrl;
            allowEmbeddedImageOutsideBaseDir = config.allowEmbeddedImageOutsideBaseDir;
            allowEmbeddedImageOutsideBaseClassPath = config.allowEmbeddedImageOutsideBaseClassPath;
            allowEmbeddedImageOutsideBaseUrl = config.allowEmbeddedImageOutsideBaseUrl;
            embeddedImageAutoResolutionMustBeSuccesful = config.embeddedImageAutoResolutionMustBeSuccesful;
            embeddedImageBaseDirConfigured = config.embeddedImageBaseDirConfigured;
            embeddedImageBaseClassPathConfigured = config.embeddedImageBaseClassPathConfigured;
            embeddedImageBaseUrlConfigured = config.embeddedImageBaseUrlConfigured;
        }

        /**
         * Enables auto resolution of file datasources for embedded images.
         * <p>
         * Normally, you would manually mark up your HTML with images using {@code cid:<some_id>} and then add an embedded image
         * resource with the same name ({@code emailBuilder.withEmbeddedImage(..)}). With auto-file-resolution, you can just
         * refer to the file instead and the data will be included dynamically with a generated <em>cid</em>.
         * <p>
         * This option is disabled by default. Enabling it lets HTML image references read from the filesystem. When the HTML is not fully trusted, set
         * {@link #withEmbeddedImageBaseDir(String)} and leave {@link #allowingEmbeddedImageOutsideBaseDir(boolean)} disabled.
         *
         * Unset choices inherit the captured configuration. {@link #clearEmbeddedImageAutoResolutionForFiles()} restores that inheritance;
         * {@link #resetEmbeddedImageAutoResolutionForFiles()} explicitly selects the published built-in fallback.
         *
         * @param embeddedImageAutoResolutionForFiles Enables auto resolution of file datasources for embedded images.
         *
         * @see #withEmbeddedImageBaseDir(String)
         * @see #allowingEmbeddedImageOutsideBaseDir(boolean)
         */
        @NotNull public Builder withEmbeddedImageAutoResolutionForFiles(final boolean embeddedImageAutoResolutionForFiles) { this.embeddedImageAutoResolutionForFiles = embeddedImageAutoResolutionForFiles; return this; }

        /** @see #withEmbeddedImageAutoResolutionForFiles(boolean) */
        @Nullable public Boolean getEmbeddedImageAutoResolutionForFiles() { return embeddedImageAutoResolutionForFiles; }
        /** @see #withEmbeddedImageAutoResolutionForFiles(boolean) */
        @NotNull public Builder clearEmbeddedImageAutoResolutionForFiles() { embeddedImageAutoResolutionForFiles = null; return this; }
        /** @see #withEmbeddedImageAutoResolutionForFiles(boolean) */
        @NotNull public Builder resetEmbeddedImageAutoResolutionForFiles() { return withEmbeddedImageAutoResolutionForFiles(EmailPopulatingBuilder.DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_FOR_FILES); }

        /**
         * Enables auto resolution of classpath datasources for embedded images.
         * <p>
         * Normally, you would manually mark up your HTML with images using {@code cid:<some_id>} and then add an embedded image
         * resource with the same name ({@code emailBuilder.withEmbeddedImage(..)}). With auto-classpath-resolution, you can just
         * refer to the resource on the classpath instead and the data will be included dynamically with a generated <em>cid</em>.
         * <p>
         * This option is disabled by default. Enabling it lets HTML image references read classpath resources. When the HTML is not fully trusted, set
         * {@link #withEmbeddedImageBaseClassPath(String)} and leave {@link #allowingEmbeddedImageOutsideBaseClassPath(boolean)} disabled.
         *
         * Unset choices inherit the captured configuration. {@link #clearEmbeddedImageAutoResolutionForClassPathResources()} restores that inheritance;
         * {@link #resetEmbeddedImageAutoResolutionForClassPathResources()} explicitly selects the published built-in fallback.
         *
         * @param embeddedImageAutoResolutionForClassPathResources Enables auto resolution of classpath datasources for embedded images.
         *
         * @see #withEmbeddedImageBaseClassPath(String)
         * @see #allowingEmbeddedImageOutsideBaseClassPath(boolean)
         */
        @NotNull public Builder withEmbeddedImageAutoResolutionForClassPathResources(final boolean embeddedImageAutoResolutionForClassPathResources) { this.embeddedImageAutoResolutionForClassPathResources = embeddedImageAutoResolutionForClassPathResources; return this; }

        /** @see #withEmbeddedImageAutoResolutionForClassPathResources(boolean) */
        @Nullable public Boolean getEmbeddedImageAutoResolutionForClassPathResources() { return embeddedImageAutoResolutionForClassPathResources; }
        /** @see #withEmbeddedImageAutoResolutionForClassPathResources(boolean) */
        @NotNull public Builder clearEmbeddedImageAutoResolutionForClassPathResources() { embeddedImageAutoResolutionForClassPathResources = null; return this; }
        /** @see #withEmbeddedImageAutoResolutionForClassPathResources(boolean) */
        @NotNull public Builder resetEmbeddedImageAutoResolutionForClassPathResources() { return withEmbeddedImageAutoResolutionForClassPathResources(EmailPopulatingBuilder.DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_FOR_CLASSPATH_RESOURCES); }

        /**
         * Enables auto resolution of URL's for embedded images.
         * <p>
         * Normally, you would manually mark up your HTML with images using {@code cid:<some_id>} and then add an embedded image
         * resource with the same name ({@code emailBuilder.withEmbeddedImage(..)}). With auto-URL-resolution, you can just
         * refer to the hosted image instead and the data will be downloaded and included dynamically with a generated <em>cid</em>.
         * <p>
         * This option is disabled by default. Enabling it lets HTML image references make network requests. When the HTML is not fully trusted, set
         * {@link #withEmbeddedImageBaseUrl(URL)} and leave {@link #allowingEmbeddedImageOutsideBaseUrl(boolean)} disabled.
         *
         * Unset choices inherit the captured configuration. {@link #clearEmbeddedImageAutoResolutionForURLs()} restores that inheritance;
         * {@link #resetEmbeddedImageAutoResolutionForURLs()} explicitly selects the published built-in fallback.
         *
         * @param embeddedImageAutoResolutionForURLs Enables auto resolution of URL's for embedded images.
         *
         * @see #withEmbeddedImageBaseUrl(String)
         * @see #withEmbeddedImageBaseUrl(URL)
         * @see #allowingEmbeddedImageOutsideBaseUrl(boolean)
         */
        @NotNull public Builder withEmbeddedImageAutoResolutionForURLs(final boolean embeddedImageAutoResolutionForURLs) { this.embeddedImageAutoResolutionForURLs = embeddedImageAutoResolutionForURLs; return this; }

        /** @see #withEmbeddedImageAutoResolutionForURLs(boolean) */
        @Nullable public Boolean getEmbeddedImageAutoResolutionForURLs() { return embeddedImageAutoResolutionForURLs; }
        /** @see #withEmbeddedImageAutoResolutionForURLs(boolean) */
        @NotNull public Builder clearEmbeddedImageAutoResolutionForURLs() { embeddedImageAutoResolutionForURLs = null; return this; }
        /** @see #withEmbeddedImageAutoResolutionForURLs(boolean) */
        @NotNull public Builder resetEmbeddedImageAutoResolutionForURLs() { return withEmbeddedImageAutoResolutionForURLs(EmailPopulatingBuilder.DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_FOR_URLS); }

        /**
         * Sets the base folder used when resolving image sources in HTML text.
         * <p>
         * By default, a resolved file must remain below this directory after normalizing the path and following symbolic links. Without a base directory,
         * file resolution is unrestricted and may use absolute paths or paths relative to the working directory.
         *
         * Unset bases inherit the captured configuration. {@link #clearEmbeddedImageBaseDir()} explicitly removes that captured base;
         * {@link #useConfiguredEmbeddedImageBaseDir()} restores inheritance. Neither method reads image content or removes a mandatory lock.
         *
         * @param embeddedImageBaseDir The base folder used when resolving images sources in HTML text.
         *
         * @see #withEmbeddedImageAutoResolutionForFiles(boolean)
         * @see #allowingEmbeddedImageOutsideBaseDir(boolean)
         */
        @NotNull public Builder withEmbeddedImageBaseDir(@NotNull final String embeddedImageBaseDir) { this.embeddedImageBaseDir = checkNonEmptyArgument(embeddedImageBaseDir, "embeddedImageBaseDir"); embeddedImageBaseDirConfigured = true; return this; }

        /** @see #withEmbeddedImageBaseDir(String) */
        @Nullable public String getEmbeddedImageBaseDir() { return embeddedImageBaseDir; }
        /** @see #withEmbeddedImageBaseDir(String) */
        public boolean isEmbeddedImageBaseDirConfigured() { return embeddedImageBaseDirConfigured; }
        /** Removes the base explicitly, including a captured base.
     * @see #withEmbeddedImageBaseDir(String) */
        @NotNull public Builder clearEmbeddedImageBaseDir() { embeddedImageBaseDir = null; embeddedImageBaseDirConfigured = true; return this; }
        /** Restores inheritance from the captured configuration.
     * @see #withEmbeddedImageBaseDir(String) */
        @NotNull public Builder useConfiguredEmbeddedImageBaseDir() { embeddedImageBaseDir = null; embeddedImageBaseDirConfigured = false; return this; }

        /**
         * Sets the classpath base used when resolving image sources in HTML text.
         * <p>
         * By default, normalized resource paths must remain below this classpath location. Without a classpath base, classpath resolution accepts absolute
         * classpath resource names without a containment boundary.
         *
         * Unset bases inherit the captured configuration. {@link #clearEmbeddedImageBaseClassPath()} explicitly removes that captured base;
         * {@link #useConfiguredEmbeddedImageBaseClassPath()} restores inheritance. Neither method reads image content or removes a mandatory lock.
         *
         * @param embeddedImageBaseClassPath The classpath base used when resolving images sources in HTML text.
         *
         * @see #withEmbeddedImageAutoResolutionForClassPathResources(boolean)
         * @see #allowingEmbeddedImageOutsideBaseClassPath(boolean)
         */
        @NotNull public Builder withEmbeddedImageBaseClassPath(@NotNull final String embeddedImageBaseClassPath) { this.embeddedImageBaseClassPath = checkNonEmptyArgument(embeddedImageBaseClassPath, "embeddedImageBaseClassPath"); embeddedImageBaseClassPathConfigured = true; return this; }

        /** @see #withEmbeddedImageBaseClassPath(String) */
        @Nullable public String getEmbeddedImageBaseClassPath() { return embeddedImageBaseClassPath; }
        /** @see #withEmbeddedImageBaseClassPath(String) */
        public boolean isEmbeddedImageBaseClassPathConfigured() { return embeddedImageBaseClassPathConfigured; }
        /** Removes the base explicitly, including a captured base.
     * @see #withEmbeddedImageBaseClassPath(String) */
        @NotNull public Builder clearEmbeddedImageBaseClassPath() { embeddedImageBaseClassPath = null; embeddedImageBaseClassPathConfigured = true; return this; }
        /** Restores inheritance from the captured configuration.
     * @see #withEmbeddedImageBaseClassPath(String) */
        @NotNull public Builder useConfiguredEmbeddedImageBaseClassPath() { embeddedImageBaseClassPath = null; embeddedImageBaseClassPathConfigured = false; return this; }

        /**
         * Sets the base URL used when resolving image sources in HTML text.
         * <p>
         * By default, resolved URLs and every HTTP redirect must use the same scheme, host and effective port as this URL, and their normalized path must
         * remain below its path. Without a base URL, URL resolution accepts absolute URLs without a containment boundary.
         *
         * Unset bases inherit the captured configuration. {@link #clearEmbeddedImageBaseUrl()} explicitly removes that captured base;
         * {@link #useConfiguredEmbeddedImageBaseUrl()} restores inheritance. Neither method reads image content or removes a mandatory lock.
         *
         * @param embeddedImageBaseUrl The base URL used when resolving images sources in HTML text.
         *
         * @see #withEmbeddedImageAutoResolutionForURLs(boolean)
         * @see #allowingEmbeddedImageOutsideBaseUrl(boolean)
         */
        @NotNull public Builder withEmbeddedImageBaseUrl(@NotNull final URL embeddedImageBaseUrl) { this.embeddedImageBaseUrl = requireNonNull(embeddedImageBaseUrl, "embeddedImageBaseUrl"); embeddedImageBaseUrlConfigured = true; return this; }

        /**
         * Delegates to {@link #withEmbeddedImageBaseUrl(URL)}.
         *
         * @param embeddedImageBaseUrl The base URL used when resolving images sources in HTML text.
         *
         * @see #withEmbeddedImageAutoResolutionForURLs(boolean)
         * @see #allowingEmbeddedImageOutsideBaseUrl(boolean)
         */
        @NotNull public Builder withEmbeddedImageBaseUrl(@NotNull final String embeddedImageBaseUrl) { try { return withEmbeddedImageBaseUrl(new URL(requireNonNull(embeddedImageBaseUrl, "embeddedImageBaseUrl"))); } catch (MalformedURLException invalidUrl) { throw new IllegalArgumentException("Invalid embedded-image base URL", invalidUrl); } }

        /** @see #withEmbeddedImageBaseUrl(URL) */
        @Nullable public URL getEmbeddedImageBaseUrl() { return embeddedImageBaseUrl; }
        /** @see #withEmbeddedImageBaseUrl(URL) */
        public boolean isEmbeddedImageBaseUrlConfigured() { return embeddedImageBaseUrlConfigured; }
        /** Removes the base explicitly, including a captured base.
     * @see #withEmbeddedImageBaseUrl(URL) */
        @NotNull public Builder clearEmbeddedImageBaseUrl() { embeddedImageBaseUrl = null; embeddedImageBaseUrlConfigured = true; return this; }
        /** Restores inheritance from the captured configuration.
     * @see #withEmbeddedImageBaseUrl(URL) */
        @NotNull public Builder useConfiguredEmbeddedImageBaseUrl() { embeddedImageBaseUrl = null; embeddedImageBaseUrlConfigured = false; return this; }

        /**
         * Allows embedded-image file resolution outside the configured base directory. Disabled by default.
         * <p>
         * Enabling this deliberately removes the filesystem containment boundary and is unsafe for untrusted HTML.
         *
         * Unset choices inherit the captured configuration. {@link #clearAllowEmbeddedImageOutsideBaseDir()} restores that inheritance;
         * {@link #resetAllowEmbeddedImageOutsideBaseDir()} explicitly selects the published built-in fallback.
         *
         * @param allowEmbeddedImageOutsideBaseDir Whether files should be resolved that reside outside the baseDir (if set)
         *
         * @see #withEmbeddedImageAutoResolutionForFiles(boolean)
         * @see #withEmbeddedImageBaseDir(String)
         */
        @NotNull public Builder allowingEmbeddedImageOutsideBaseDir(final boolean allowEmbeddedImageOutsideBaseDir) { this.allowEmbeddedImageOutsideBaseDir = allowEmbeddedImageOutsideBaseDir; return this; }

        /** @see #allowingEmbeddedImageOutsideBaseDir(boolean) */
        @Nullable public Boolean getAllowEmbeddedImageOutsideBaseDir() { return allowEmbeddedImageOutsideBaseDir; }
        /** @see #allowingEmbeddedImageOutsideBaseDir(boolean) */
        @NotNull public Builder clearAllowEmbeddedImageOutsideBaseDir() { allowEmbeddedImageOutsideBaseDir = null; return this; }
        /** @see #allowingEmbeddedImageOutsideBaseDir(boolean) */
        @NotNull public Builder resetAllowEmbeddedImageOutsideBaseDir() { return allowingEmbeddedImageOutsideBaseDir(EmailPopulatingBuilder.DEFAULT_ALLOW_EMBEDDED_IMAGE_OUTSIDE_BASE_DIR); }

        /**
         * Allows embedded-image classpath resolution outside the configured classpath base. Disabled by default.
         * <p>
         * Enabling this deliberately removes the classpath containment boundary and is unsafe for untrusted HTML.
         *
         * Unset choices inherit the captured configuration. {@link #clearAllowEmbeddedImageOutsideBaseClassPath()} restores that inheritance;
         * {@link #resetAllowEmbeddedImageOutsideBaseClassPath()} explicitly selects the published built-in fallback.
         *
         * @param allowEmbeddedImageOutsideBaseClassPath Whether image sources should be resolved that reside outside the baseClassPath (if set)
         *
         * @see #withEmbeddedImageAutoResolutionForClassPathResources(boolean)
         * @see #withEmbeddedImageBaseClassPath(String)
         */
        @NotNull public Builder allowingEmbeddedImageOutsideBaseClassPath(final boolean allowEmbeddedImageOutsideBaseClassPath) { this.allowEmbeddedImageOutsideBaseClassPath = allowEmbeddedImageOutsideBaseClassPath; return this; }

        /** @see #allowingEmbeddedImageOutsideBaseClassPath(boolean) */
        @Nullable public Boolean getAllowEmbeddedImageOutsideBaseClassPath() { return allowEmbeddedImageOutsideBaseClassPath; }
        /** @see #allowingEmbeddedImageOutsideBaseClassPath(boolean) */
        @NotNull public Builder clearAllowEmbeddedImageOutsideBaseClassPath() { allowEmbeddedImageOutsideBaseClassPath = null; return this; }
        /** @see #allowingEmbeddedImageOutsideBaseClassPath(boolean) */
        @NotNull public Builder resetAllowEmbeddedImageOutsideBaseClassPath() { return allowingEmbeddedImageOutsideBaseClassPath(EmailPopulatingBuilder.DEFAULT_ALLOW_EMBEDDED_IMAGE_OUTSIDE_BASE_CLASSPATH); }

        /**
         * Allows embedded-image URL resolution outside the configured base URL. Disabled by default.
         * <p>
         * Enabling this deliberately removes the URL origin and path boundary and is unsafe for untrusted HTML.
         *
         * Unset choices inherit the captured configuration. {@link #clearAllowEmbeddedImageOutsideBaseUrl()} restores that inheritance;
         * {@link #resetAllowEmbeddedImageOutsideBaseUrl()} explicitly selects the published built-in fallback.
         *
         * @param allowEmbeddedImageOutsideBaseUrl Whether url's should be resolved that reside outside the baseUrl (if set)
         *
         * @see #withEmbeddedImageAutoResolutionForURLs(boolean)
         * @see #withEmbeddedImageBaseUrl(String)
         * @see #withEmbeddedImageBaseUrl(URL)
         */
        @NotNull public Builder allowingEmbeddedImageOutsideBaseUrl(final boolean allowEmbeddedImageOutsideBaseUrl) { this.allowEmbeddedImageOutsideBaseUrl = allowEmbeddedImageOutsideBaseUrl; return this; }

        /** @see #allowingEmbeddedImageOutsideBaseUrl(boolean) */
        @Nullable public Boolean getAllowEmbeddedImageOutsideBaseUrl() { return allowEmbeddedImageOutsideBaseUrl; }
        /** @see #allowingEmbeddedImageOutsideBaseUrl(boolean) */
        @NotNull public Builder clearAllowEmbeddedImageOutsideBaseUrl() { allowEmbeddedImageOutsideBaseUrl = null; return this; }
        /** @see #allowingEmbeddedImageOutsideBaseUrl(boolean) */
        @NotNull public Builder resetAllowEmbeddedImageOutsideBaseUrl() { return allowingEmbeddedImageOutsideBaseUrl(EmailPopulatingBuilder.DEFAULT_ALLOW_EMBEDDED_IMAGE_OUTSIDE_BASE_URL); }

        /**
         * When embedded image auto resolution is enabled, this option will make sure unresolved images sources result in an exception.
         * <p>
         * Not using this option effectively means a more lenient approach to image sources.
         * References blocked by a configured base boundary count as unresolved.
         * <p>
         * Note: It also allows you to work with URL's as image sources that can't be resolved at time of sending, but that makes sense
         * when viewing the email in some client (e.g. relative url's).
         *
         * Unset choices inherit the captured configuration. {@link #clearEmbeddedImageAutoResolutionMustBeSuccesful()} restores that inheritance;
         * {@link #resetEmbeddedImageAutoResolutionMustBeSuccesful()} explicitly selects the published built-in fallback.
         *
         * @param embeddedImageAutoResolutionMustBeSuccesful Whether auto resolution is enforced and bubbles up failure to do so.
         */
        @NotNull public Builder embeddedImageAutoResolutionMustBeSuccesful(final boolean embeddedImageAutoResolutionMustBeSuccesful) { this.embeddedImageAutoResolutionMustBeSuccesful = embeddedImageAutoResolutionMustBeSuccesful; return this; }

        /** @see #embeddedImageAutoResolutionMustBeSuccesful(boolean) */
        @Nullable public Boolean getEmbeddedImageAutoResolutionMustBeSuccesful() { return embeddedImageAutoResolutionMustBeSuccesful; }
        /** @see #embeddedImageAutoResolutionMustBeSuccesful(boolean) */
        @NotNull public Builder clearEmbeddedImageAutoResolutionMustBeSuccesful() { embeddedImageAutoResolutionMustBeSuccesful = null; return this; }
        /** @see #embeddedImageAutoResolutionMustBeSuccesful(boolean) */
        @NotNull public Builder resetEmbeddedImageAutoResolutionMustBeSuccesful() { return embeddedImageAutoResolutionMustBeSuccesful(EmailPopulatingBuilder.DEFAULT_EMBEDDED_IMAGE_AUTO_RESOLUTION_MUST_BE_SUCCESSFUL); }

        /** @return An immutable snapshot without resolving or reading any image. */
        @NotNull public ImageResolutionConfig build() { return new ImageResolutionConfig(this); }
    }
}
