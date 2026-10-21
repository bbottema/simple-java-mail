package org.simplejavamail.mailer.internal;

import jakarta.activation.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.email.config.SmtpDeliveryOptions;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.email.internal.InternalEmail;
import org.simplejavamail.email.internal.InternalEmailPopulatingBuilder;
import org.simplejavamail.internal.email.OpenPgpDetailsImpl;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;

class EmailCompletionHistoryTest {

    private static final byte[] SMIME_BYTES = {11, 12};
    private static final byte[] OPENPGP_BYTES = {21, 22};
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completionOmitsInspectionHistoryFromEverySourceWithoutReadingOrLosingOutgoingContent(final boolean merged) {
        final CountingSource content = new CountingSource();
        final Email historical = historicalEmail(content, merged);

        assertCompletions(historical, null, null, historical);
        assertCompletions(null, historical, null, historical);
        assertCompletions(null, null, historical, historical);
        assertCompletions(historical, historical, historical, historical);

        assertHistoricalResults(historical, merged);
        assertThat(content.reads).isZero();
        final byte[] detached = historical.getOriginalSmimeDetails().getOriginalProtectedMessage();
        detached[0] = 0;
        assertThat(historical.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(SMIME_BYTES);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryCopyingAndSerializationRetainHistoryAndTheirCompletionOmitsIt(final boolean merged) throws Exception {
        final CountingSource content = new CountingSource();
        final Email original = historicalEmail(content, merged);
        final Email copied = mail.emailBuilder().copying(original).buildEmail();

        assertHistoricalResults(copied, merged);
        assertThat(copied.getOriginalSmimeDetails()).isSameAs(original.getOriginalSmimeDetails());
        assertThat(copied.getOriginalOpenPgpDetails()).isSameAs(original.getOriginalOpenPgpDetails());
        assertThat(copied.getSmimeSignedEmail()).isSameAs(original.getSmimeSignedEmail());
        assertThat(content.reads).isZero();

        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }
        final Email restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (Email) input.readObject();
        }
        assertThat(content.reads).isPositive();
        assertHistoricalResults(restored, merged);
        assertThat(restored.getPlainText()).isEqualTo(original.getPlainText());
        assertThat(restored.getAttachments()).extracting(AttachmentResource::getName)
                .containsExactlyElementsOf(merged ? List.of("outer.txt", "nested.txt") : List.of("outer.txt"));
        assertNoHistory(governance(null, null).produceEmailApplyingDefaultsAndOverrides(copied));
        assertNoHistory(governance(null, null).produceEmailApplyingDefaultsAndOverrides(restored));
        assertHistoricalResults(original, merged);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outgoingProtectionAndNotificationChoicesSurviveIndependentlyOfHistory(final boolean openPgp) {
        final Email original = historicalEmail(new CountingSource(), false);
        final Email supplied = protectionTemplate(mail.emailBuilder().copying(original), openPgp, (byte) 1);
        final Email overrides = protectionTemplate(mail.emailBuilder().startingBlank(), openPgp, (byte) 2);
        final EmailGovernanceImpl governance = governance(null, overrides);

        final Email completed = governance.produceEmailApplyingDefaultsAndOverrides(supplied);
        assertProtectionChoices(completed, overrides);
        assertNoHistory(completed);
        final Email suppressed = mail.emailBuilder().copying(supplied)
                .withTemplateOptions(EmailTemplateOptions.builder().ignoringOverrides().build()).buildEmail();
        final Email kept = governance.produceEmailApplyingDefaultsAndOverrides(suppressed);
        assertProtectionChoices(kept, supplied);
        assertNoHistory(kept);
        assertHistoricalResults(supplied, false);
    }

    private void assertCompletions(final Email supplied, final Email defaults, final Email overrides, final Email expectedContent) {
        final EmailGovernanceImpl governance = governance(defaults, overrides);
        final Email direct = governance.produceEmailApplyingDefaultsAndOverrides(supplied);
        final EmailPopulatingBuilder builder = supplied == null ? mail.emailBuilder().startingBlank() : mail.emailBuilder().copying(supplied);
        final Email built = builder.buildEmailCompletedWithDefaultsAndOverrides(governance);
        for (final Email result : List.of(direct, built, governance.produceEmailApplyingDefaultsAndOverrides(direct))) {
            assertNoHistory(result);
            assertThat(result.getPlainText()).isEqualTo(expectedContent.getPlainText());
            assertThat(result.getHeaders()).isEqualTo(expectedContent.getHeaders());
            assertThat(result.getAttachments()).containsExactlyElementsOf(expectedContent.getAttachments());
            assertThat(result.getEmbeddedImages()).containsExactlyElementsOf(expectedContent.getEmbeddedImages());
            assertThat(result.getId()).isEqualTo(expectedContent.getId());
            assertThat(result.getSentDate()).isEqualTo(expectedContent.getSentDate());
            assertThat(result.getRecipients()).containsExactlyElementsOf(expectedContent.getRecipients());
            assertThat(result.getOverrideReceivers()).containsExactlyElementsOf(expectedContent.getOverrideReceivers());
            assertThat(result.getBounceToRecipient()).isEqualTo(expectedContent.getBounceToRecipient());
            assertThat(result.getDeliveryStatusNotification()).isEqualTo(expectedContent.getDeliveryStatusNotification());
        }
    }

    private Email historicalEmail(final CountingSource content, final boolean merged) {
        final Email signedContent = mail.emailBuilder().startingBlank().withPlainText("Signed body")
                .withHeader("X-Signed", "retained content").withAttachment("nested.txt", content).buildEmail();
        final InternalEmailPopulatingBuilder builder = (InternalEmailPopulatingBuilder) mail.emailBuilder().startingBlank()
                .from("sender@example.org").to("visible@example.org")
                .withOverrideReceivers(mail.recipientBuilder().to("Archive", "archive@example.org"))
                .withBounceTo("bounce@example.org").withPlainText("Outer body").withHeader("X-Outer", "outer content")
                .withAttachment("outer.txt", content).withEmbeddedImage("image", content)
                .fixingMessageId("<retained@example.org>").fixingSentDate(new Date(123456789))
                .withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withDeliveryStatusNotificationNotifyOptions(FAILURE).build());
        builder.withOriginalSmimeDetails(OriginalSmimeDetailsImpl.builder()
                .smimeMode(OriginalSmimeDetails.SmimeMode.SIGNED_ENCRYPTED)
                .verificationStatus(OriginalSmimeDetails.VerificationStatus.VALID)
                .decryptionStatus(OriginalSmimeDetails.DecryptionStatus.DECRYPTED)
                .smimeSignedBy("input-signer").smimeMime("application/pkcs7-mime")
                .originalProtectedMessage(SMIME_BYTES).build());
        builder.withOriginalOpenPgpDetails(OpenPgpDetailsImpl.builder()
                .openPgpMode(OriginalOpenPgpDetails.OpenPgpMode.SIGNED_ENCRYPTED)
                .signatureStatus(OriginalOpenPgpDetails.SignatureStatus.VALID)
                .decryptionStatus(OriginalOpenPgpDetails.DecryptionStatus.DECRYPTED)
                .signerFingerprint("input-fingerprint").recipientKeyIds(List.of("input-recipient"))
                .originalProtectedMessage(OPENPGP_BYTES).build());
        builder.withDecryptedAttachments(List.of(AttachmentResource.builder().withName("decrypted.eml").withDataSource(content).build()));
        builder.withSmimeSignedEmail(signedContent);
        if (!merged) {
            builder.notMergingSingleSMIMESignedAttachment();
        }
        return builder.buildEmail();
    }

