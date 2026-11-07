package org.simplejavamail.converter;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.lang.reflect.InvocationTargetException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.channels.ClosedByInterruptException;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class UnavailableSmimeInterruptionTest {

    @TestFactory
    Stream<DynamicTest> lateDeclarationInterruptionCancelsBothImportRoutes() {
        return importRoutes().flatMap(route -> Stream.<Supplier<Exception>>of(
                () -> new InterruptedIOException("Metadata read interrupted"),
                ClosedByInterruptException::new,
                () -> new InterruptedException("Metadata reader interrupted"),
                UnavailableSmimeInterruptionTest::failureWithSuppressedInterruption)
                .map(cause -> DynamicTest.dynamicTest(route + " " + cause.get().getClass().getSimpleName(), () -> {
                    final Exception original = cause.get();
                    final AtomicBoolean bodyRead = new AtomicBoolean();
                    assertThat(Thread.currentThread().isInterrupted()).isFalse();
                    try (WithoutSmime runtime = runtime()) {
                        final Throwable failure = catchThrowable(() -> convert(runtime, route, original, false, bodyRead));

                        assertThat(bodyRead).as("ordinary body retrieval completed before the metadata failure").isTrue();
                        assertThat(failure).isInstanceOf(CancellationException.class);
                        assertThat(failure.getCause().getCause()).isSameAs(original);
                        assertThat(failure.getCause().getCause().getSuppressed()).containsExactly(original.getSuppressed());
                        assertThat(Thread.currentThread().isInterrupted()).isFalse();
                    }
                })));
    }

    @TestFactory
    Stream<DynamicTest> flaggedIoFailureKeepsTheCallingThreadsInterruptState() {
        return importRoutes().map(route -> DynamicTest.dynamicTest(route.toString(), () -> {
            final IOException original = new IOException("Metadata read stopped");
            final AtomicBoolean bodyRead = new AtomicBoolean();
            try (WithoutSmime runtime = runtime()) {
                final Throwable failure = catchThrowable(() -> convert(runtime, route, original, true, bodyRead));

                assertThat(bodyRead).isTrue();
                assertThat(failure).isInstanceOf(CancellationException.class);
                assertThat(failure.getCause().getCause()).isSameAs(original);
                assertThat(Thread.currentThread().isInterrupted()).isTrue();
            } finally {
                Thread.interrupted();
            }
        }));
    }

    @TestFactory
    Stream<DynamicTest> ordinaryMetadataFailuresRemainRecoverable() {
        return importRoutes().flatMap(route -> Stream.<Supplier<Exception>>of(
                () -> new IOException("Metadata temporarily unavailable"),
                () -> new SocketTimeoutException("Metadata timed out"))
                .map(cause -> DynamicTest.dynamicTest(route + " " + cause.get().getClass().getSimpleName(), () -> {
                    final AtomicBoolean bodyRead = new AtomicBoolean();
                    try (WithoutSmime runtime = runtime()) {
                        final String[] observed = convert(runtime, route, cause.get(), false, bodyRead);

                        assertThat(bodyRead).isTrue();
                        assertThat(observed).containsExactly("Readable body", "UNKNOWN", "UNKNOWN", "UNKNOWN");
                        assertThat(Thread.currentThread().isInterrupted()).isFalse();
                    }
                })));
    }

    @TestFactory
    Stream<DynamicTest> unrelatedApplicationFailuresStillPropagate() {
        return importRoutes().flatMap(route -> Stream.<Supplier<Throwable>>of(
                () -> new IllegalStateException("Application metadata reader failed"),
                () -> new AssertionError("Application metadata reader failed"))
                .map(cause -> DynamicTest.dynamicTest(route + " " + cause.get().getClass().getSimpleName(), () -> {
                    final Throwable original = cause.get();
                    try (WithoutSmime runtime = runtime()) {
                        assertThat(catchThrowable(() -> convert(runtime, route, original, false, new AtomicBoolean())))
                                .isSameAs(original);
                    }
                })));
    }

    private static Exception failureWithSuppressedInterruption() {
        final IOException original = new IOException("Metadata cleanup failed");
        original.addSuppressed(new InterruptedIOException("Metadata cleanup interrupted"));
        return original;
    }

    private static Stream<Route> importRoutes() {
        return Stream.of(new Route(true, true), new Route(true, false), new Route(false, true), new Route(false, false));
    }

    private static String[] convert(final WithoutSmime runtime, final Route route, final Throwable original,
            final boolean interruptAtFailure, final AtomicBoolean bodyRead) throws Throwable {
        final ClassLoader previous = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(runtime);
        try {
            final Class<?> fixture = runtime.loadClass(ProviderFixture.class.getName());
            return (String[]) fixture.getMethod("convert", boolean.class, boolean.class, Throwable.class, boolean.class, AtomicBoolean.class)
                    .invoke(null, route.fluent, route.eager, original, interruptAtFailure, bodyRead);
        } catch (InvocationTargetException failure) {
            throw failure.getCause();
        } finally {
            Thread.currentThread().setContextClassLoader(previous);
        }
    }

    private static WithoutSmime runtime() {
        final String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        final URL[] urls = Arrays.stream(classpath.split(Pattern.quote(File.pathSeparator))).map(entry -> {
            try {
                return new File(entry).toURI().toURL();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }).toArray(URL[]::new);
        return new WithoutSmime(urls);
    }

    private static final class WithoutSmime extends URLClassLoader {
        private WithoutSmime(final URL[] urls) {
            super(urls, ClassLoader.getPlatformClassLoader());
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            if (name.startsWith("org.simplejavamail.internal.smimesupport.")) {
                throw new ClassNotFoundException("S/MIME deliberately absent from this isolated application: " + name);
            }
            return super.loadClass(name, resolve);
        }
    }

    private static final class Route {
        private final boolean fluent;
        private final boolean eager;

        private Route(final boolean fluent, final boolean eager) {
            this.fluent = fluent;
            this.eager = eager;
        }

        @Override
        public String toString() {
            return (fluent ? "fluent" : "static") + (eager ? " eager" : " deferred");
        }
    }

    /** Loaded with the application classes so the optional implementation is absent from the actual converter invocation. */
    public static final class ProviderFixture {
        public static String[] convert(final boolean fluent, final boolean eager, final Throwable original,
                final boolean interruptAtFailure, final AtomicBoolean bodyRead) throws Exception {
            if (ModuleLoader.smimeModuleAvailable()) throw new AssertionError("The S/MIME implementation must be absent");
            final String eml = "From: sender@example.org\r\nSubject: Readable message\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nReadable body";
            final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(eml.getBytes(UTF_8))) {
                @Override
                public Object getContent() {
                    bodyRead.set(true);
                    return "Readable body";
                }

                @Override
                public String getContentType() throws MessagingException {
                    if (bodyRead.get()) {
                        if (interruptAtFailure) Thread.currentThread().interrupt();
                        if (original instanceof RuntimeException) throw (RuntimeException) original;
                        if (original instanceof Error) throw (Error) original;
                        throw new MessagingException("Late MIME metadata read failed", (Exception) original);
                    }
                    return super.getContentType();
                }
            };
            final Email imported = fluent
                    ? SimpleJavaMail.fromDefaults().converter().fromMimeMessage(source).withFetchAttachmentData(eager).toEmail()
                    : EmailConverter.mimeMessageToEmail(source, null, eager);
            return new String[] {imported.getPlainText(), imported.getOriginalSmimeDetails().getSmimeMode().name(),
                    imported.getOriginalSmimeDetails().getVerificationStatus().name(), imported.getOriginalSmimeDetails().getDecryptionStatus().name()};
        }
    }
}
