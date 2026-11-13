package org.simplejavamail.internal.smimesupport;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.EncryptionStatus;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.ValueOrigin;
import org.simplejavamail.api.email.ProtectionRequirements;
import org.simplejavamail.api.email.ProtectionCoverage;
import org.simplejavamail.api.email.ValueAvailability;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import org.simplejavamail.api.converter.config.ImportLimitsConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;


import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class ReceivedEmailReadableViewTest {
    @Test
    void encryptedBodyAlternativeAndIndependentMessageKeepTheirLogicalOwners() throws Exception {
        final MimeBodyPart plain = new MimeBodyPart();
        plain.setText("Recovered parent plain", "UTF-8");
        final MimeBodyPart html = new MimeBodyPart();
        html.setContent("<p>Native parent HTML</p>", "text/html; charset=UTF-8");
        final MimeMultipart alternatives = new MimeMultipart("alternative");
        alternatives.addBodyPart(encrypted(plain));
        alternatives.addBodyPart(html);
        final MimeBodyPart parentBodies = new MimeBodyPart();
        parentBodies.setContent(alternatives);

        final MimeMessage child = new MimeMessage(session());
        child.setFrom("child@example.org");
        child.setSubject("Independent child");
        child.setHeader("Return-Path", "<child-bounce@example.org>");
        child.setText("Child content stays in child", "UTF-8");
        child.saveChanges();
        final MimeBodyPart childMessage = new MimeBodyPart();
        childMessage.setContent(child, "message/rfc822");
        final MimeBodyPart attached = encrypted(childMessage);
        attached.setDisposition("attachment");
        attached.setFileName("independent.p7m");
        final MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(parentBodies);
        mixed.addBodyPart(attached);
        final MimeMessage parent = new MimeMessage(session());
        parent.setFrom("parent@example.org");
        parent.setSubject("Parent");
        parent.setContent(mixed);
        parent.saveChanges();
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        parent.writeTo(output);

        final SimpleJavaMail mail = SimpleJavaMail.fromDefaults();
        final byte[] original = output.toByteArray();
        final byte[] protectedChild = attached.getInputStream().readAllBytes();
        final Email email = mail.converter().fromEml(original)
                .withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
        assertThat(email.getPlainText()).isEqualTo("Recovered parent plain");
        assertThat(email.getHTMLText()).isEqualTo("<p>Native parent HTML</p>");
        assertThat(email.getSubject()).isEqualTo("Parent");
        assertThat(email.getAttachments()).hasSize(1);
        assertThat(email.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        assertThat(email.getInspection().getHTMLText().getEncryptionStatus()).isEqualTo(EncryptionStatus.NOT_ENCRYPTED);
        assertThat(email.getInspection().getHTMLText().getSignatureStatus()).isEqualTo(SignatureStatus.UNSIGNED);
        assertThat(email.getInspection().getOriginalMessage().readAllBytes()).containsExactly(original);
        assertThat(email.getInspection().getOriginalAttachment(0).readAllBytes()).containsExactly(protectedChild);
        final AttachmentResource selectedChild = email.getAttachments().get(0);
        assertThat(selectedChild.getDataSource().getContentType()).startsWith("message/rfc822");
        assertThat(selectedChild.getEmail()).isNotNull().isSameAs(selectedChild.getEmail());
        assertThat(selectedChild.getEmail().getSubject()).isEqualTo("Independent child");
        assertThat(selectedChild.getEmail().getPlainText()).isEqualTo("Child content stays in child");
        assertThat(selectedChild.getEmail().getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        assertThat(selectedChild.getEmail().getBounceToRecipient().getAddress()).isEqualTo("child-bounce@example.org");
        assertThat(selectedChild.getEmail().getInspection().getBounceToRecipient().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        assertThat(ProtectionRequirements.builder().requireDecryptedContent().build().check(email.getInspection().getAttachment(0)).isSatisfied()).isTrue();
        final byte[] selectedPayload = selectedChild.readAllBytes();

        final Email copied = mail.emailBuilder().copying(email).buildEmail();
        assertThat(copied.getInspection().getOriginalMessage()).isSameAs(email.getInspection().getOriginalMessage());
        assertThat(copied.getInspection().getPlainText()).isSameAs(email.getInspection().getPlainText());
        final Email edited = mail.emailBuilder().copying(email).withPlainText(email.getPlainText()).buildEmail();
        assertThat(edited.getInspection().getPlainText().getOrigin()).isEqualTo(ValueOrigin.CALLER_SUPPLIED);
        assertThat(edited.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.UNKNOWN);
        assertThat(edited.getInspection().getHTMLText()).isSameAs(email.getInspection().getHTMLText());
        assertThat(selectedChild.readAllBytes()).containsExactly(selectedPayload);

        final Email forward = mail.emailBuilder().forwarding(email).from("forwarder@example.org").to("partner@example.org")
                .encryptWithSmime(SmimeEncryptionConfig.builder().x509Certificate(certificate()).build()).buildEmail();
        final byte[] protectedForward = mail.converter().fromEmail(forward).toEmlBytes();
        final Email interpretedForward = mail.converter().fromEml(protectedForward).withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
        final Email forwardedView = interpretedForward.getAttachments().get(0).getEmail();
        assertThat(forwardedView).isNotNull();
        assertThat(forwardedView.getPlainText()).isEqualTo("Recovered parent plain");
        assertThat(forwardedView.getAttachments().get(0).readAllBytes()).containsExactly(selectedPayload);

        final Email originalForward = mail.emailBuilder().forwardingOriginal(email).from("forwarder@example.org").to("partner@example.org").buildEmail();
        final Email originalForwardRead = mail.converter().fromEml(mail.converter().fromEmail(originalForward).toEmlBytes()).toEmail();
        assertThat(originalForwardRead.getAttachments().get(0).readAllBytes()).containsExactly(original);
    }

    @Test
    void repeatedLayersUseTheConfiguredKeysAndSharedDepthAllowance() throws Exception {
        final MimeBodyPart plain = new MimeBodyPart(); plain.setText("Deep readable content", "UTF-8");
        final MimeBodyPart nested = encrypted(encrypted(plain));
        final ByteArrayOutputStream source = new ByteArrayOutputStream(); nested.writeTo(source);
        final SimpleJavaMail mail = SimpleJavaMail.fromDefaults();
        final Email complete = mail.converter().fromEml(source.toByteArray()).withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
        assertThat(complete.getPlainText()).isEqualTo("Deep readable content");
        assertThat(complete.getInspection().getPlainText().getDecryptions()).hasSize(2);
        assertThat(complete.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        final Email bounded = mail.converter().fromEml(source.toByteArray()).withSmimeDecryption(loadPkcs12KeyStore())
                .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumNestingDepth(1).build()).toEmail();
        assertThat(bounded.getPlainText()).isNull();
        assertThat(bounded.getAttachments()).isEmpty();
        assertThat(bounded.getInspection().getPlainText().getEncryptionStatus()).isNotEqualTo(EncryptionStatus.DECRYPTED);
        assertThat(ProtectionRequirements.builder().requireDecryptedContent().build().check(bounded.getInspection().getPlainText()).isSatisfied()).isFalse();
        assertThat(bounded.getProblems()).anyMatch(problem -> problem.getCode() == org.simplejavamail.api.email.EmailProblem.Code.IMPORT_LIMIT_EXCEEDED);
    }

    @Test
    void independentBodyFragmentsKeepCollectiveAndPartialEncryptionCoverage() throws Exception {
        final MimeMultipart allProtected = new MimeMultipart("mixed");
        allProtected.addBodyPart(alternative(encrypted(plain("first"))));
        allProtected.addBodyPart(alternative(encrypted(plain("second"))));
        final Email recovered = receive(allProtected);
        assertThat(recovered.getPlainText()).isEqualTo("firstsecond");
        assertThat(recovered.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        assertThat(recovered.getInspection().getPlainText().getDecryptions()).hasSize(2)
                .allMatch(value -> value.getCoverage() == ProtectionCoverage.PARTIAL);
        assertThat(ProtectionRequirements.builder().requireDecryptedContent().build().check(recovered.getInspection().getPlainText()).isSatisfied()).isTrue();

        final MimeMultipart partial = new MimeMultipart("mixed");
        partial.addBodyPart(alternative(encrypted(plain("protected"))));
        partial.addBodyPart(plain("native"));
        final Email mixed = receive(partial);
        assertThat(mixed.getPlainText()).isEqualTo("protectednative");
        assertThat(mixed.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.UNKNOWN);
        assertThat(mixed.getInspection().getPlainText().getDecryptions()).hasSize(1)
                .allMatch(value -> value.getCoverage() == ProtectionCoverage.PARTIAL);
        assertThat(ProtectionRequirements.builder().requireDecryptedContent().build().check(mixed.getInspection().getPlainText()).isSatisfied()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void mixedProtectedFileRemainsIndependentWithoutAnAttachmentDisposition(final boolean inline) throws Exception {
        final MimeBodyPart file = plain("Private file payload"); file.setFileName("invoice.txt");
        final MimeBodyPart protectedFile = encrypted(file);
        if (inline) protectedFile.setDisposition("inline"); else protectedFile.removeHeader("Content-Disposition");
        final byte[] ciphertext = protectedFile.getInputStream().readAllBytes();
        final MimeMultipart parts = new MimeMultipart("mixed");
        parts.addBodyPart(plain("Public parent")); parts.addBodyPart(protectedFile);
        final Email email = receive(parts);
        assertThat(email.getPlainText()).isEqualTo("Public parent");
        assertThat(email.getAttachments()).hasSize(1);
        assertThat(email.getAttachments().get(0).getEmail()).isNull();
        assertThat(email.getAttachments().get(0).getName()).isEqualTo("invoice.txt");
        assertThat(email.getAttachments().get(0).readAllBytes()).isEqualTo("Private file payload".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThat(email.getInspection().getOriginalAttachment(0).readAllBytes()).containsExactly(ciphertext);
        assertThat(email.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(EncryptionStatus.NOT_ENCRYPTED);
        assertThat(email.getInspection().getAttachment(0).getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);
        assertThat(email.getInspection().getPlainText().getDecryptions()).isEmpty();
        final java.util.UUID opening = email.getInspection().getAttachment(0).getDecryptions().get(0).getReference();
        assertThat(email.getInspection().getSmime().getDecryptions()).singleElement()
                .satisfies(value -> assertThat(value.getReference()).isEqualTo(opening));
        final Email removed = SimpleJavaMail.fromDefaults().emailBuilder().copying(email).clearAttachments().buildEmail();
        assertThat(removed.getAttachments()).isEmpty();
        assertThat(removed.getInspection().getSmime().getDecryptions()).singleElement()
                .satisfies(value -> assertThat(value.getReference()).isEqualTo(opening));
    }

    @Test
    void generatedOutgoingIdentityHasCurrentDerivedInspectionAfterRehearsal() throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(testutil.ConfigLoaderTestHelper.emptyConfig());
        final Email source = mail.emailBuilder().startingBlank().from("sender@example.org").to("reader@example.org").withPlainText("body").buildEmail();
        assertThat(source.getId()).isNull();
        final org.simplejavamail.api.email.ValueInspection original = source.getInspection().getId();
        try (org.simplejavamail.api.mailer.Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 2525).buildMailer()) {
            final org.simplejavamail.api.mailer.MailRehearsal preview = mailer.rehearse(source);
            assertThat(preview.getEffectiveEmail().getId()).isEqualTo(preview.getEmailId()).isNotNull();
            assertThat(preview.getEffectiveEmail().getInspection().getId().getOrigin()).isEqualTo(ValueOrigin.DERIVED);
            assertThat(preview.getEffectiveEmail().getInspection().getId().getAvailability()).isEqualTo(ValueAvailability.PRESENT);
            assertThat(preview.getEffectiveEmail().getInspection().getId().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        }
        assertThat(source.getId()).isNull();
        assertThat(source.getInspection().getId()).isSameAs(original);
    }

    @Test
    void anIndependentNestedCiphertextStoppedByDepthIsNotReportedAsDecrypted() throws Exception {
        final MimeBodyPart file = plain("Nested private document"); file.setFileName("document.txt");
        final MimeBodyPart inner = encrypted(file);
        final byte[] innerCiphertext = inner.getInputStream().readAllBytes();
        final MimeBodyPart outer = encrypted(inner); outer.setDisposition("attachment");
        final MimeMultipart parts = new MimeMultipart("mixed"); parts.addBodyPart(plain("parent")); parts.addBodyPart(outer);
        final MimeMessage message = new MimeMessage(session()); message.setContent(parts); message.saveChanges();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(); message.writeTo(bytes);
        final Email email = SimpleJavaMail.fromDefaults().converter().fromEml(bytes.toByteArray()).withSmimeDecryption(loadPkcs12KeyStore())
                .withImportLimitsConfig(ImportLimitsConfig.builder().withMaximumNestingDepth(2).build()).toEmail();
        assertThat(email.getAttachments()).hasSize(1);
        assertThat(email.getAttachments().get(0).readAllBytes()).containsExactly(innerCiphertext);
        assertThat(email.getInspection().getAttachment(0).getEncryptionStatus()).isEqualTo(EncryptionStatus.ENCRYPTED);
        assertThat(ProtectionRequirements.builder().requireDecryptedContent().build().check(email.getInspection().getAttachment(0)).isSatisfied()).isFalse();
    }

    @Test
    void imageResolutionPreservesNoOpProtectionAndInvalidatesOnlyActualRewrites(@org.junit.jupiter.api.io.TempDir final java.nio.file.Path directory) throws Exception {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(testutil.ConfigLoaderTestHelper.emptyConfig());
        final ImageResolutionConfig images = ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForFiles(true)
                .withEmbeddedImageBaseDir(directory.toString()).build();
        final MimeBodyPart stableHtml = new MimeBodyPart(); stableHtml.setContent("<p>Protected HTML without external images</p>", "text/html; charset=UTF-8");
        final ByteArrayOutputStream stableBytes = new ByteArrayOutputStream(); encrypted(stableHtml).writeTo(stableBytes);
        final Email stable = mail.converter().fromEml(stableBytes.toByteArray()).withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
        final Email noOp = mail.emailBuilder().copying(stable).withImageResolutionConfig(images).buildEmail();
        assertThat(noOp.getHTMLText()).isEqualTo(stable.getHTMLText());
        assertThat(noOp.getInspection().getHTMLText()).isSameAs(stable.getInspection().getHTMLText());
        assertThat(noOp.getInspection().getHTMLText().getEncryptionStatus()).isEqualTo(EncryptionStatus.DECRYPTED);

        java.nio.file.Files.write(directory.resolve("document.png"), new byte[] {1, 2, 3, 4});
        final MimeBodyPart changingHtml = new MimeBodyPart(); changingHtml.setContent("<img src=\"document.png\">", "text/html; charset=UTF-8");
        final ByteArrayOutputStream changingBytes = new ByteArrayOutputStream(); encrypted(changingHtml).writeTo(changingBytes);
        final Email changing = mail.converter().fromEml(changingBytes.toByteArray()).withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
        final Email rewritten = mail.emailBuilder().copying(changing).withImageResolutionConfig(images).buildEmail();
        assertThat(rewritten.getHTMLText()).contains("cid:").doesNotContain("document.png");
        assertThat(rewritten.getInspection().getHTMLText().getOrigin()).isEqualTo(ValueOrigin.DERIVED);
        assertThat(rewritten.getInspection().getHTMLText().getEncryptionStatus()).isEqualTo(EncryptionStatus.UNKNOWN);
        assertThat(rewritten.getEmbeddedImages()).hasSize(1);
        assertThat(rewritten.getInspection().getEmbeddedImage(0).getOrigin()).isEqualTo(ValueOrigin.DERIVED);
        assertThat(rewritten.getInspection().getOriginalMessage()).isSameAs(changing.getInspection().getOriginalMessage());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realProtectedAuthoredHeadersDoNotInvokeDisplacedOuterGetters(final boolean emptyProtectedSubject) throws Exception {
        final MimeMessage inner = new MimeMessage(session());
        inner.setFrom("protected-author@example.org");
        inner.setRecipients(jakarta.mail.Message.RecipientType.TO, "protected-reader@example.org");
        if (emptyProtectedSubject) inner.setSubject("");
        inner.setText("Protected message body", "UTF-8"); inner.saveChanges();
        inner.setHeader("Message-ID", "<logical-protected@example.org>"); inner.removeHeader("Date");
        final MimeBodyPart wholeMessage = new MimeBodyPart(); wholeMessage.setContent(inner, "message/rfc822");
        final MimeBodyPart protectedContent = encrypted(wholeMessage);
        final MimeMessage outer = new MimeMessage(session());
        outer.setFrom("carrier@example.org"); outer.setRecipients(jakarta.mail.Message.RecipientType.CC, "carrier-copy@example.org");
        outer.setSubject("Public wrapper"); outer.setDataHandler(protectedContent.getDataHandler()); outer.saveChanges();
        final ByteArrayOutputStream wire = new ByteArrayOutputStream(); outer.writeTo(wire);
        final MimeMessage source = new MimeMessage(session(), new ByteArrayInputStream(wire.toByteArray())) {
            @Override public jakarta.mail.Address[] getFrom() { throw new AssertionError("Displaced carrier From getter"); }
            @Override public jakarta.mail.Address[] getReplyTo() { throw new AssertionError("Displaced carrier Reply-To getter"); }
            @Override public String getMessageID() { throw new AssertionError("Displaced carrier identity getter"); }
            @Override public String getSubject() throws jakarta.mail.MessagingException {
                if (emptyProtectedSubject) throw new AssertionError("Displaced carrier subject getter");
                return super.getSubject();
            }
        };
        final Email received = SimpleJavaMail.fromDefaults().converter().fromMimeMessage(source).withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
        assertThat(received.getFromRecipient().getAddress()).isEqualTo("protected-author@example.org");
        assertThat(received.getToRecipients()).extracting(org.simplejavamail.api.email.Recipient::getAddress).containsExactly("protected-reader@example.org");
        assertThat(received.getCcRecipients()).isEmpty();
        assertThat(received.getId()).isEqualTo("<logical-protected@example.org>");
        assertThat(received.getSentDate()).isNull();
        assertThat(received.getSubject()).isEqualTo(emptyProtectedSubject ? "" : "Public wrapper");
        assertThat(received.getInspection().getSubject().getEncryptionStatus()).isEqualTo(emptyProtectedSubject ? EncryptionStatus.DECRYPTED : EncryptionStatus.NOT_ENCRYPTED);
        assertThat(received.getInspection().getOriginalMessage()).isNull();
    }

    private static MimeBodyPart plain(final String text) throws Exception {
        final MimeBodyPart part = new MimeBodyPart(); part.setText(text, "UTF-8"); return part;
    }

    private static MimeBodyPart alternative(final MimeBodyPart content) throws Exception {
        final MimeMultipart alternatives = new MimeMultipart("alternative"); alternatives.addBodyPart(content);
        final MimeBodyPart part = new MimeBodyPart(); part.setContent(alternatives); return part;
    }

    private static Email receive(final MimeMultipart content) throws Exception {
        final MimeMessage message = new MimeMessage(session()); message.setFrom("parent@example.org"); message.setContent(content); message.saveChanges();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream(); message.writeTo(bytes);
        return SimpleJavaMail.fromDefaults().converter().fromEml(bytes.toByteArray()).withSmimeDecryption(loadPkcs12KeyStore()).toEmail();
    }

    private static MimeBodyPart encrypted(final MimeBodyPart content) throws Exception {
        return SmimeCryptoSupport.encrypt(content, certificate(),
                SmimeCryptoSupport.DEFAULT_KEY_ENCAPSULATION_ALGORITHM, SmimeCryptoSupport.DEFAULT_CIPHER);
    }

    private static X509Certificate certificate() throws Exception {
        final Pkcs12Config keys = loadPkcs12KeyStore();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        return (X509Certificate) store.getCertificate(keys.getKeyAlias());
    }

    private static Session session() {
        final Properties properties = new Properties();
        properties.setProperty("mail.host", "localhost");
        return Session.getInstance(properties);
    }
}
