package org.simplejavamail.converter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.simplejavamail.MailException;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourcePart;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.EmailSourceProperty;
import org.simplejavamail.api.converter.EmailConversionResult;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.internal.modules.SMIMEModule;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import testutil.ConfigLoaderTestHelper;

import jakarta.activation.DataSource;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.ObjectStreamClass;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CancellationException;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_16LE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class ConversionFollowupBoundaryTest {

    @TempDir Path directory;

    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final String BROKEN_SIGNED_CHILD = "From: sender@example.org\r\nTo: reader@example.org\r\n"
            + "Message-ID: <parent@example.org>\r\nSubject: Readable parent\r\nMIME-Version: 1.0\r\n"
            + "Content-Type: multipart/mixed; boundary=parts\r\n\r\n"
            + "--parts\r\nContent-Type: text/plain; charset=US-ASCII\r\n\r\nReadable body\r\n"
            + "--parts\r\nContent-Type: application/pkcs7-mime; smime-type=signed-data\r\n"
            + "Content-Disposition: attachment; filename=broken.p7m\r\n\r\nnot CMS\r\n"
            + "--parts\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=good.bin\r\n"
            + "\r\nusable sibling\r\n--parts--\r\n";

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void automaticMalformedSignedChildKeepsReadableParentAndLinksItsProblem(final boolean fetchContent) {
        final Email received = MAIL.converter().fromEml(BROKEN_SIGNED_CHILD)
                .withFetchAttachmentData(fetchContent).toEmail();

        assertThat(received.getPlainText()).contains("Readable body");
        assertThat(received.getAttachments()).extracting(resource -> resource.getName()).containsExactly("broken.p7m", "good.bin");
        assertThat(received.getAttachments().get(0).readAllBytes()).isEqualTo("not CMS".getBytes(US_ASCII));
        assertThat(received.getAttachments().get(1).readAllBytes()).isEqualTo("usable sibling".getBytes(US_ASCII));
        assertThat(received.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.PLAIN);
        assertThat(received.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.PROTECTION_FAILED);
            assertThat(problem.getOperation()).isEqualTo(EmailProblem.Operation.IMPORT);
            assertThat(problem.getOrigin()).isSameAs(received.getSourceData().getOrigin());
            final EmailSourcePart child = received.getSourceData().getParts().get(0).getChildren().get(1);
            assertThat(problem.getSources()).containsExactly(child);
            assertThat(child.getHeaders()).anySatisfy(header -> {
                assertThat(header.getName()).isEqualToIgnoringCase("Content-Disposition");
                assertThat(header.getRawValue()).contains("broken.p7m");
            });
        });
    }

    @Test
    void combinedStaticMsgArtifactsDoNotExportFailedAcquisitionAsEmptyMail() {
        assertThatThrownBy(() -> EmailConverter.outlookMsgToEML(new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(MailException.class);
        assertThatThrownBy(() -> EmailConverter.outlookMsgToMimeMessage(new ByteArrayInputStream(new byte[0])))
                .isInstanceOf(MailException.class);
    }

    enum Target { MIME, EML }

    @ParameterizedTest
    @EnumSource(Target.class)
    void staticMsgTargetsPreserveFailureAcrossFileAndStringEntryPoints(final Target target) throws Exception {
        final Path malformed = Files.write(directory.resolve("malformed.msg"), "not MSG".getBytes(US_ASCII));
        final Path missing = directory.resolve("missing.msg");
        for (final Path path : Arrays.asList(malformed, missing)) {
            assertThatThrownBy(() -> {
                if (target == Target.MIME) EmailConverter.outlookMsgToMimeMessage(path.toFile());
                else EmailConverter.outlookMsgToEML(path.toFile());
            }).isInstanceOf(MailException.class);
            assertThatThrownBy(() -> {
                if (target == Target.MIME) EmailConverter.outlookMsgToMimeMessage(path.toString());
                else EmailConverter.outlookMsgToEML(path.toString());
            }).isInstanceOf(MailException.class);
        }
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void staticMsgTargetsRetainReadCauseSuppressedCloseAndStreamOwnership(final Target target) {
        final IOException reading = new IOException("source unavailable");
        final IOException closing = new IOException("close unavailable");
        final CountingInput input = new CountingInput(new byte[] {1, 2, 3}, reading, closing);
        assertThatThrownBy(() -> staticArtifact(target, input)).isInstanceOfSatisfying(MailException.class, failure -> {
            assertThat(failure).hasCause(reading);
            assertThat(failure.getSuppressed()).containsExactly(closing);
        });
        assertThat(input.closed).isEqualTo(1);
    }

    @Test
    void inspectionThenExplicitExportStillAllowsSourceOnlyEmail() {
        final Email inspected = EmailConverter.outlookMsgToEmail(new ByteArrayInputStream(new byte[0]));
        assertThat(inspected.getProblems()).isNotEmpty();
        assertThat(EmailConverter.emailToEML(inspected)).contains("MIME-Version:");
        assertThat(MAIL.converter().fromEmail(inspected).toMimeMessageResult().hasValue()).isTrue();
        final EmailConversionResult<String> combined = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(new byte[0])).toEmlResult();
        assertThat(combined.hasValue()).isFalse();
        assertThat(combined.getFailure()).isNotNull();
        assertThat(combined.getProblems()).isNotEmpty();
    }

    @ParameterizedTest
    @EnumSource(Target.class)
    void staticAndFluentHealthyMsgArtifactsKeepContentAndExistingClosingContracts(final Target target) throws Exception {
        final byte[] bytes;
        try (InputStream fixture = getClass().getResourceAsStream("/test-messages/simple email with TO and CC.msg")) {
            bytes = fixture.readAllBytes();
        }
        final CountingInput owned = new CountingInput(bytes, null, null);
        final Object artifact = staticArtifact(target, owned);
        final Path file = Files.write(directory.resolve("healthy.msg"), bytes);
        for (final Object value : Arrays.asList(artifact,
                target == Target.MIME ? EmailConverter.outlookMsgToMimeMessage(file.toFile()) : EmailConverter.outlookMsgToEML(file.toFile()),
                target == Target.MIME ? EmailConverter.outlookMsgToMimeMessage(file.toString()) : EmailConverter.outlookMsgToEML(file.toString()))) {
            final Email parsed = target == Target.MIME ? EmailConverter.mimeMessageToEmail((MimeMessage) value) : EmailConverter.emlToEmail((String) value);
            assertThat(parsed.getSubject()).isEqualTo("Test E-Mail");
            assertThat(parsed.getPlainText()).contains("Just a test");
        }
        assertThat(owned.closed).isEqualTo(1);
        final CountingInput borrowed = new CountingInput(bytes, null, null);
        if (target == Target.MIME) assertThat(MAIL.converter().fromOutlookMsg(borrowed).toMimeMessageResult().hasValue()).isTrue();
        else assertThat(MAIL.converter().fromOutlookMsg(borrowed).toEmlResult().hasValue()).isTrue();
        assertThat(borrowed.closed).isZero();

        final CountingInput closeFailure = new CountingInput(bytes, null, new IOException("close failed"));
        assertThat(staticArtifact(target, closeFailure)).isNotNull();
        assertThat(closeFailure.closed).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void duplicateNamesAndSiblingOrderDoNotDetermineTheProblemSource(final boolean brokenFirst) {
        final String malformed = part("application/pkcs7-mime; smime-type=signed-data", "same.p7m", "not CMS");
        final String healthy = part("application/octet-stream", "same.p7m", "usable sibling");
        final String eml = mixed(brokenFirst ? malformed + healthy : healthy + malformed);
        final Email received = MAIL.converter().fromEml(eml).toEmail();
        assertThat(received.getAttachments()).hasSize(2);
        final EmailSourcePart affected = received.getSourceData().getParts().get(0).getChildren().get(brokenFirst ? 1 : 2);
        assertThat(received.getProblems()).singleElement().satisfies(problem -> assertThat(problem.getSources()).containsExactly(affected));
        assertThat(received.getAttachments().get(brokenFirst ? 0 : 1).readAllData(US_ASCII)).isEqualTo("not CMS");
        assertThat(received.getAttachments().get(brokenFirst ? 1 : 0).readAllData(US_ASCII)).isEqualTo("usable sibling");
    }

    @Test
    void repeatedMalformedNamesKeepDistinctProblemOccurrences() {
        final String malformed = part("application/pkcs7-mime; smime-type=signed-data", "same.p7m", "not CMS");
        final Email received = MAIL.converter().fromEml(mixed(malformed + malformed)).toEmail();
        assertThat(received.getAttachments()).hasSize(2);
        assertThat(received.getProblems()).hasSize(2);
        assertThat(received.getProblems().get(0).getSources()).containsExactly(received.getSourceData().getParts().get(0).getChildren().get(1));
        assertThat(received.getProblems().get(1).getSources()).containsExactly(received.getSourceData().getParts().get(0).getChildren().get(2));
    }

    @Test
    void healthySignedChildStillCommitsItsEstablishedLegacyFacts() throws Exception {
        final Email signed = MAIL.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withPlainText("Signed child content")
                .signWithSmime(SmimeSigningConfig.builder().pkcs12Config(loadPkcs12KeyStore()).build()).buildEmail();
        final MimeMessage protectedMessage = EmailConverter.emailToMimeMessage(signed);
        final ByteArrayOutputStream payload = new ByteArrayOutputStream();
        ((jakarta.mail.internet.MimeMultipart) protectedMessage.getContent()).writeTo(payload);
        final Email parent = MAIL.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withPlainText("Readable parent").withAttachment("signed.p7m", payload.toByteArray(), protectedMessage.getContentType()).buildEmail();
        final Email received = MAIL.converter().fromMimeMessage(EmailConverter.emailToMimeMessage(parent)).toEmail();
        assertThat(received.getProblems()).isEmpty();
        assertThat(received.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.VALID);
        assertThat(received.getPlainText()).contains("Readable parent");
        // The legacy materializer retains a signed attachment whose clear entity is single-part text.
        assertThat(received.getSmimeSignedEmail()).isNull();
        assertThat(received.getAttachments()).singleElement().satisfies(resource -> assertThat(resource.readAllBytes()).isEqualTo(payload.toByteArray()));
    }

    @Test
    void soleMalformedChildDoesNotPromoteFailedVerificationToItsParentAndSurvivesCopies() throws Exception {
        final Email received = EmailConverter.emlToEmail(mixed(part("application/pkcs7-mime; smime-type=signed-data", "broken.p7m", "not CMS")));
        assertThat(received.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.PLAIN);
        assertThat(received.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.NOT_SIGNED);
        assertThat(received.getOriginalSmimeDetails().getSmimeSignatureValid()).isNull();
        final Email copy = MAIL.emailBuilder().copying(received).fixingMessageId("<copy@example.org>").buildEmail();
        assertThat(copy.getSourceData()).isSameAs(received.getSourceData());
        assertThat(copy.getProblems()).containsExactlyElementsOf(received.getProblems());
        final Email restored = restoreWithoutOutlook(copy);
        assertThat(restored.getAttachments().get(0).readAllData(US_ASCII)).isEqualTo("not CMS");
        assertThat(restored.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getSources()).containsExactly(restored.getSourceData().getParts().get(0).getChildren().get(1));
            assertThat(problem.getOrigin().getMessageId()).isEqualTo("<parent@example.org>");
        });
    }

    @Test
    void actualMsgContainerLinksTheFailedNativeOccurrencePastInlineAndDuplicateNamedSiblings() throws Exception {
        final byte[] msg = malformedSignedMsg();
        final Email received = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(msg)).toEmail();
        assertThat(received.getPlainText()).contains("Just a test");
        assertThat(received.getEmbeddedImages()).hasSize(1);
        assertThat(received.getAttachments()).hasSize(2);
        assertThat(received.getAttachments()).extracting(resource -> resource.readAllData(US_ASCII))
                .containsExactlyInAnyOrder("not CMS", "usable sibling");
        final EmailSourcePart affected = received.getSourceData().getParts().stream()
                .filter(source -> source.getProperties().stream().anyMatch(property -> "mimeTag".equals(property.getName())
                        && "application/pkcs7-mime; smime-type=signed-data".equals(property.getValue())))
                .findFirst().orElseThrow(AssertionError::new);
        assertThat(received.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.PROTECTION_FAILED);
            assertThat(problem.getSources()).containsExactly(affected);
            assertThat(problem.getOrigin()).isSameAs(received.getSourceData().getOrigin());
        });
        final Email restored = restoreWithoutOutlook(received);
        assertThat(restored.getProblems()).singleElement().satisfies(problem -> assertThat(problem.getSources()).singleElement()
                .satisfies(source -> assertThat(((EmailSourcePart) source).getProperties()).extracting(EmailSourceProperty::getValue)
                        .contains("application/pkcs7-mime; smime-type=signed-data")));
        assertThat(EmailConverter.outlookMsgToEML(new ByteArrayInputStream(msg))).contains("Test E-Mail");
    }

    @Test
    void directSmimeHelperAndExplicitDecryptionKeepTheirFailureBoundary() {
        final SMIMEModule smime = ModuleLoader.loadSmimeModule().createRuntime(org.simplejavamail.config.ConfigLoader.DEFAULT_SMIME_KEY_CACHE_CAPACITY);
        final AttachmentResource malformed = AttachmentResource.builder().withName("broken.p7m")
                .withData("not CMS".getBytes(US_ASCII), "application/pkcs7-mime; smime-type=signed-data").build();
        final Email parent = MAIL.emailBuilder().startingBlank().buildEmail();
        assertThatThrownBy(() -> smime.decryptAttachments(Collections.singletonList(malformed), null, parent.getOriginalSmimeDetails()))
                .isInstanceOf(MailException.class);
        final Pkcs12Config explicit = Pkcs12Config.builder().pkcs12Store(new byte[] {1}).storePassword("").keyAlias("unused").keyPassword("").build();
        assertThatThrownBy(() -> MAIL.converter().fromEml(BROKEN_SIGNED_CHILD).withSmimeDecryption(explicit).toEmail())
                .isInstanceOf(MailException.class);
    }

    @Test
    void applicationReaderRuntimeAndCancellationDoNotBecomeChildProblems() {
        for (final RuntimeException failure : Arrays.asList(new IllegalStateException("application reader failed"), new CancellationException("stop"))) {
            final DataSource source = new DataSource() {
                @Override public InputStream getInputStream() { throw failure; }
                @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
                @Override public String getContentType() { return "application/pkcs7-mime; smime-type=signed-data"; }
                @Override public String getName() { return "broken.p7m"; }
            };
            final MimeMessage message = EmailConverter.emailToMimeMessage(MAIL.emailBuilder().startingBlank()
                    .from("sender@example.org").to("reader@example.org").withPlainText("Readable body")
                    .withAttachment("broken.p7m", source).buildEmail());
            assertThatThrownBy(() -> MAIL.converter().fromMimeMessage(message).withFetchAttachmentData(false).toEmail())
                    .satisfies(thrown -> {
                        Throwable current = thrown;
                        while (current != failure && current.getCause() != null) current = current.getCause();
                        assertThat(current).isSameAs(failure);
                    });
        }
    }

    private static String mixed(final String attachments) {
        return "From: sender@example.org\r\nMessage-ID: <parent@example.org>\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=parts\r\n\r\n"
                + "--parts\r\nContent-Type: text/plain\r\n\r\nReadable body\r\n" + attachments + "--parts--\r\n";
    }

    private static String part(final String type, final String name, final String body) {
        return "--parts\r\nContent-Type: " + type + "\r\nContent-Disposition: attachment; filename=" + name + "\r\n\r\n" + body + "\r\n";
    }

    private static Object staticArtifact(final Target target, final InputStream input) {
        return target == Target.MIME ? EmailConverter.outlookMsgToMimeMessage(input) : EmailConverter.outlookMsgToEML(input);
    }

    private static Email restoreWithoutOutlook(final Email email) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(email); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
            @Override protected Class<?> resolveClass(final ObjectStreamClass descriptor) throws IOException, ClassNotFoundException {
                if (descriptor.getName().startsWith("org.simplejavamail.internal.outlooksupport.")
                        || descriptor.getName().startsWith("org.simplejavamail.outlookmessageparser.")) throw new ClassNotFoundException(descriptor.getName());
                return super.resolveClass(descriptor);
            }
        }) { return (Email) input.readObject(); }
    }

    private static byte[] malformedSignedMsg() throws Exception {
        try (InputStream input = ConversionFollowupBoundaryTest.class.getResourceAsStream("/test-messages/simple email with TO and CC.msg");
                POIFSFileSystem file = new POIFSFileSystem(input)) {
            final DirectoryEntry root = file.getRoot();
            for (final String name : Arrays.asList("__substg1.0_1013001F", "__substg1.0_1013001E", "__substg1.0_10130102")) {
                if (root.hasEntry(name)) root.getEntry(name).delete();
            }
            stringProperty(root, "1013", "<p>Readable HTML</p><img src=\"cid:logo\">");
            for (int index = 0; index < 3; index++) {
                final DirectoryEntry attachment = root.createDirectory(String.format("__attach_version1.0_#%08X", index));
                attachment.createDocument("__substg1.0_37010102", new ByteArrayInputStream((index == 0 ? "image bytes" : index == 1 ? "not CMS" : "usable sibling").getBytes(US_ASCII)));
                stringProperty(attachment, "3707", index == 0 ? "logo.png" : "same.p7m");
                stringProperty(attachment, "370E", index == 0 ? "image/png" : index == 1 ? "application/pkcs7-mime; smime-type=signed-data" : "application/octet-stream");
                if (index == 0) stringProperty(attachment, "3712", "logo");
            }
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            file.writeFilesystem(output);
            return output.toByteArray();
        }
    }

    private static void stringProperty(final DirectoryEntry entry, final String property, final String value) throws IOException {
        entry.createDocument("__substg1.0_" + property + "001F", new ByteArrayInputStream((value + '\0').getBytes(UTF_16LE)));
    }

    private static final class CountingInput extends InputStream {
        private final ByteArrayInputStream content;
        private final IOException reading;
        private final IOException closing;
        private int closed;
        private CountingInput(final byte[] bytes, final IOException reading, final IOException closing) {
            content = new ByteArrayInputStream(bytes); this.reading = reading; this.closing = closing;
        }
        @Override public int read() throws IOException {
            if (content.available() == 0 && reading != null) throw reading;
            return content.read();
        }
        @Override public int read(final byte[] bytes, final int off, final int len) throws IOException {
            if (content.available() == 0 && reading != null) throw reading;
            return content.read(bytes, off, len);
        }
        @Override public void close() throws IOException { closed++; if (closing != null) throw closing; }
    }
}