    private Email protectionTemplate(final EmailPopulatingBuilder builder, final boolean openPgp, final byte marker) {
        if (openPgp) {
            builder.signWithOpenPgp(OpenPgpSigningConfig.builder().secretKeyRing(new byte[]{marker}).build())
                    .encryptWithOpenPgp(OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(new byte[]{marker}).build());
        } else {
            builder.signWithSmime(SmimeSigningConfig.builder().pkcs12Config(Pkcs12Config.builder()
                    .pkcs12Store(new byte[]{marker}).storePassword("store").keyAlias("key").keyPassword("secret").build()).build())
                    .encryptWithSmime(SmimeEncryptionConfig.builder().x509Certificate(mock(X509Certificate.class)).build());
        }
        return builder.signWithDomainKey(DkimConfig.builder().dkimPrivateKeyData(new byte[]{marker})
                        .dkimSigningDomain("example.org").dkimSelector("selector-" + marker).build())
                .withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withDeliveryStatusNotification(
                        DeliveryStatusNotification.builder().notifyOptions(FAILURE).envelopeId("request-" + marker).build()).build())
                .buildEmail();
    }

    private EmailGovernanceImpl governance(final Email defaults, final Email overrides) {
        return new EmailGovernanceImpl(mail.getConfig(), mail.emailBuilder(), null, defaults, overrides, null);
    }

    private static void assertProtectionChoices(final Email actual, final Email expected) {
        assertThat(actual.getSmimeSigningConfig()).isSameAs(expected.getSmimeSigningConfig());
        assertThat(actual.getSmimeEncryptionConfig()).isSameAs(expected.getSmimeEncryptionConfig());
        assertThat(actual.getOpenPgpSigningConfig()).isSameAs(expected.getOpenPgpSigningConfig());
        assertThat(actual.getOpenPgpEncryptionConfig()).isSameAs(expected.getOpenPgpEncryptionConfig());
        assertThat(actual.getDkimConfig()).isSameAs(expected.getDkimConfig());
        assertThat(actual.getDeliveryStatusNotification()).isEqualTo(expected.getDeliveryStatusNotification());
    }

    private static void assertNoHistory(final Email email) {
        assertThat(email.getOriginalSmimeDetails().getSmimeMode()).isEqualTo(OriginalSmimeDetails.SmimeMode.PLAIN);
        assertThat(email.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.NOT_SIGNED);
        assertThat(email.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(OriginalSmimeDetails.DecryptionStatus.NOT_ENCRYPTED);
        assertThat(email.getOriginalSmimeDetails().getSmimeSignedBy()).isNull();
        assertThat(email.getOriginalSmimeDetails().getOriginalProtectedMessage()).isNull();
        assertThat(email.getOriginalOpenPgpDetails().getOpenPgpMode()).isEqualTo(OriginalOpenPgpDetails.OpenPgpMode.PLAIN);
        assertThat(email.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(OriginalOpenPgpDetails.SignatureStatus.NOT_PRESENT);
        assertThat(email.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(OriginalOpenPgpDetails.DecryptionStatus.NOT_ENCRYPTED);
        assertThat(email.getOriginalOpenPgpDetails().getSignerFingerprint()).isNull();
        assertThat(email.getOriginalOpenPgpDetails().getRecipientKeyIds()).isEmpty();
        assertThat(email.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).isNull();
        assertThat(email.getDecryptedAttachments()).isEmpty();
        assertThat(email.getSmimeSignedEmail()).isNull();
        assertThat(((InternalEmail) email).wasMergedWithSmimeSignedMessage()).isFalse();
        assertThat(email.getDeliveryStatusReport()).isNull();
    }

    private static void assertHistoricalResults(final Email email, final boolean merged) {
        assertThat(email.getOriginalSmimeDetails().getVerificationStatus()).isEqualTo(OriginalSmimeDetails.VerificationStatus.VALID);
        assertThat(email.getOriginalSmimeDetails().getDecryptionStatus()).isEqualTo(OriginalSmimeDetails.DecryptionStatus.DECRYPTED);
        assertThat(email.getOriginalSmimeDetails().getSmimeSignedBy()).isEqualTo("input-signer");
        assertThat(email.getOriginalSmimeDetails().getOriginalProtectedMessage()).containsExactly(SMIME_BYTES);
        assertThat(email.getOriginalOpenPgpDetails().getSignatureStatus()).isEqualTo(OriginalOpenPgpDetails.SignatureStatus.VALID);
        assertThat(email.getOriginalOpenPgpDetails().getDecryptionStatus()).isEqualTo(OriginalOpenPgpDetails.DecryptionStatus.DECRYPTED);
        assertThat(email.getOriginalOpenPgpDetails().getSignerFingerprint()).isEqualTo("input-fingerprint");
        assertThat(email.getOriginalOpenPgpDetails().getRecipientKeyIds()).containsExactly("input-recipient");
        assertThat(email.getOriginalOpenPgpDetails().getOriginalProtectedMessage()).containsExactly(OPENPGP_BYTES);
        assertThat(email.getDecryptedAttachments()).extracting(AttachmentResource::getName).containsExactly("decrypted.eml");
        assertThat(email.getSmimeSignedEmail().getPlainText()).isEqualTo("Signed body");
        assertThat(((InternalEmail) email).wasMergedWithSmimeSignedMessage()).isEqualTo(merged);
    }

    private static class CountingSource implements DataSource {
        private int reads;
        @Override public InputStream getInputStream() { reads++; return new ByteArrayInputStream(new byte[]{1, 2, 3}); }
        @Override public OutputStream getOutputStream() { throw new AssertionError("Read-only source"); }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public String getName() { return "borrowed-content"; }
    }
}
