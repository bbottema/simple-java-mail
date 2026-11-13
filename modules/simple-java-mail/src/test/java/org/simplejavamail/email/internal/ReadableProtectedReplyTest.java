package org.simplejavamail.email.internal;

import jakarta.activation.DataSource;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.MailException;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpReceiveConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.converter.EmailConverter;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.EmailStartingBuilder.DEFAULT_QUOTING_MARKUP;
import static org.simplejavamail.api.email.EncryptionStatus.DECRYPTED;
import static org.simplejavamail.api.email.SignatureStatus.VERIFIED;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

class ReadableProtectedReplyTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final String PLAIN = "Please ship the protected order.";
    private static final String HTML = "<p>Please <b>ship</b> the protected order. <img src=\"cid:logo@example.org\" alt=\"Parcel logo\"></p>";
    private static final String CUSTOM = "<section class=\"original\">%s</section>";
    private static Pkcs12Config keys;
    private static SmimeSigningConfig smimeSigning;
    private static SmimeEncryptionConfig smimeEncryption;
    private static OpenPgpSigningConfig pgpSigning;
    private static OpenPgpEncryptionConfig pgpEncryption;
    private static OpenPgpReceiveConfig pgpReceiving;

    @BeforeAll
    static void keys() throws Exception {
        keys = loadPkcs12KeyStore();
        final KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
        final X509Certificate certificate = (X509Certificate) store.getCertificate(keys.getKeyAlias());
        smimeSigning = SmimeSigningConfig.builder().pkcs12Config(keys).build();
        smimeEncryption = SmimeEncryptionConfig.builder().x509Certificate(certificate).build();
        final byte[] publicKey = resource("public-key.asc");
        final byte[] privateKey = resource("private-key.asc");
        pgpEncryption = OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(publicKey).build();
        pgpSigning = OpenPgpSigningConfig.builder().secretKeyRing(privateKey).passphrase("openpgpjs-fixture-passphrase").build();
        pgpReceiving = OpenPgpReceiveConfig.builder().addVerificationKeyRing(publicKey)
                .addDecryptionKeyRing(privateKey, "openpgpjs-fixture-passphrase").build();
    }

    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("protectedRoutes")
    void outgoingEncryptionDoesNotHideReadableContentWhenPrimingAnyEmailReply(final Protection protection, final Route route) {
        final Email original = protection.encrypt(original().withPlainText(PLAIN).withHTMLText(HTML)
                .withEmbeddedImage(image("logo@example.org", "logo.png", new byte[] {1, 2, 3}))
                .withEmbeddedImage(image("unused@example.org", "unused.png", new byte[] {4, 5}))
                .withAttachment("invoice.bin", new byte[] {6, 7}, "application/octet-stream")).buildEmail();
        final Email reply = route.reply(original).buildEmail();

        assertThat(reply.getPlainText()).isEqualTo("> " + PLAIN);
        assertThat(reply.getHTMLText()).isEqualTo(String.format(route.custom ? CUSTOM : DEFAULT_QUOTING_MARKUP, HTML));
        assertRouting(reply, route.all);
        assertThat(reply.getEmbeddedImages()).extracting(AttachmentResource::getContentId).containsExactly("logo@example.org");
        assertThat(reply.getAttachments()).isEmpty();
        assertThat(original.getPlainText()).isEqualTo(PLAIN);
        assertThat(original.getHTMLText()).isEqualTo(HTML);
        assertThat(original.getEmbeddedImages()).hasSize(2);
        assertThat(original.getOverrideReceivers()).extracting(Recipient::getAddress).containsExactly("test-inbox@example.org");
        protection.assertConfigured(original);
        assertThat(reply.getSmimeEncryptionConfig()).isNull();
        assertThat(reply.getOpenPgpEncryptionConfig()).isNull();
    }

    static Stream<Arguments> protectedRoutes() {
        return Arrays.stream(Protection.values()).flatMap(protection -> Arrays.stream(Route.values())
                .map(route -> Arguments.of(protection, route)));
    }

    @Test
    void encryptedPlainOnlyOriginalStillGeneratesItsMissingHtmlQuote() {
        final Email original = original().withPlainText("Ship <parcel> & box\nNext line.")
                .encryptWithSmime(smimeEncryption).buildEmail();
        final Email reply = MAIL.emailBuilder().replyingTo(original, CUSTOM).buildEmail();
        assertThat(reply.getPlainText()).isEqualTo("> Ship <parcel> & box\n> Next line.");
        assertThat(reply.getHTMLText()).isEqualTo("<section class=\"original\">Ship &lt;parcel&gt; &amp; box<br>Next line.</section>");
    }

    @Test
    void encryptedHtmlOnlyOriginalStillGeneratesItsMissingPlainQuote() {
        final Email original = original().withHTMLText("<p>Ship <b>parcel</b>.</p>").encryptWithOpenPgp(pgpEncryption).buildEmail();
        final Email reply = MAIL.emailBuilder().replyingToAll(original).buildEmail();
        assertThat(reply.getPlainText()).isEqualTo("> Ship parcel.");
        assertThat(reply.getHTMLText()).isEqualTo(String.format(DEFAULT_QUOTING_MARKUP, "<p>Ship <b>parcel</b>.</p>"));
    }

    @ParameterizedTest
    @EnumSource(Protection.class)
    void originalAndExplicitlyProtectedReplyStillUseRealOutgoingProtection(final Protection protection) {
        final Email original = protection.signAndEncrypt(original().withPlainText(PLAIN)).buildEmail();
        final Email reply = protection.signAndEncrypt(MAIL.emailBuilder().replyingTo(original)
                .from("bob@example.org").prependPlainText("Answer.\n\n")).buildEmail();

        final Email openedOriginal = protection.open(MAIL.converter().fromEmail(original).toEmlBytes());
        final Email openedReply = protection.open(MAIL.converter().fromEmail(reply).toEmlBytes());
        assertThat(openedOriginal.getPlainText()).isEqualTo(PLAIN);
        assertThat(openedReply.getPlainText()).contains("Answer.", "> " + PLAIN);
        protection.assertConfigured(original);
        protection.assertConfigured(reply);
    }

    @Test
    void normalReplyGovernanceStillAppliesProtectionAndLockedRecipients() throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("simplejavamail.locked.defaults.bcc.address", "archive@example.org");
        final SimpleJavaMail governed = SimpleJavaMail.withConfig(ConfigLoader.builder().withProperties(properties).load());
        final Email defaults = MAIL.emailBuilder().startingBlank().signWithSmime(smimeSigning).buildEmail();
        final Email overrides = MAIL.emailBuilder().startingBlank().encryptWithSmime(smimeEncryption).buildEmail();
        final Email original = original().withPlainText(PLAIN).encryptWithSmime(smimeEncryption).buildEmail();
        final Email reply = governed.emailBuilder().replyingTo(original).from("bob@example.org").prependPlainText("Answer.\n\n").buildEmail();
        try (Mailer mailer = governed.mailerBuilder().withSMTPServerHost("localhost")
                .withProperty("mail.host", "localhost")
                .withEmailDefaults(defaults).withEmailOverrides(overrides).buildMailer()) {
            final org.simplejavamail.api.mailer.MailRehearsal prepared = mailer.rehearse(reply);
            assertThat(prepared.getEffectiveEmail().getRecipients()).extracting(Recipient::getAddress).contains("archive@example.org");
            final Email opened = Protection.SMIME.open(prepared.getEmlBytes());
            assertThat(opened.getPlainText()).contains("Answer.", "> " + PLAIN);
        }
    }

    @Test
    void quotePreparationDoesNotReadBorrowedContentOrInterpretUnusedEncryptionKeys() {
        final AtomicInteger reads = new AtomicInteger();
        final IOException unavailable = new IOException("Attachment is deliberately unavailable");
        final DataSource source = new DataSource() {
            @Override public InputStream getInputStream() throws IOException { reads.incrementAndGet(); throw unavailable; }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "application/octet-stream"; }
            @Override public String getName() { return "unavailable.bin"; }
        };
        final Email original = original().withPlainText(PLAIN).withAttachment("unavailable.bin", source)
                .encryptWithOpenPgp(OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(new byte[] {1, 2, 3}).build()).buildEmail();
        final Email reply = MAIL.emailBuilder().replyingTo(original).buildEmail();
        assertThat(reply.getPlainText()).isEqualTo("> " + PLAIN);
        assertThat(reads).hasValue(0);
        assertThat(original.getAttachments().get(0).getDataSource()).isSameAs(source);
        assertThatThrownBy(() -> MAIL.converter().fromEmail(original).toEmlBytes()).isInstanceOf(RuntimeException.class);
    }

    @Test
    void unprotectedReplyResourceSelectionMatchesTheExistingMimeRoute() {
        final Email original = original().withPlainText(PLAIN).withHTMLText("<img src=\"cid:z@example.org\"><img src=\"cid:a@example.org\">")
                .withEmbeddedImage(image("z@example.org", "z.png", new byte[] {1}))
                .withEmbeddedImage(image("unused@example.org", "unused.png", new byte[] {2}))
                .withEmbeddedImage(image("a@example.org", "earlier.png", new byte[] {3}))
                .withAttachment(image("a@example.org", "last.png", new byte[] {4}))
                .withAttachment("ordinary.bin", new byte[] {5}, "application/octet-stream").buildEmail();
        final Email reference = MAIL.emailBuilder().replyingTo(EmailConverter.emailToMimeMessage(original)).buildEmail();
        final Email reply = MAIL.emailBuilder().replyingTo(original).buildEmail();
        assertThat(reply.getEmbeddedImages()).extracting(AttachmentResource::getContentId)
                .containsExactlyElementsOf(reference.getEmbeddedImages().stream().map(AttachmentResource::getContentId).collect(java.util.stream.Collectors.toList()));
        assertThat(reply.getEmbeddedImages()).extracting(AttachmentResource::getName).containsExactly("last.png", "z.png");
        assertThat(reply.getEmbeddedImages().get(0).readAllBytes()).containsExactly((byte) 4);
        assertThat(reply.getAttachments()).isEmpty();
    }

    @Test
    void exactAndMimeCiphertextStillNeedReceiveSideDecryptionAndRetainTheirBytes() {
        final Email original = original().withPlainText(PLAIN).encryptWithSmime(smimeEncryption).buildEmail();
        final byte[] encrypted = MAIL.converter().fromEmail(original).toEmlBytes();
        final MimeMessage mime = EmailConverter.emlToMimeMessage(new ByteArrayInputStream(encrypted));
        final Email mimeReply = MAIL.emailBuilder().replyingTo(mime).buildEmail();
        assertThat(mimeReply.getPlainText()).doesNotContain(PLAIN);
        assertThat(EmailConverter.mimeMessageToEMLByteArray(mime)).containsExactly(encrypted);
        final byte[] exactBytes = Arrays.copyOf(encrypted, encrypted.length + 2);
        exactBytes[encrypted.length] = '\r';
        exactBytes[encrypted.length + 1] = '\n';
        final Email exact = MAIL.emailBuilder().startingFromExactEml(exactBytes)
                .withEnvelopeRecipients("envelope-only@example.org").buildEmail();
        final Email exactReply = MAIL.emailBuilder().replyingToAll(exact).buildEmail();
        assertThat(exactReply.getPlainText()).doesNotContain(PLAIN);
        assertThat(exactReply.getRecipients()).extracting(Recipient::getAddress)
                .contains("reply@example.org").doesNotContain("envelope-only@example.org", "test-inbox@example.org");
        assertThat(MAIL.converter().fromEmail(exact).toEmlBytes()).containsExactly(exactBytes);
    }

    @Test
    void referencedInlineResourcesKeepOwnedSnapshotsWhileUnrelatedAttachmentsAreOmitted() {
        final AtomicInteger reads = new AtomicInteger();
        final AtomicInteger closes = new AtomicInteger();
        final AtomicInteger unrelatedReads = new AtomicInteger();
        final byte[] payload = {1, 2, 3};
        final DataSource source = new DataSource() {
            @Override public InputStream getInputStream() {
                reads.incrementAndGet();
                return new ByteArrayInputStream(payload) { @Override public void close() { closes.incrementAndGet(); } };
            }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "image/png"; }
            @Override public String getName() { return "borrowed.png"; }
        };
        final DataSource unrelated = new DataSource() {
            @Override public InputStream getInputStream() throws IOException { unrelatedReads.incrementAndGet(); throw new IOException("Unrelated content"); }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "application/octet-stream"; }
            @Override public String getName() { return "unused.bin"; }
        };
        final Email original = original().withHTMLText("<img src=\"cid:borrowed@example.org\">")
                .withEmbeddedImage(AttachmentResource.builder().withName("borrowed.png").withContentId("borrowed@example.org")
                        .withDataSource(source).build())
                .withAttachment("unused.bin", unrelated).buildEmail();
        final Email reply = MAIL.emailBuilder().replyingTo(original).buildEmail();
        assertThat(reads).hasValue(1);
        assertThat(closes).hasValue(1);
        assertThat(unrelatedReads).hasValue(0);
        assertThat(reply.getEmbeddedImages()).singleElement().satisfies(resource -> assertThat(resource.getDataSource()).isNotSameAs(source));
        assertThat(reply.getAttachments()).isEmpty();
        payload[0] = 9;
        assertThat(reply.getEmbeddedImages().get(0).readAllBytes()).containsExactly((byte) 1, (byte) 2, (byte) 3);
        assertThat(reads).hasValue(1);
        assertThat(MAIL.converter().fromEmail(reply).toEmlBytes()).isNotEmpty();
        assertThat(unrelatedReads).hasValue(0);
        assertThat(original.getEmbeddedImages().get(0).getDataSource()).isSameAs(source);
    }

    @ParameterizedTest
    @EnumSource(ImageReadFailure.class)
    void selectedImageSnapshotRetainsReadAndCloseCauses(final ImageReadFailure kind) {
        final IOException readFailure = new IOException("Selected image read failed");
        final IOException closeFailure = new IOException("Selected image close failed");
        final AtomicInteger closes = new AtomicInteger();
        final DataSource source = new DataSource() {
            @Override public InputStream getInputStream() throws IOException {
                if (kind == ImageReadFailure.OPEN) throw readFailure;
                return new InputStream() {
                    private boolean consumed;
                    @Override public int read() throws IOException {
                        if (kind == ImageReadFailure.READ || kind == ImageReadFailure.READ_AND_CLOSE) throw readFailure;
                        if (consumed) return -1;
                        consumed = true;
                        return 1;
                    }
                    @Override public void close() throws IOException {
                        closes.incrementAndGet();
                        if (kind == ImageReadFailure.CLOSE || kind == ImageReadFailure.READ_AND_CLOSE) throw closeFailure;
                    }
                };
            }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "image/png"; }
            @Override public String getName() { return "selected.png"; }
        };
        final Email original = original().withHTMLText("<img src=\"cid:selected@example.org\">")
                .withEmbeddedImage(AttachmentResource.builder().withName("selected.png").withContentId("selected@example.org")
                        .withDataSource(source).build()).buildEmail();
        final Throwable expected = kind == ImageReadFailure.CLOSE ? closeFailure : readFailure;
        assertThatThrownBy(() -> MAIL.emailBuilder().replyingTo(original)).isInstanceOf(MailException.class).hasCause(expected)
                .satisfies(failure -> assertThat(((MailException) failure).getKind())
                        .isEqualTo(org.simplejavamail.api.mailer.MailFailureKind.CONVERSION));
        assertThat(closes).hasValue(kind == ImageReadFailure.OPEN ? 0 : 1);
        if (kind == ImageReadFailure.READ_AND_CLOSE) assertThat(readFailure.getSuppressed()).containsExactly(closeFailure);
    }

    @Test
    void rawVisibleAddressHeadersRetainTheExistingMimeReplyRules() {
        final Email original = original().withPlainText(PLAIN).withHeader("To", "raw-to@example.org")
                .withHeader("Cc", "raw-cc@example.org").withHeader("Reply-To", "raw-reply@example.org").buildEmail();
        final Email reference = MAIL.emailBuilder().replyingToAll(EmailConverter.emailToMimeMessage(original)).buildEmail();
        final Email reply = MAIL.emailBuilder().replyingToAll(original).buildEmail();
        assertThat(reply.getRecipients()).extracting(Recipient::getAddress)
                .containsExactlyElementsOf(reference.getRecipients().stream().map(Recipient::getAddress).collect(java.util.stream.Collectors.toList()))
                .contains("raw-to@example.org", "raw-cc@example.org", "raw-reply@example.org").doesNotContain("test-inbox@example.org");
        assertThat(reply.getHeaders()).isEqualTo(reference.getHeaders());
    }

    @Test
    void quotingDoesNotTurnAnInjectedSourceHeaderIntoAReplyRecipientOrBypassSendValidation() throws Exception {
        final Email original = original().withPlainText(PLAIN)
                .withHeader("X-Source", "safe\r\nBcc: injected@example.org").buildEmail();
        final Email reply = MAIL.emailBuilder().replyingToAll(original).from("bob@example.org").buildEmail();
        assertThat(reply.getRecipients()).extracting(Recipient::getAddress).doesNotContain("injected@example.org");
        assertThat(reply.getHeaders()).doesNotContainKey("X-Source");
        try (Mailer mailer = MAIL.mailerBuilder().withSMTPServerHost("localhost").buildMailer()) {
            assertThatThrownBy(() -> mailer.rehearse(original)).isInstanceOf(MailException.class);
        }
    }

    @Test
    void representedProtectionHistorySurvivesQuotingWithoutReencryption() {
        final Email sent = original().withPlainText(PLAIN).signWithSmime(smimeSigning).encryptWithSmime(smimeEncryption).buildEmail();
        final Email received = Protection.SMIME.open(MAIL.converter().fromEmail(sent).toEmlBytes());
        final Email original = MAIL.emailBuilder().copying(received).encryptWithSmime(smimeEncryption).buildEmail();
        final byte[] history = original.getInspection().getOriginalMessage().readAllBytes();
        final Email reply = MAIL.emailBuilder().replyingTo(original).buildEmail();
        assertThat(reply.getPlainText()).isEqualTo("> " + PLAIN);
        assertThat(original.getInspection().getOriginalMessage().readAllBytes()).containsExactly(history);
        assertThat(original.getInspection().getPlainText().getSignatureStatus()).isEqualTo(VERIFIED);
        assertThat(original.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(DECRYPTED);
    }

    private static EmailPopulatingBuilder original() {
        return MAIL.emailBuilder().startingBlank().from("alice@example.org").to("bob@example.org").cc("carol@example.org")
                .withReplyTo("reply@example.org").withSubject("Order").fixingMessageId("<source@example.org>")
                .withHeader("References", "<earlier@example.org>")
                .withOverrideReceivers(MAIL.recipientBuilder().to("test-inbox@example.org"));
    }

    private static AttachmentResource image(final String cid, final String name, final byte[] bytes) {
        return AttachmentResource.builder().withContentId(cid).withName(name).withData(bytes, "image/png").build();
    }

    private static void assertRouting(final Email reply, final boolean all) {
        assertThat(reply.getSubject()).isEqualTo("Re: Order");
        assertThat(reply.getRecipients()).extracting(Recipient::getAddress).contains("reply@example.org").doesNotContain("test-inbox@example.org");
        if (all) assertThat(reply.getRecipients()).extracting(Recipient::getAddress).contains("carol@example.org");
        else assertThat(reply.getRecipients()).extracting(Recipient::getAddress).doesNotContain("carol@example.org");
        assertThat(reply.getHeaders().get("In-Reply-To")).containsExactly("<source@example.org>");
        assertThat(reply.getHeaders().get("References")).containsExactly("<earlier@example.org> <source@example.org>");
    }

    private static byte[] resource(final String name) throws IOException {
        try (InputStream input = ReadableProtectedReplyTest.class.getResourceAsStream("/openpgpjs/" + name)) {
            return input.readAllBytes();
        }
    }

    private enum Route {
        REPLY(false, false), REPLY_CUSTOM(false, true), REPLY_ALL(true, false), REPLY_ALL_CUSTOM(true, true);
        private final boolean all;
        private final boolean custom;
        Route(final boolean all, final boolean custom) { this.all = all; this.custom = custom; }
        EmailPopulatingBuilder reply(final Email email) {
            if (all) return custom ? MAIL.emailBuilder().replyingToAll(email, CUSTOM) : MAIL.emailBuilder().replyingToAll(email);
            return custom ? MAIL.emailBuilder().replyingTo(email, CUSTOM) : MAIL.emailBuilder().replyingTo(email);
        }
    }

    private enum ImageReadFailure { OPEN, READ, CLOSE, READ_AND_CLOSE }

    private enum Protection {
        SMIME {
            @Override EmailPopulatingBuilder encrypt(final EmailPopulatingBuilder builder) { return builder.encryptWithSmime(smimeEncryption); }
            @Override EmailPopulatingBuilder signAndEncrypt(final EmailPopulatingBuilder builder) { return encrypt(builder.signWithSmime(smimeSigning)); }
            @Override Email open(final byte[] eml) {
                final Email result = MAIL.converter().fromEml(eml).withSmimeDecryption(keys).toEmail();
                assertThat(result.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(DECRYPTED);
                assertThat(result.getInspection().getPlainText().getSignatureStatus()).isEqualTo(VERIFIED);
                return result;
            }
            @Override void assertConfigured(final Email email) { assertThat(email.getSmimeEncryptionConfig()).isSameAs(smimeEncryption); }
        },
        OPENPGP {
            @Override EmailPopulatingBuilder encrypt(final EmailPopulatingBuilder builder) { return builder.encryptWithOpenPgp(pgpEncryption); }
            @Override EmailPopulatingBuilder signAndEncrypt(final EmailPopulatingBuilder builder) { return encrypt(builder.signWithOpenPgp(pgpSigning)); }
            @Override Email open(final byte[] eml) {
                final Email result = MAIL.converter().fromEml(eml).withOpenPgpReceiveConfig(pgpReceiving).toEmail();
                assertThat(result.getInspection().getPlainText().getEncryptionStatus()).isEqualTo(DECRYPTED);
                assertThat(result.getInspection().getPlainText().getSignatureStatus()).isEqualTo(VERIFIED);
                return result;
            }
            @Override void assertConfigured(final Email email) { assertThat(email.getOpenPgpEncryptionConfig()).isSameAs(pgpEncryption); }
        };
        abstract EmailPopulatingBuilder encrypt(EmailPopulatingBuilder builder);
        abstract EmailPopulatingBuilder signAndEncrypt(EmailPopulatingBuilder builder);
        abstract Email open(byte[] eml);
        abstract void assertConfigured(Email email);
    }
}
