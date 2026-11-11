package org.simplejavamail.converter;

import jakarta.activation.DataSource;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.converter.ConfiguredEmailConverter;
import org.simplejavamail.api.converter.config.ImportLimitsConfig;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.api.email.EmailSourceContent;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.SignatureVerification;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.config.ConfigLoader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImportLimitsTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @Test
    void rawLimitStopsAtOneLookaheadAndRetainsOnlyAnIncompletePrefix() {
        final byte[] eml = ("From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Available input\r\n\r\n" + "x".repeat(1000))
                .getBytes(US_ASCII);
        final CountingInput input = new CountingInput(eml);

        final Email result = mail.converter().fromEml(input)
                .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumInputBytes(64).build()).toEmail();

        assertThat(input.read).isEqualTo(65);
        assertThat(input.closed).isFalse();
        assertThat(result.getPlainText()).isNull();
        assertThat(result.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(result.getInspection().getOriginalMessage()).isNull();
        assertThat(result.getProblems()).singleElement().satisfies(problem ->
                assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.IMPORT_LIMIT_EXCEEDED));
        final EmailSourceContent prefix = result.getSourceData().getParts().get(0).getContent();
        assertThat(prefix.getSize()).isEqualTo(64);
        assertThat(prefix.isFullyCaptured()).isFalse();
        assertThat(prefix.readAllBytes()).isEqualTo(java.util.Arrays.copyOf(eml, 64));
    }

    @Test
    void aPartLimitKeepsCompletedTextAndDoesNotReadTheOmittedAttachment() throws Exception {
        final MimeMessage incoming = message();
        final MimeMultipart parts = new MimeMultipart("mixed");
        final MimeBodyPart body = new MimeBodyPart();
        body.setText("Readable neighbor", "UTF-8");
        parts.addBodyPart(body);
        final MimeBodyPart omitted = new MimeBodyPart();
        omitted.setDisposition("attachment");
        omitted.setFileName("omitted.bin");
        omitted.setDataHandler(new jakarta.activation.DataHandler(new DataSource() {
            @Override public InputStream getInputStream() { throw new AssertionError("Unadmitted content was read"); }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "application/octet-stream"; }
            @Override public String getName() { return "omitted.bin"; }
        }));
        parts.addBodyPart(omitted);
        incoming.setContent(parts);

        final Email result = mail.converter().fromMimeMessage(incoming)
                .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumPartCount(2).build()).toEmail();

        assertThat(result.getPlainText()).contains("Readable neighbor");
        assertThat(result.getAttachments()).isEmpty();
        assertThat(result.getProblems()).singleElement().satisfies(problem ->
                assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.IMPORT_LIMIT_EXCEEDED));
    }

    @Test
    void suppliedMimeRejectsRawSizeWithoutConsumptionAndCanBeCorrected() throws Exception {
        final MimeMessage incoming = message();
        incoming.setText("Already constructed");
        final ConfiguredEmailConverter.MimeMessageConversion conversion = mail.converter().fromMimeMessage(incoming)
                .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumInputBytes(1024).build());

        assertThatThrownBy(conversion::toEmail).isInstanceOf(IllegalStateException.class).hasMessageContaining("MimeMessage");
        assertThat(conversion.withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumPartCount(1).build())
                .toEmail().getPlainText()).contains("Already constructed");
    }

    @Test
    void depthStopsOneSubtreeAndContinuesPermittedSiblings() throws Exception {
        final MimeMessage incoming = message();
        final MimeMultipart root = new MimeMultipart("mixed");
        final MimeMultipart alternative = new MimeMultipart("alternative");
        final MimeBodyPart deepText = new MimeBodyPart();
        deepText.setText("Deeper text", "UTF-8");
        alternative.addBodyPart(deepText);
        final MimeBodyPart nested = new MimeBodyPart();
        nested.setContent(alternative);
        root.addBodyPart(nested);
        final MimeBodyPart neighbor = new MimeBodyPart();
        neighbor.setText("Permitted sibling", "UTF-8");
        root.addBodyPart(neighbor);
        incoming.setContent(root);

        final Email result = mail.converter().fromMimeMessage(incoming)
                .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumNestingDepth(2).build()).toEmail();

        assertThat(result.getPlainText()).contains("Permitted sibling").doesNotContain("Deeper text");
        assertThat(result.getProblems()).singleElement().satisfies(problem -> {
            assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.IMPORT_LIMIT_EXCEEDED);
            assertThat(problem.getSources()).hasSize(1);
        });
    }

    @Test
    void anExactlySizedRawSourceIsCompleteAndDirectTargetOptionsCanBeRestored() {
        final byte[] source = "From: sender@example.org\r\nTo: reader@example.org\r\nContent-Type: text/plain\r\n\r\nComplete body".getBytes(US_ASCII);
        final ImportLimitsConfig limits = ImportLimitsConfig.builder().withMaximumInputBytes(source.length).build();

        assertThat(mail.converter().fromEml(source).withImportLimitsConfig(limits).toEmail().getPlainText()).contains("Complete body");
        final ConfiguredEmailConverter.EmlConversion direct = mail.converter().fromEml(source).withImportLimitsConfig(limits);
        assertThatThrownBy(direct::toMimeMessage).isInstanceOf(IllegalStateException.class);
        assertThat(direct.useConfiguredImportLimitsConfig().toMimeMessage()).isNotNull();
    }

    @Test
    void nativeOutlookTraversalStopsBeforeMaterializingNestedMessages() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/test-messages/#298 Email with nested msg with own attachment.msg")) {
            final Email result = mail.converter().fromOutlookMsg(input)
                    .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumNestingDepth(2).build()).toEmail();

            assertThat(result.getSubject()).isNotBlank();
            assertThat(result.getAttachments()).noneMatch(resource -> resource.getName() != null && resource.getName().endsWith(".eml"));
            assertThat(result.getProblems()).anySatisfy(problem ->
                    assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.IMPORT_LIMIT_EXCEEDED));
            assertThat(result.getSourceData().getParts()).allSatisfy(part -> assertThat(part.getChildren()).isEmpty());
        }
    }

    @Test
    void aOneNodeOutlookBudgetRetainsHeadersWithoutCopyingBodyOrAttachmentParts() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/test-messages/HTML mail with replyto and attachment and embedded image.msg")) {
            final Email result = mail.converter().fromOutlookMsg(input)
                    .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumPartCount(1).build()).toEmail();

            assertThat(result.getSubject()).isNotBlank();
            assertThat(result.getPlainText()).isNull();
            assertThat(result.getHTMLText()).isNull();
            assertThat(result.getAttachments()).isEmpty();
            assertThat(result.getEmbeddedImages()).isEmpty();
            assertThat(result.getSourceData().getParts()).isEmpty();
            assertThat(result.getProblems()).anySatisfy(problem ->
                    assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.IMPORT_LIMIT_EXCEEDED));
        }
    }

    @Test
    void signedExtractionAndItsRetainedViewShareTheSameAllowance() {
        final Email draft = mail.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withPlainText("Readable signed body")
                .withAttachment("not-admitted.txt", "attachment content".getBytes(US_ASCII), "text/plain")
                .signWithSmime(SmimeSigningConfig.builder().pkcs12Config(org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore()).build())
                .buildEmail();
        final byte[] signed = mail.converter().fromEmail(draft).toEmlBytes();

        final Email result = mail.converter().fromEml(signed)
                .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumPartCount(3).build()).toEmail();

        assertThat(result.getPlainText()).contains("Readable signed body");
        assertThat(result.getAttachments()).noneMatch(resource -> "not-admitted.txt".equals(resource.getName()));
        assertThat(result.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.VERIFIED);
        assertThat(result.getInspection().getSmime().getSignatures()).extracting("status").containsOnly(SignatureVerification.VERIFIED);
        assertThat(result.getProblems()).anySatisfy(problem ->
                assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.IMPORT_LIMIT_EXCEEDED));
    }

    @Test
    void reportClassificationDoesNotInspectAnUnadmittedCandidate() throws Exception {
        final MimeMessage incoming = message();
        final MimeMultipart report = new MimeMultipart("report");
        final MimeBodyPart readable = new MimeBodyPart();
        readable.setText("Delivery notice", "UTF-8");
        report.addBodyPart(readable);
        final MimeBodyPart beyondLimit = new MimeBodyPart() {
            @Override public String getContentType() { throw new AssertionError("Unadmitted report candidate was inspected"); }
        };
        beyondLimit.setHeader("Content-Type", "message/delivery-status");
        report.addBodyPart(beyondLimit);
        incoming.setContent(report);
        incoming.setHeader("Content-Type", "multipart/report; report-type=delivery-status");

        final Email result = mail.converter().fromMimeMessage(incoming)
                .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumPartCount(2).build()).toEmail();

        assertThat(result.getPlainText()).contains("Delivery notice");
        assertThat(result.getProblems()).anySatisfy(problem ->
                assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.IMPORT_LIMIT_EXCEEDED));
        assertThat(result.getDeliveryStatusReport().getRecipients()).isEmpty();
    }

    @Test
    void anExactlyFullEncryptedMultipartBudgetCanReuseItsAlreadyAdmittedContent() throws Exception {
        final Pkcs12Config keys = org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore();
        final java.security.KeyStore store = java.security.KeyStore.getInstance("PKCS12");
        final char[] password = keys.getStorePassword();
        try (InputStream input = new ByteArrayInputStream(keys.getPkcs12StoreData())) {
            store.load(input, password);
        } finally {
            java.util.Arrays.fill(password, '\0');
        }
        final java.security.cert.X509Certificate certificate = (java.security.cert.X509Certificate) store.getCertificate(keys.getKeyAlias());
        final Email draft = mail.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org")
                .withPlainText("Already admitted encrypted body")
                .withAttachment("admitted.txt", "Allowed file".getBytes(US_ASCII), "text/plain")
                .encryptWithSmime(SmimeEncryptionConfig.builder().x509Certificate(certificate).build()).buildEmail();
        final byte[] encrypted = mail.converter().fromEmail(draft).toEmlBytes();

        final Email result = mail.converter().fromEml(encrypted).withSmimeDecryption(keys)
                .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumPartCount(4).build()).toEmail();

        assertThat(result.getPlainText()).contains("Already admitted encrypted body");
        assertThat(result.getAttachments()).filteredOn(resource -> "admitted.txt".equals(resource.getName())).singleElement()
                .satisfies(resource -> assertThat(resource.readAllBytes()).isEqualTo("Allowed file".getBytes(US_ASCII)));
        assertThat(result.getProblems()).noneMatch(problem -> problem.getCode() == EmailProblem.Code.IMPORT_LIMIT_EXCEEDED);
    }

    @Test
    void anEmptyOutlookFilenameRemainsAnArgumentErrorWithUnlimitedDefaults() {
        assertThatThrownBy(() -> mail.converter().fromOutlookMsg("").toEmail()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> EmailConverter.outlookMsgToEmail("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unlimitedAcquisitionKeepsTheSuppliedStreamsBulkReadContract() {
        final byte[] source = "From: sender@example.org\r\nTo: reader@example.org\r\n\r\nBulk content".getBytes(US_ASCII);
        final java.util.concurrent.atomic.AtomicInteger singleReads = new java.util.concurrent.atomic.AtomicInteger();
        final InputStream input = new InputStream() {
            private final ByteArrayInputStream bytes = new ByteArrayInputStream(source);
            @Override public int read(final byte[] buffer) { return bytes.read(buffer, 0, buffer.length); }
            @Override public int read() { singleReads.incrementAndGet(); return bytes.read(); }
        };

        assertThat(mail.converter().fromEml(input).toEmail().getPlainText()).contains("Bulk content");
        assertThat(singleReads).hasValue(0);
    }

    @Test
    void anEmptyMultipartDoesNotInventWorkBeyondItsRootOnlyAllowance() throws Exception {
        final MimeMessage empty = message();
        empty.setContent(new MimeMultipart("mixed"));

        for (ImportLimitsConfig limits : java.util.List.of(
                ImportLimitsConfig.builder().withMaximumPartCount(1).build(),
                ImportLimitsConfig.builder().withMaximumNestingDepth(1).build())) {
            final Email result = mail.converter().fromMimeMessage(empty).withImportLimitsConfig(limits).toEmail();
            assertThat(result.getSubject()).isEqualTo("Import limits");
            assertThat(result.getProblems()).noneMatch(problem -> problem.getCode() == EmailProblem.Code.IMPORT_LIMIT_EXCEEDED);
        }
    }

    @Test
    void aDepthStopSummarizesAnUnvisitedWideSubtreeOnce() throws Exception {
        final MimeMessage incoming = message();
        final MimeMultipart wide = new MimeMultipart("mixed");
        for (int i = 0; i < 1000; i++) {
            final MimeBodyPart child = new MimeBodyPart();
            child.setText("Not visited");
            wide.addBodyPart(child);
        }
        incoming.setContent(wide);

        final Email result = mail.converter().fromMimeMessage(incoming)
                .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumNestingDepth(1).build()).toEmail();

        assertThat(result.getSourceData().getParts().get(0).getChildren()).isEmpty();
        assertThat(result.getProblems()).filteredOn(problem -> problem.getCode() == EmailProblem.Code.IMPORT_LIMIT_EXCEEDED).hasSize(1);
    }

    @Test
    void aRequiredSessionIsValidatedBeforeRawAcquisition() {
        final InputStream unread = new InputStream() {
            @Override public int read() { throw new AssertionError("Input read before required Session was checked"); }
        };

        assertThatThrownBy(() -> EmailConverter.emlToEmailBuilder(unread, (Pkcs12Config) null, (Session) null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void concurrentImportsReuseChoicesWithoutSharingTheAllowance() throws Exception {
        final byte[] source = ("From: sender@example.org\r\nTo: reader@example.org\r\nMIME-Version: 1.0\r\n"
                + "Content-Type: multipart/mixed; boundary=concurrent\r\n\r\n"
                + "--concurrent\r\nContent-Type: text/plain\r\n\r\nConcurrent body\r\n"
                + "--concurrent\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=file.bin\r\n"
                + "Content-Transfer-Encoding: base64\r\n\r\nAQID\r\n--concurrent--\r\n").getBytes(US_ASCII);
        final ImportLimitsConfig limits = ImportLimitsConfig.builder().withMaximumPartCount(3).withMaximumNestingDepth(2).build();
        final java.util.concurrent.ExecutorService callers = java.util.concurrent.Executors.newFixedThreadPool(2);
        final java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        final java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try {
            final java.util.List<java.util.concurrent.Future<Email>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 2; i++) {
                results.add(callers.submit(() -> {
                    ready.countDown();
                    assertThat(start.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
                    return mail.converter().fromEml(source).withImportLimitsConfig(limits).toEmail();
                }));
            }
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (java.util.concurrent.Future<Email> result : results) {
                final Email email = result.get(10, java.util.concurrent.TimeUnit.SECONDS);
                assertThat(email.getPlainText()).contains("Concurrent body");
                assertThat(email.getAttachments()).hasSize(1);
                assertThat(email.getProblems()).noneMatch(problem -> problem.getCode() == EmailProblem.Code.IMPORT_LIMIT_EXCEEDED);
            }
        } finally {
            start.countDown();
            callers.shutdownNow();
            assertThat(callers.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rawLimitsUseThePathsOwnFileSystem(@TempDir final java.nio.file.Path directory) throws Exception {
        final byte[] source = "From: sender@example.org\r\nTo: reader@example.org\r\n\r\nFilesystem body".getBytes(US_ASCII);
        final java.net.URI uri = java.net.URI.create("jar:" + directory.resolve("messages.zip").toUri());
        try (java.nio.file.FileSystem filesystem = java.nio.file.FileSystems.newFileSystem(uri, java.util.Map.of("create", "true"))) {
            final java.nio.file.Path message = java.nio.file.Files.write(filesystem.getPath("/mail.eml"), source);
            assertThat(mail.converter().fromEml(message)
                    .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumInputBytes(source.length).build())
                    .toEmail().getPlainText()).contains("Filesystem body");
            final Email limited = mail.converter().fromEml(message)
                    .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumInputBytes(16).build()).toEmail();
            assertThat(limited.getSourceData().getParts().get(0).getContent().getSize()).isEqualTo(16);
            assertThat(limited.getProblems()).singleElement().satisfies(problem ->
                    assertThat(problem.getCode()).isEqualTo(EmailProblem.Code.IMPORT_LIMIT_EXCEEDED));
        }
    }

    private static MimeMessage message() throws Exception {
        final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        message.setFrom("sender@example.org");
        message.setRecipients(jakarta.mail.Message.RecipientType.TO, "reader@example.org");
        message.setSubject("Import limits");
        return message;
    }

    private static final class CountingInput extends ByteArrayInputStream {
        private int read;
        private boolean closed;
        private CountingInput(final byte[] bytes) { super(bytes); }
        @Override public synchronized int read(final byte[] buffer, final int offset, final int length) {
            final int count = super.read(buffer, offset, length);
            if (count > 0) read += count;
            return count;
        }
        @Override public synchronized int read() {
            final int next = super.read();
            if (next >= 0) read++;
            return next;
        }
        @Override public void close() throws IOException { closed = true; super.close(); }
    }
}
