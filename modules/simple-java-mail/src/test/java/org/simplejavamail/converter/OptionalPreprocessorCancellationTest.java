package org.simplejavamail.converter;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

class OptionalPreprocessorCancellationTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final String HEADERS = "Message-ID: <preprocessor@example.org>\r\nFrom: sender@example.org\r\nTo: reader@example.org\r\n";
    enum Detector { OPENPGP, SMIME }
    enum FailureKind { INTERRUPTED_IO, DIRECT_CANCELLATION }

    private static Stream<Arguments> cancellations() {
        return Stream.of(Detector.values()).flatMap(detector -> Stream.of(FailureKind.values()).map(kind -> Arguments.of(detector, kind)));
    }

    @ParameterizedTest @MethodSource("cancellations")
    void optionalPreprocessorsDoNotTurnSerializationCancellationIntoReceivedEmailFacts(final Detector detector,
            final FailureKind kind) throws Exception {
        final Throwable original = kind == FailureKind.INTERRUPTED_IO
                ? new InterruptedIOException("one-shot preprocessor interruption")
                : new CancellationException("one-shot preprocessor cancellation");
        final FailingSerializationMessage input = message(detector, original);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        try {
            if (detector == Detector.SMIME) {
                try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
                    modules.when(ModuleLoader::openPgpModuleAvailable).thenReturn(false);
                    assertThat(ModuleLoader.smimeModuleAvailable()).isTrue();
                    assertCancellation(input, original, kind);
                }
            } else {
                assertThat(ModuleLoader.openPgpModuleAvailable()).isTrue();
                assertCancellation(input, original, kind);
            }
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(kind == FailureKind.INTERRUPTED_IO);
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest @EnumSource(Detector.class)
    void ordinarySerializationFailureKeepsTheExistingReadableFallback(final Detector detector) throws Exception {
        final FailingSerializationMessage input = message(detector, new IOException("one-shot ordinary serialization failure"));
        try {
            final Email email;
            if (detector == Detector.SMIME) {
                try (MockedStatic<ModuleLoader> modules = Mockito.mockStatic(ModuleLoader.class, Mockito.CALLS_REAL_METHODS)) {
                    modules.when(ModuleLoader::openPgpModuleAvailable).thenReturn(false);
                    assertThat(ModuleLoader.smimeModuleAvailable()).isTrue();
                    email = MAIL.converter().fromMimeMessage(input).toEmail();
                }
            } else {
                assertThat(ModuleLoader.openPgpModuleAvailable()).isTrue();
                email = MAIL.converter().fromMimeMessage(input).toEmail();
            }
            assertThat(input.writeAttempts).as("the selected protection serialization must exercise the injected I/O failure").isPositive();
            assertThat(email.getPlainText()).isEqualTo("Readable after serialization failure");
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
        } finally {
            Thread.interrupted();
        }
    }

    private static void assertCancellation(final FailingSerializationMessage input, final Throwable original, final FailureKind kind) {
        assertThatThrownBy(() -> MAIL.converter().fromMimeMessage(input).toEmail())
                .isInstanceOfSatisfying(CancellationException.class, canceled -> {
                    if (kind == FailureKind.DIRECT_CANCELLATION) assertThat(canceled).isSameAs(original);
                    else assertThat(hasCause(canceled, original)).isTrue();
                });
        assertThat(input.writeAttempts).as("a declared protected envelope must reach the injected serialization cancellation").isPositive();
    }

    private static boolean hasCause(final Throwable failure, final Throwable expected) {
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current == expected) return true;
        }
        return false;
    }

    private static FailingSerializationMessage message(final Detector detector, final Throwable firstWriteFailure) throws Exception {
        final String plain = "Content-Type: text/plain; charset=UTF-8\r\n\r\nReadable after serialization failure";
        final String protocol = detector == Detector.OPENPGP ? "application/pgp-signature" : "application/pkcs7-signature";
        final String filename = detector == Detector.OPENPGP ? "signature.asc" : "smime.p7s";
        final String content = "Content-Type: multipart/signed; boundary=signed; protocol=\"" + protocol + "\"\r\n\r\n"
                + "--signed\r\n" + plain + "\r\n--signed\r\nContent-Type: " + protocol + "\r\n"
                + "Content-Disposition: attachment; filename=" + filename + "\r\nContent-Transfer-Encoding: base64\r\n\r\nAA==\r\n--signed--\r\n";
        return new FailingSerializationMessage(content, firstWriteFailure);
    }

    private static final class FailingSerializationMessage extends MimeMessage {
        private final Throwable firstWriteFailure;
        private int writeAttempts;

        private FailingSerializationMessage(final String content, final Throwable firstWriteFailure) throws Exception {
            super(Session.getInstance(new Properties()), new ByteArrayInputStream((HEADERS + content).getBytes(UTF_8)));
            this.firstWriteFailure = firstWriteFailure;
        }

        @Override public void writeTo(final OutputStream output) throws IOException, MessagingException {
            if (++writeAttempts == 1) {
                if (firstWriteFailure instanceof InterruptedIOException) Thread.currentThread().interrupt();
                if (firstWriteFailure instanceof IOException) throw (IOException) firstWriteFailure;
                throw (CancellationException) firstWriteFailure;
            }
            super.writeTo(output);
        }
    }
}
