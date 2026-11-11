package org.simplejavamail.converter;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMultipart;
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
    Stream<DynamicTest> selectedMetadataInterruptionAfterAReadableSiblingCancelsBothImportRoutes() {
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
                        assertThat(causes(failure)).contains(original);
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
                assertThat(causes(failure)).contains(original);
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
                        assertThat(observed).containsExactly("Readable body", "UNSIGNED", "NOT_ENCRYPTED", "true");
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

    private static java.util.List<Throwable> causes(final Throwable failure) {
        final java.util.List<Throwable> causes = new java.util.ArrayList<>();
        for (Throwable cause = failure; cause != null && !causes.contains(cause); cause = cause.getCause()) causes.add(cause);
        return causes;
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
            final MimeBodyPart readable = new MimeBodyPart() {
                @Override
                public Object getContent() {
                    bodyRead.set(true);
                    return "Readable body";
                }
            };
            readable.setText("Readable body", "UTF-8");
            final MimeBodyPart unavailable = new MimeBodyPart() {
                @Override
                public String[] getHeader(final String name) throws MessagingException {
                    if (bodyRead.get() && name.equalsIgnoreCase("Content-Transfer-Encoding")) {
                        if (interruptAtFailure) Thread.currentThread().interrupt();
                        if (original instanceof RuntimeException) throw (RuntimeException) original;
                        if (original instanceof Error) throw (Error) original;
                        throw new MessagingException("Selected sibling metadata read failed", (Exception) original);
                    }
                    return super.getHeader(name);
                }
            };
            unavailable.setText("Unavailable sibling", "UTF-8");
            unavailable.setDisposition("attachment");
            unavailable.setFileName("unavailable.txt");
            final MimeMultipart mixed = new MimeMultipart("mixed");
            mixed.addBodyPart(readable);
            mixed.addBodyPart(unavailable);
            final MimeMessage source = new MimeMessage(Session.getInstance(new Properties()));
            source.setFrom("sender@example.org");
            source.setSubject("Readable message");
            source.setContent(mixed);
            source.setHeader("Content-Type", mixed.getContentType());
            final Email imported = fluent
                    ? SimpleJavaMail.fromDefaults().converter().fromMimeMessage(source).withFetchAttachmentData(eager).toEmail()
                    : EmailConverter.mimeMessageToEmail(source, null, eager);
            return new String[] {imported.getPlainText(), imported.getInspection().getPlainText().getSignatureStatus().name(),
                    imported.getInspection().getPlainText().getEncryptionStatus().name(),
                    Boolean.toString(imported.getProblems().stream().anyMatch(problem -> !problem.getSources().isEmpty()))};
        }
    }
}
