package org.simplejavamail.converter;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceContent;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;

class OptionalPreprocessorCancellationTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final String HEADERS = "Message-ID: <preprocessor@example.org>\r\nFrom: sender@example.org\r\n"
            + "To: reader@example.org\r\nSubject: Readable subject\r\n";
    enum Detector { OPENPGP, SMIME }
    enum FailureKind { INTERRUPTED_IO, DIRECT_CANCELLATION, APPLICATION_FAILURE }

    private static Stream<Arguments> operationFailures() {
        return Stream.of(Detector.values()).flatMap(detector -> Stream.of(FailureKind.values()).map(kind -> Arguments.of(detector, kind)));
    }

    @ParameterizedTest @MethodSource("operationFailures")
    void selectedProtectionReaderPreservesCancellationAndApplicationFailures(final Detector detector,
            final FailureKind kind) throws Exception {
        final Throwable original;
        switch (kind) {
            case INTERRUPTED_IO: original = new InterruptedIOException("selected reader interrupted"); break;
            case DIRECT_CANCELLATION: original = new CancellationException("selected reader cancelled"); break;
            default: original = new IllegalStateException("application reader failed");
        }
        final FailingInput stream = new FailingInput(new byte[0], original, null);
        final MimeMessage input = message(detector, stream);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        try {
            requireModule(detector);
            if (kind == FailureKind.INTERRUPTED_IO) {
                assertThatThrownBy(() -> MAIL.converter().fromMimeMessage(input).toEmail())
                        .isInstanceOfSatisfying(CancellationException.class, cancelled -> assertThat(hasCause(cancelled, original)).isTrue());
            } else {
                assertThatThrownBy(() -> MAIL.converter().fromMimeMessage(input).toEmail()).isSameAs(original);
            }
            assertThat(stream.opens).isOne();
            assertThat(stream.closes).isOne();
            assertThat(Thread.currentThread().isInterrupted()).isEqualTo(kind == FailureKind.INTERRUPTED_IO);
        } finally {
            Thread.interrupted();
        }
    }

    @ParameterizedTest @EnumSource(Detector.class)
    void selectedReadAndCloseFailureRetainsThePrefixAndReadableHeaders(final Detector detector) throws Exception {
        final byte[] prefix = "Acquired prefix".getBytes(UTF_8);
        final IOException readFailure = new IOException("selected body read failed");
        final IOException closeFailure = new IOException("selected body close failed");
        final FailingInput stream = new FailingInput(prefix, readFailure, closeFailure);
        requireModule(detector);

        final Email email = MAIL.converter().fromMimeMessage(message(detector, stream)).toEmail();

        assertThat(stream.opens).isOne();
        assertThat(stream.closes).isOne();
        assertThat(readFailure.getSuppressed()).containsExactly(closeFailure);
        assertThat(email.getSubject()).isEqualTo("Readable subject");
        assertThat(email.getPlainText()).isNull();
        assertThat(email.getProblems()).anySatisfy(problem -> assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.CONTENT_READ_FAILED));
        assertThat(email.getSourceData().getParts()).singleElement().satisfies(part -> {
            assertThat(part.getContent().getRepresentation()).isEqualTo(EmailSourceContent.Representation.READER_BYTES);
            assertThat(part.getContent().isFullyCaptured()).isFalse();
            assertThat(part.getContent().readAllBytes()).containsExactly(prefix);
        });
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @ParameterizedTest @EnumSource(Detector.class)
    void unavailableProtectionMetadataKeepsTheReadableDetachedBody(final Detector detector) throws Exception {
        final MetadataFailureMessage input = new MetadataFailureMessage(detector);
        requireModule(detector);

        final Email email = MAIL.converter().fromMimeMessage(input).toEmail();

        assertThat(input.failures).isOne();
        assertThat(email.getPlainText()).isEqualTo("Readable body");
        assertThat(email.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(email.getProblems()).anySatisfy(problem -> assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.PROTECTION_FAILED));
    }

    private static void requireModule(final Detector detector) {
        assertThat(detector == Detector.OPENPGP ? ModuleLoader.openPgpModuleAvailable() : ModuleLoader.smimeModuleAvailable()).isTrue();
    }

    private static boolean hasCause(final Throwable failure, final Throwable expected) {
        final Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            if (current == expected) return true;
        }
        return false;
    }

    private static byte[] fixture(final Detector detector) {
        final String plain = "Content-Type: text/plain; charset=UTF-8\r\n\r\nReadable body";
        final String protocol = detector == Detector.OPENPGP ? "application/pgp-signature" : "application/pkcs7-signature";
        final String filename = detector == Detector.OPENPGP ? "signature.asc" : "smime.p7s";
        final String content = "Content-Type: multipart/signed; boundary=signed; protocol=\"" + protocol + "\"\r\n\r\n"
                + "--signed\r\n" + plain + "\r\n--signed\r\nContent-Type: " + protocol + "\r\n"
                + "Content-Disposition: attachment; filename=" + filename + "\r\nContent-Transfer-Encoding: base64\r\n\r\nAA==\r\n--signed--\r\n";
        return (HEADERS + content).getBytes(UTF_8);
    }

    private static MimeMessage message(final Detector detector, final FailingInput stream) throws MessagingException {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(fixture(detector))) {
            @Override public InputStream getInputStream() {
                stream.opens++;
                return stream;
            }
        };
    }

    private static final class FailingInput extends InputStream {
        private final byte[] prefix;
        private final Throwable readFailure;
        private final IOException closeFailure;
        private int offset;
        private int opens;
        private int closes;

        private FailingInput(final byte[] prefix, final Throwable readFailure, final IOException closeFailure) {
            this.prefix = prefix;
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
        }

        @Override public int read() throws IOException {
            if (offset < prefix.length) return prefix[offset++] & 0xff;
            if (readFailure instanceof InterruptedIOException) Thread.currentThread().interrupt();
            if (readFailure instanceof IOException) throw (IOException) readFailure;
            throw (RuntimeException) readFailure;
        }

        @Override public void close() throws IOException {
            closes++;
            if (closeFailure != null) throw closeFailure;
        }
    }

    private static final class MetadataFailureMessage extends MimeMessage {
        private final Detector detector;
        private int failures;

        private MetadataFailureMessage(final Detector detector) throws MessagingException {
            super(Session.getInstance(new Properties()), new ByteArrayInputStream(fixture(detector)));
            this.detector = detector;
        }

        @Override public String getEncoding() throws MessagingException {
            if (detector == Detector.SMIME && failures == 0) throw unavailableMetadata();
            return super.getEncoding();
        }

        @Override public Enumeration<String> getAllHeaderLines() throws MessagingException {
            if (detector == Detector.OPENPGP && failures == 0) throw unavailableMetadata();
            return super.getAllHeaderLines();
        }

        private MessagingException unavailableMetadata() {
            failures++;
            return new MessagingException("protection reader metadata unavailable", new IOException("metadata read failed"));
        }
    }
}
