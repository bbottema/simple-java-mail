package org.simplejavamail.email.internal;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import org.simplejavamail.config.ConfigLoader;
import testutil.ConfigLoaderTestHelper;
import testutil.imageprotocol.controlledimage.Handler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.URLConnection;
import java.nio.channels.ClosedByInterruptException;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CancellationException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES;

@ResourceLock(SYSTEM_PROPERTIES)
class DynamicImageReadFailureTest {
    private static final String HANDLER_PACKAGES = "java.protocol.handler.pkgs";
    private static final String SOURCE = "controlledimage://images/logo.png";
    private static final String HTML = "<img src='" + SOURCE + "'>";
    private static String previousHandlers;

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    enum Stage { OPEN, READ, CLOSE }

    @BeforeAll
    static void enableControlledImages() {
        previousHandlers = System.getProperty(HANDLER_PACKAGES);
        System.setProperty(HANDLER_PACKAGES, "testutil.imageprotocol"
                + (previousHandlers == null ? "" : "|" + previousHandlers));
    }

    @AfterAll
    static void restoreHandlers() {
        if (previousHandlers == null) System.clearProperty(HANDLER_PACKAGES);
        else System.setProperty(HANDLER_PACKAGES, previousHandlers);
    }

    @AfterEach
    void releaseFixture() {
        Handler.CONNECTIONS.remove();
        // Individual assertions check the state before this cleanup.
        Thread.interrupted();
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void buildPropagatesInterruptionDuringOpeningReadingAndClosing(final Stage stage) {
        for (boolean required : new boolean[] {false, true}) {
            final IOException interrupted = stage == Stage.READ
                    ? new ClosedByInterruptException() : new InterruptedIOException("image stopped");
            final ImageRead read = install(stage, interrupted, null);
            final EmailPopulatingBuilder draft = draft(required);

            assertCancellation(catchThrowable(draft::buildEmail), interrupted, false);
            assertThat(read.closed).isEqualTo(stage != Stage.OPEN);
            assertThat(draft.getHTMLText()).isEqualTo(HTML);
            assertThat(draft.getEmbeddedImages()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuredEmlAndMimeImportsPropagateInterruptionFromAnImage(final boolean required) throws Exception {
        final SimpleJavaMail configured = configured(required);
        final String eml = "Content-Type: text/html; charset=UTF-8\r\n\r\n" + HTML;
        final IOException readFailure = new IOException("image unavailable", new InterruptedIOException("stopped"));
        install(Stage.READ, readFailure, null);
        assertCancellation(catchThrowable(() -> configured.converter().fromEml(eml).toEmail()), readFailure, false);

        install(Stage.CLOSE, readFailure, null);
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(eml.getBytes(UTF_8)));
        assertCancellation(catchThrowable(() -> configured.converter().fromMimeMessage(message).toEmail()), readFailure, false);
    }

    @Test
    void interruptedCleanupPreservesThePrimaryReadFailureAndSuppressedFailure() {
        final IOException primary = new IOException("read failed");
        final IOException cleanup = new ClosedByInterruptException();
        final ImageRead read = install(Stage.READ, primary, cleanup);

        assertCancellation(catchThrowable(() -> draft(false).buildEmail()), primary, false);
        assertThat(primary.getSuppressed()).containsExactly(cleanup);
        assertThat(read.closed).isTrue();
    }

    @Test
    void aProviderArgumentFailureCannotHideAnInterruptedCauseOrCleanupFailure() {
        final IllegalArgumentException caused = new IllegalArgumentException("provider failed", new InterruptedIOException("stopped"));
        Handler.CONNECTIONS.set(url -> { throw caused; });
        assertCancellation(catchThrowable(() -> draft(false).buildEmail()), caused, false);

        final IllegalArgumentException primary = new IllegalArgumentException("read rejected");
        final IOException cleanup = new ClosedByInterruptException();
        Handler.CONNECTIONS.set(url -> new URLConnection(url) {
            @Override public void connect() { }
            @Override public String getContentType() { return "image/png"; }
            @Override public InputStream getInputStream() {
                return new InputStream() {
                    @Override public int read() { throw primary; }
                    @Override public void close() throws IOException { throw cleanup; }
                };
            }
        });
        assertCancellation(catchThrowable(() -> draft(false).buildEmail()), primary, false);
        assertThat(primary.getSuppressed()).containsExactly(cleanup);
    }

    @Test
    void ordinaryProviderArgumentFailuresStillCountAsUnresolved() {
        Handler.CONNECTIONS.set(url -> { throw new IllegalArgumentException("unusable source"); });
        assertThat(draft(false).buildEmail().getHTMLText()).isEqualTo(HTML);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void interruptionAfterAnEarlierImageLeavesTheWholeDraftUnchanged() {
        final ImageRead completed = new ImageRead(null, null, null);
        final IOException stopped = new InterruptedIOException("second image stopped");
        final ImageRead interrupted = new ImageRead(Stage.READ, stopped, null);
        Handler.CONNECTIONS.set(url -> url.getPath().equals("/complete.png") ? completed.open(url) : interrupted.open(url));
        final String html = "<img src='controlledimage://images/complete.png'>" + HTML;
        final EmailPopulatingBuilder draft = draft(false).withHTMLText(html);

        assertCancellation(catchThrowable(draft::buildEmail), stopped, false);
        assertThat(draft.getHTMLText()).isEqualTo(html);
        assertThat(draft.getEmbeddedImages()).isEmpty();
        assertThat(completed.closed).isTrue();
        assertThat(interrupted.closed).isTrue();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void classpathReadAndCloseFailuresKeepTheirCauseInOptionalAndRequiredMode(final boolean required) throws Exception {
        final IOException primary = new IOException("classpath read failed");
        final IOException cleanup = new IOException("classpath close failed");
        final ImageRead read = new ImageRead(Stage.READ, primary, cleanup);

        assertThat(buildWithClasspathImage(read, required)).isInstanceOf(RuntimeException.class).hasCause(primary);
        assertThat(primary.getSuppressed()).containsExactly(cleanup);
        assertThat(read.closed).isTrue();
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void classpathInterruptedCleanupCancelsWithThePrimaryReadContext() throws Exception {
        final IOException primary = new IOException("classpath read failed");
        final IOException cleanup = new ClosedByInterruptException();
        final ImageRead read = new ImageRead(Stage.READ, primary, cleanup);

        assertCancellation(buildWithClasspathImage(read, false), primary, false);
        assertThat(primary.getSuppressed()).containsExactly(cleanup);
        assertThat(read.closed).isTrue();
    }

    @Test
    void existingThreadInterruptionIsNotClearedOrReplaced() {
        final IOException failure = new IOException("read failed while interrupted");
        install(Stage.READ, failure, null);
        Thread.currentThread().interrupt();

        assertCancellation(catchThrowable(() -> draft(false).buildEmail()), failure, true);
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void requiredUrlFailuresRetainTheirOriginalCause(final Stage stage) {
        final IOException failure = new IOException("image unavailable");
        final ImageRead read = install(stage, failure, null);

        assertThat(catchThrowable(() -> draft(true).buildEmail())).isInstanceOf(MailException.class)
                .hasCause(failure).hasMessageContaining(SOURCE);
        assertThat(read.closed).isEqualTo(stage != Stage.OPEN);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @ParameterizedTest
    @EnumSource(Stage.class)
    void ordinarySocketTimeoutsKeepTheOptionalImageUnresolved(final Stage stage) {
        final IOException timeout = new SocketTimeoutException("ordinary image timeout");
        final ImageRead read = install(stage, timeout, null);

        final Email email = draft(false).buildEmail();

        assertThat(email.getHTMLText()).isEqualTo(HTML);
        assertThat(email.getEmbeddedImages()).isEmpty();
        assertThat(read.closed).isEqualTo(stage != Stage.OPEN);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        install(stage, timeout, null);
        assertThat(catchThrowable(() -> draft(true).buildEmail())).isInstanceOf(MailException.class).hasCause(timeout);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void ordinaryOptionalReadFailuresStillLeaveTheRemoteReference() {
        install(Stage.READ, new IOException("image unavailable"), null);
        final Email email = configured(false).converter().fromEml("Content-Type: text/html\r\n\r\n" + HTML).toEmail();
        assertThat(email.getHTMLText()).isEqualTo(HTML);
        assertThat(email.getEmbeddedImages()).isEmpty();
    }

    private EmailPopulatingBuilder draft(final boolean required) {
        return mail.emailBuilder().startingBlank().withHTMLText(HTML)
                .withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForURLs(true)
                        .embeddedImageAutoResolutionMustBeSuccesful(required).build());
    }

    private static SimpleJavaMail configured(final boolean required) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.embeddedimages.dynamicresolution.enable.url", "true",
                "simplejavamail.embeddedimages.dynamicresolution.mustbesuccesful", Boolean.toString(required))).load());
    }

    private static void assertCancellation(final Throwable failure, final Throwable original, final boolean interrupted) {
        assertThat(failure).isInstanceOf(CancellationException.class).hasCause(original);
        assertThat(Thread.currentThread().isInterrupted()).isEqualTo(interrupted);
    }

    private static ImageRead install(final Stage stage, final IOException failure, final IOException closeFailure) {
        final ImageRead read = new ImageRead(stage, failure, closeFailure);
        Handler.CONNECTIONS.set(read::open);
        return read;
    }

    private static Throwable buildWithClasspathImage(final ImageRead read, final boolean required) throws Exception {
        final URL[] sources = {SimpleJavaMail.class.getProtectionDomain().getCodeSource().getLocation(),
                ConfigLoader.class.getProtectionDomain().getCodeSource().getLocation()};
        // A classpath resource's owning loader supplies its stream; keep the real factory and builder path in that loader.
        try (URLClassLoader loader = new URLClassLoader(sources, DynamicImageReadFailureTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null && name.startsWith("org.simplejavamail.")) {
                        try { type = findClass(name); } catch (ClassNotFoundException absent) { /* optional implementation */ }
                    }
                    if (type == null) type = super.loadClass(name, false);
                    if (resolve) resolveClass(type);
                    return type;
                }
            }
            @Override public InputStream getResourceAsStream(final String name) {
                return name.equals("interrupted-image.png") ? read.stream() : super.getResourceAsStream(name);
            }
            @Override public URL getResource(final String name) {
                return name.equals("interrupted-image.png") ? sources[0] : super.getResource(name);
            }
        }) {
            final Class<?> configLoader = loader.loadClass(ConfigLoader.class.getName());
            final Object captured = configLoader.getMethod("builder").invoke(null);
            configLoader.getMethod("withMap", Map.class).invoke(captured, Map.of(
                    "simplejavamail.embeddedimages.dynamicresolution.enable.classpath", "true",
                    "simplejavamail.embeddedimages.dynamicresolution.mustbesuccesful", Boolean.toString(required)));
            final Object config = configLoader.getMethod("load").invoke(captured);
            final Class<?> factoryType = loader.loadClass(SimpleJavaMail.class.getName());
            final Object factory = factoryType.getMethod("withConfig", loader.loadClass("org.simplejavamail.config.SimpleJavaMailConfig"))
                    .invoke(null, config);
            final Object starter = factoryType.getMethod("emailBuilder").invoke(factory);
            final Object draft = starter.getClass().getMethod("startingBlank").invoke(starter);
            draft.getClass().getMethod("withHTMLText", String.class).invoke(draft, "<img src='/interrupted-image.png'>");
            try {
                draft.getClass().getMethod("buildEmail").invoke(draft);
                return null;
            } catch (InvocationTargetException failure) {
                return failure.getCause();
            }
        }
    }

    private static final class ImageRead {
        private final Stage stage;
        private final IOException failure;
        private final IOException closeFailure;
        private boolean closed;

        private ImageRead(final Stage stage, final IOException failure, final IOException closeFailure) {
            this.stage = stage;
            this.failure = failure;
            this.closeFailure = closeFailure;
        }

        private URLConnection open(final URL url) throws IOException {
            if (stage == Stage.OPEN) throw failure;
            return new URLConnection(url) {
                @Override public void connect() { }
                @Override public String getContentType() { return "image/png"; }
                @Override public InputStream getInputStream() {
                    return stream();
                }
            };
        }

        private InputStream stream() {
            return new InputStream() {
                private final InputStream bytes = new ByteArrayInputStream(new byte[] {1, 2, 3});
                @Override public int read() throws IOException {
                    if (stage == Stage.READ) throw failure;
                    return bytes.read();
                }
                @Override public int read(final byte[] destination, final int offset, final int length) throws IOException {
                    if (stage == Stage.READ) throw failure;
                    return bytes.read(destination, offset, length);
                }
                @Override public void close() throws IOException {
                    closed = true;
                    if (closeFailure != null) throw closeFailure;
                    if (stage == Stage.CLOSE) throw failure;
                }
            };
        }
    }
}
