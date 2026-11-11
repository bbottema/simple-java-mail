package org.simplejavamail.converter;

import jakarta.activation.DataHandler;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.util.ByteArrayDataSource;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceHeader;
import org.simplejavamail.api.email.ProtectionInspection;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InterruptedIOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenPgpDeclarationInspectionTest {

    private static final String[] CONTRADICTORY_TYPES = {
            "multipart/encrypted; boundary=crossed; protocol=\"application/pgp-signature\"",
            "multipart/signed; boundary=crossed; protocol=\"application/pgp-encrypted\""
    };

    @TempDir
    Path childOutput;

    @TestFactory
    Stream<DynamicTest> malformedDeclarationsDoNotEstablishAbsenceOfProtection() {
        return Stream.of("not-a-content-type", "text/plain; =broken").flatMap(type ->
                Stream.of("fluent-eml", "static-eml", "fluent-mime", "static-mime", "exact").map(route ->
                        DynamicTest.dynamicTest(type + " / " + route, () -> {
                            final byte[] source = source(type);
                            final Email received = read(source, route);

                            assertUnknown(received.getInspection().getOpenPgp());
                            assertThat(received.getSubject()).isEqualTo("Readable subject");
                            assertThat(received.getSourceData().getHeaders()).filteredOn(header -> header.getName().equalsIgnoreCase("Content-Type"))
                                    .extracting(EmailSourceHeader::getRawValue).containsExactly(type);
                            assertThat(received.getProblems()).isNotEmpty();
                            assertThat(received.getProblems()).noneMatch(problem -> problem.getCode() == EmailProblem.Code.PROTECTION_FAILED
                                    || problem.getCode() == EmailProblem.Code.SIGNATURE_INVALID);
                            if (route.equals("exact")) {
                                assertThat(SimpleJavaMail.fromDefaults().converter().fromEmail(received).toEmlBytes()).containsExactly(source);
                            }
                        })));
    }

    @TestFactory
    Stream<DynamicTest> contradictoryProtectionDeclarationsRemainUnknown() {
        return Arrays.stream(CONTRADICTORY_TYPES).flatMap(type ->
                Stream.of("fluent-eml", "static-eml", "fluent-mime", "static-mime", "exact").map(route ->
                        DynamicTest.dynamicTest(type + " / " + route, () -> assertContradictory(type, route))));
    }

    @TestFactory
    Stream<DynamicTest> contradictoryProtectionDoesNotSerializeBorrowedMimeContent() {
        return Arrays.stream(CONTRADICTORY_TYPES).map(type -> DynamicTest.dynamicTest(type, () -> {
            final AtomicInteger serializations = new AtomicInteger();
            final MimeMessage supplied = new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(contradictorySource(type))) {
                @Override
                public void writeTo(final OutputStream output) throws java.io.IOException, MessagingException {
                    serializations.incrementAndGet();
                    super.writeTo(output);
                }
            };

            final Email received = SimpleJavaMail.fromDefaults().converter().fromMimeMessage(supplied)
                    .withFetchAttachmentData(false).toEmail();

            assertUnknown(received.getInspection().getOpenPgp());
            assertThat(received.getPlainText()).isEqualTo("Readable neighboring body");
            assertThat(serializations).as("an unestablished envelope needs no protected-byte snapshot").hasValue(0);
        }));
    }

    private static void assertContradictory(final String type, final String route) throws Exception {
        final byte[] source = contradictorySource(type);
        final Email received = read(source, route);

        assertUnknown(received.getInspection().getOpenPgp());
        assertThat(received.getSubject()).isEqualTo("Readable subject");
        assertThat(received.getPlainText()).isEqualTo("Readable neighboring body");
        assertThat(received.getSourceData().getHeaders()).extracting(EmailSourceHeader::getRawValue).contains(type);
        assertThat(received.getProblems()).noneMatch(problem -> problem.getCode() == EmailProblem.Code.PROTECTION_FAILED
                || problem.getCode() == EmailProblem.Code.SIGNATURE_INVALID);
        assertUnknown(SimpleJavaMail.fromDefaults().emailBuilder().copying(received).buildEmail().getInspection().getOpenPgp());
        assertUnknown(roundTrip(received).getInspection().getOpenPgp());
        if (route.equals("exact")) {
            assertThat(SimpleJavaMail.fromDefaults().converter().fromEmail(received).toEmlBytes()).containsExactly(source);
        }
    }

    @Test
    void successfulNativeTypeMatchingRetainsItsAuthority() throws Exception {
        assertNativeTypeAuthority();
    }

    private static void assertNativeTypeAuthority() throws Exception {
        final MimeMessage supplied = new MimeMessage(Session.getInstance(new Properties()),
                new ByteArrayInputStream(contradictorySource(CONTRADICTORY_TYPES[0]))) {
            @Override
            public boolean isMimeType(final String type) throws MessagingException {
                if (type.equals("multipart/signed")) return true;
                if (type.equals("multipart/encrypted")) return false;
                return super.isMimeType(type);
            }
        };

        final Email received = SimpleJavaMail.fromDefaults().converter().fromMimeMessage(supplied).toEmail();

        // The provider classifies this as signed; it cannot establish decryption or a valid signature from the incomplete fixture.
        assertThat(received.getInspection().getOpenPgp().getDecryptions()).isEmpty();
        assertThat(received.getInspection().getOpenPgp().isInspectionComplete()).isFalse();
    }

    @Test
    void anIndependentReadableBodySurvivesAnUninterpretableDeclaration() throws Exception {
        final MimeMessage supplied = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(source("not-a-content-type"))) {
            @Override
            public DataHandler getDataHandler() {
                return new DataHandler(new ByteArrayDataSource("Readable neighboring body".getBytes(UTF_8), "text/plain; charset=UTF-8"));
            }
        };

        final Email received = SimpleJavaMail.fromDefaults().converter().fromMimeMessage(supplied).toEmail();

        assertUnknown(received.getInspection().getOpenPgp());
        assertThat(received.getPlainText()).isEqualTo("Readable neighboring body");
        assertThat(received.getSubject()).isEqualTo("Readable subject");
    }

    @Test
    void unknownDeclarationFactsSurviveCopyAndSerialization() throws Exception {
        final Email received = read(source("not-a-content-type"), "fluent-eml");
        final Email copied = SimpleJavaMail.fromDefaults().emailBuilder().copying(received).buildEmail();
        final Email restored = roundTrip(copied);

        assertUnknown(copied.getInspection().getOpenPgp());
        assertUnknown(restored.getInspection().getOpenPgp());
        assertThat(restored.getSourceData().getHeaders()).extracting(EmailSourceHeader::getRawValue)
                .contains("not-a-content-type");
        assertThat(restored.getProblems()).hasSize(received.getProblems().size());
    }

    @TestFactory
    Stream<DynamicTest> validOrDefaultedOrdinaryDeclarationsRemainPlain() {
        return Stream.of("text/plain; charset=UTF-8", null).map(type -> DynamicTest.dynamicTest(String.valueOf(type), () -> {
            final Email received = read(source(type), "fluent-eml");

            assertThat(received.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNSIGNED);
            assertThat(received.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.NOT_ENCRYPTED);
            assertThat(received.getInspection().getOpenPgp().getSignatures()).isEmpty();
            assertThat(received.getInspection().getOpenPgp().getDecryptions()).isEmpty();
            assertThat(received.getPlainText()).isEqualTo("Ordinary content\r\n");
        }));
    }

    @Test
    void nativeClassificationFailureRemainsUnknown() throws Exception {
        final MimeMessage supplied = classificationFailure(new MessagingException("Classification unavailable"));

        final Email received = SimpleJavaMail.fromDefaults().converter().fromMimeMessage(supplied).toEmail();

        assertUnknown(received.getInspection().getOpenPgp());
        assertThat(received.getPlainText()).isEqualTo("Ordinary content\r\n");
    }

    @Test
    void typedClassificationInterruptionStillEscapes() throws Exception {
        final MessagingException failure = new MessagingException("Classification interrupted", new InterruptedIOException("stopped"));
        final MimeMessage supplied = classificationFailure(failure);

        assertThatThrownBy(() -> SimpleJavaMail.fromDefaults().converter().fromMimeMessage(supplied).toEmail())
                .isInstanceOf(CancellationException.class).hasCauseReference(failure);
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
    }

    @Test
    void applicationMetadataFailuresKeepTheirOriginalException() throws Exception {
        final IllegalStateException failure = new IllegalStateException("Application metadata unavailable");
        final MimeMessage supplied = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(source("text/plain"))) {
            @Override
            public String getContentType() {
                throw failure;
            }
        };

        assertThatThrownBy(() -> SimpleJavaMail.fromDefaults().converter().fromMimeMessage(supplied).toEmail()).isSameAs(failure);
    }

    @Test
    void absenceOfTheOptionalImplementationKeepsTheSameDeclarationBoundary() throws Exception {
        final String implementation = "org.simplejavamail.internal.openpgpsupport.OpenPgpSupport";
        final Path optionalArtifact = Path.of(Class.forName(implementation, false, getClass().getClassLoader())
                .getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
        final String original = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        final String withoutModule = Arrays.stream(original.split(Pattern.quote(File.pathSeparator)))
                .filter(entry -> !Path.of(entry).toAbsolutePath().normalize().equals(optionalArtifact))
                .collect(Collectors.joining(File.pathSeparator));
        assertThat(withoutModule).isNotEqualTo(original);
        final Path output = childOutput.resolve("without-openpgp.log");
        final Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", withoutModule, WithoutOpenPgp.class.getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertThat(child.waitFor(45, SECONDS)).as("Module-absent child completed; output: %s", output).isTrue();
            assertThat(child.exitValue()).as(Files.readString(output)).isZero();
            assertThat(Files.readString(output)).contains("DECLARATIONS PRESERVED WITHOUT OPENPGP");
        } finally {
            if (child.isAlive()) {
                child.destroyForcibly();
                child.waitFor(5, SECONDS);
            }
        }
    }

    public static final class WithoutOpenPgp {
        public static void main(final String[] ignored) throws Exception {
            assertThatThrownBy(() -> Class.forName("org.simplejavamail.internal.openpgpsupport.OpenPgpSupport"))
                    .isInstanceOf(ClassNotFoundException.class);
            assertThat(ModuleLoader.openPgpModuleAvailable()).isFalse();
            assertNativeTypeAuthority();
            for (String type : CONTRADICTORY_TYPES) {
                for (String route : new String[] {"fluent-eml", "static-eml", "fluent-mime", "static-mime", "exact"}) {
                    assertContradictory(type, route);
                }
            }
            for (String type : new String[] {"not-a-content-type", "text/plain; =broken"}) {
                for (String route : new String[] {"fluent-eml", "static-eml", "fluent-mime", "static-mime", "exact"}) {
                    final Email received = read(source(type), route);
                    assertUnknown(received.getInspection().getOpenPgp());
                    assertThat(received.getSubject()).isEqualTo("Readable subject");
                    assertThat(received.getSourceData().getHeaders()).extracting(EmailSourceHeader::getRawValue).contains(type);
                    assertThat(received.getProblems()).noneMatch(problem -> problem.getCode() == EmailProblem.Code.PROTECTION_FAILED);
                    assertUnknown(SimpleJavaMail.fromDefaults().emailBuilder().copying(received).buildEmail().getInspection().getOpenPgp());
                    assertUnknown(roundTrip(received).getInspection().getOpenPgp());
                    if (route.equals("exact")) {
                        assertThat(SimpleJavaMail.fromDefaults().converter().fromEmail(received).toEmlBytes()).containsExactly(source(type));
                    }
                }
            }
            final Email readable = SimpleJavaMail.fromDefaults().converter()
                    .fromMimeMessage(classificationFailure(new MessagingException("Classification unavailable"))).toEmail();
            assertUnknown(readable.getInspection().getOpenPgp());
            assertThat(readable.getPlainText()).isEqualTo("Ordinary content\r\n");
            final MessagingException interrupted = new MessagingException("Classification interrupted", new InterruptedIOException("stopped"));
            final MimeMessage interruptedSource = classificationFailure(interrupted);
            assertThatThrownBy(() -> SimpleJavaMail.fromDefaults().converter().fromMimeMessage(interruptedSource).toEmail())
                    .isInstanceOf(CancellationException.class).hasCauseReference(interrupted);
            assertThat(Thread.currentThread().isInterrupted()).isFalse();
            assertThat(read(source(null), "fluent-eml").getInspection().getPlainText().getSignatureStatus())
                    .isEqualTo(SignatureStatus.UNSIGNED);
            System.out.println("DECLARATIONS PRESERVED WITHOUT OPENPGP");
        }
    }

    private static Email roundTrip(final Email email) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(email);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return (Email) input.readObject();
        }
    }

    private static MimeMessage classificationFailure(final MessagingException failure) throws MessagingException {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(source("text/plain; charset=UTF-8"))) {
            private boolean first = true;

            @Override
            public boolean isMimeType(final String type) throws MessagingException {
                if (first) {
                    first = false;
                    throw failure;
                }
                return super.isMimeType(type);
            }
        };
    }

    private static Email read(final byte[] source, final String route) throws Exception {
        switch (route) {
            case "fluent-eml": return SimpleJavaMail.fromDefaults().converter().fromEml(source).toEmail();
            case "static-eml": return EmailConverter.emlToEmail(new ByteArrayInputStream(source));
            case "fluent-mime": return SimpleJavaMail.fromDefaults().converter().fromMimeMessage(message(source)).toEmail();
            case "static-mime": return EmailConverter.mimeMessageToEmail(message(source));
            case "exact": return SimpleJavaMail.fromDefaults().emailBuilder().startingFromExactEml(source)
                    .withEnvelopeRecipients("reader@example.org").buildEmail();
            default: throw new AssertionError(route);
        }
    }

    private static MimeMessage message(final byte[] source) throws MessagingException {
        return new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(source));
    }

    private static byte[] source(final String type) {
        return ("From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Readable subject\r\n"
                + "Message-ID: <declaration@example.org>\r\nMIME-Version: 1.0\r\n"
                + (type == null ? "" : "Content-Type: " + type + "\r\n") + "\r\nOrdinary content\r\n").getBytes(UTF_8);
    }

    private static byte[] contradictorySource(final String type) {
        return ("From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Readable subject\r\n"
                + "Message-ID: <crossed@example.org>\r\nMIME-Version: 1.0\r\nContent-Type: " + type + "\r\n\r\n"
                + "--crossed\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nReadable neighboring body\r\n"
                + "--crossed--\r\n").getBytes(UTF_8);
    }

    private static void assertUnknown(final ProtectionInspection details) {
        assertThat(details.isInspectionComplete()).isFalse();
        assertThat(details.getSignatures()).isEmpty();
        assertThat(details.getDecryptions()).isEmpty();
    }
}
