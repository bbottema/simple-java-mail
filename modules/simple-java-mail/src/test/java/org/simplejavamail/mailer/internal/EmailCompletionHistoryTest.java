package org.simplejavamail.mailer.internal;

import jakarta.activation.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.SignatureStatus;
import org.simplejavamail.api.email.ValueOrigin;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.email.config.SmtpDeliveryOptions;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;

/** Completion keeps the submitted input's history; templates supply choices, never received proof. */
class EmailCompletionHistoryTest {
    private static final byte[] ORIGINAL = ("From: sender@example.org\r\nTo: visible@example.org\r\n"
            + "Message-ID: <retained@example.org>\r\nDate: Thu, 1 Jan 1970 00:00:01 +0000\r\n"
            + "Subject: =?X-NO-SUCH-CHARSET?Q?historical_subject?=\r\nX-Source: retained\r\n"
            + "Content-Type: text/plain; charset=UTF-8\r\n\r\nOriginal body").getBytes(StandardCharsets.US_ASCII);
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void completionPreservesSubmittedHistoryWithoutReadingBorrowedContent(final boolean repeatedResources) {
        final CountingSource content = new CountingSource();
        final Email historical = historicalEmail(content, repeatedResources);
        for (final EmailGovernanceImpl governance : List.of(governance(null, null), governance(historical, null),
                governance(null, historical), governance(historical, historical))) {
            final Email direct = governance.produceEmailApplyingDefaultsAndOverrides(historical);
            final Email completed = mail.emailBuilder().copying(historical).buildEmailCompletedWithDefaultsAndOverrides(governance);
            for (final Email result : List.of(direct, completed, governance.produceEmailApplyingDefaultsAndOverrides(direct))) {
                assertHistory(result, historical);
                assertThat(result.getPlainText()).isEqualTo(historical.getPlainText());
                assertThat(result.getAttachments()).containsExactlyElementsOf(historical.getAttachments());
                assertThat(result.getEmbeddedImages()).containsExactlyElementsOf(historical.getEmbeddedImages());
                assertThat(result.getId()).isEqualTo(historical.getId());
                assertThat(result.getSentDate()).isEqualTo(historical.getSentDate());
                assertThat(result.getRecipients()).containsExactlyElementsOf(historical.getRecipients());
                assertThat(result.getOverrideReceivers()).containsExactlyElementsOf(historical.getOverrideReceivers());
                assertThat(result.getBounceToRecipient()).isEqualTo(historical.getBounceToRecipient());
                assertThat(result.getDeliveryStatusNotification()).isEqualTo(historical.getDeliveryStatusNotification());
            }
        }
        assertThat(content.reads).isZero();
        final byte[] exposed = historical.getInspection().getOriginalMessage().readAllBytes();
        exposed[0] = 0;
        assertThat(historical.getInspection().getOriginalMessage().readAllBytes()).containsExactly(ORIGINAL);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void templateContentNeverManufacturesReceivedEvidence(final boolean override) {
        final CountingSource content = new CountingSource();
        final Email historicalTemplate = historicalEmail(content, true);
        final EmailGovernanceImpl governance = override ? governance(null, historicalTemplate) : governance(historicalTemplate, null);
        final Email completed = governance.produceEmailApplyingDefaultsAndOverrides(null);
        assertThat(completed.getPlainText()).isEqualTo(historicalTemplate.getPlainText());
        assertThat(completed.getAttachments()).containsExactlyElementsOf(historicalTemplate.getAttachments());
        assertThat(completed.getProblems()).isEmpty();
        assertThat(completed.getSourceData().getHeaders()).isEmpty();
        assertThat(completed.getInspection().getOriginalMessage()).isNull();
        assertThat(completed.getInspection().getPlainText().getOrigin()).isEqualTo(ValueOrigin.CALLER_SUPPLIED);
        assertThat(completed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(content.reads).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void copyingAndSnapshotsKeepHistoryThroughLaterCompletion(final boolean repeatedResources) throws Exception {
        final CountingSource content = new CountingSource();
        final Email original = historicalEmail(content, repeatedResources);
        final Email copy = mail.emailBuilder().copying(original).buildEmail();
        assertThat(copy.getInspection().getPlainText()).isSameAs(original.getInspection().getPlainText());
        assertThat(copy.getInspection().getOriginalMessage()).isSameAs(original.getInspection().getOriginalMessage());
        assertThat(content.reads).isZero();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(copy); }
        final Email restored;
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            restored = (Email) input.readObject();
        }
        assertThat(content.reads).isPositive();
        for (final Email result : List.of(copy, restored, governance(null, null).produceEmailApplyingDefaultsAndOverrides(restored))) {
            assertHistory(result, original);
            assertThat(result.getAttachments()).extracting(AttachmentResource::getName)
                    .containsExactlyElementsOf(repeatedResources ? List.of("outer.txt", "second.txt") : List.of("outer.txt"));
        }
    }

    @Test
    void anEqualOverrideIsAReplacementWhileUntouchedValuesKeepTheirAssociations() {
        final Email source = historicalEmail(new CountingSource(), false);
        final Email overrides = mail.emailBuilder().startingBlank().withPlainText(source.getPlainText()).buildEmail();
        final Email completed = governance(null, overrides).produceEmailApplyingDefaultsAndOverrides(source);
        assertThat(completed.getPlainText()).isEqualTo(source.getPlainText());
        assertThat(completed.getInspection().getPlainText().getOrigin()).isEqualTo(ValueOrigin.CALLER_SUPPLIED);
        assertThat(completed.getInspection().getPlainText().getSignatureStatus()).isEqualTo(SignatureStatus.UNKNOWN);
        assertThat(completed.getInspection().getSubject()).isSameAs(source.getInspection().getSubject());
        assertHistory(completed, source);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void outgoingProtectionAndNotificationChoicesRemainIndependentOfReceivedHistory(final boolean openPgp) {
        final Email original = historicalEmail(new CountingSource(), false);
        final Email supplied = protectionTemplate(mail.emailBuilder().copying(original), openPgp, (byte) 1);
        final Email overrides = protectionTemplate(mail.emailBuilder().startingBlank(), openPgp, (byte) 2);
        final EmailGovernanceImpl governance = governance(null, overrides);
        final Email completed = governance.produceEmailApplyingDefaultsAndOverrides(supplied);
        assertProtectionChoices(completed, overrides);
        assertHistory(completed, supplied);
        final Email suppressed = mail.emailBuilder().copying(supplied)
                .withTemplateOptions(EmailTemplateOptions.builder().ignoringOverrides().build()).buildEmail();
        final Email kept = governance.produceEmailApplyingDefaultsAndOverrides(suppressed);
        assertProtectionChoices(kept, supplied);
        assertHistory(kept, supplied);
    }

    private Email historicalEmail(final CountingSource content, final boolean repeatedResources) {
        final Email received = mail.converter().fromEml(ORIGINAL).toEmail();
        assertThat(received.getProblems()).isNotEmpty();
        final EmailPopulatingBuilder builder = mail.emailBuilder().copying(received)
                .withOverrideReceivers(mail.recipientBuilder().to("Archive", "archive@example.org"))
                .withBounceTo("bounce@example.org").withAttachment("outer.txt", content).withEmbeddedImage("image", content)
                .withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withDeliveryStatusNotificationNotifyOptions(FAILURE).build());
        if (repeatedResources) builder.withAttachment("second.txt", content);
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

    private static void assertHistory(final Email actual, final Email original) {
        assertThat(actual.getSourceData().getOrigin().getReference()).isEqualTo(original.getSourceData().getOrigin().getReference());
        assertThat(actual.getProblems()).extracting(problem -> problem.getOrigin().getReference())
                .containsExactlyElementsOf(original.getProblems().stream().map(problem -> problem.getOrigin().getReference())
                        .collect(java.util.stream.Collectors.toList()));
        assertThat(actual.getInspection().getOriginalMessage().readAllBytes()).containsExactly(ORIGINAL);
    }

    private static void assertProtectionChoices(final Email actual, final Email expected) {
        assertThat(actual.getSmimeSigningConfig()).isSameAs(expected.getSmimeSigningConfig());
        assertThat(actual.getSmimeEncryptionConfig()).isSameAs(expected.getSmimeEncryptionConfig());
        assertThat(actual.getOpenPgpSigningConfig()).isSameAs(expected.getOpenPgpSigningConfig());
        assertThat(actual.getOpenPgpEncryptionConfig()).isSameAs(expected.getOpenPgpEncryptionConfig());
        assertThat(actual.getDkimConfig()).isSameAs(expected.getDkimConfig());
        assertThat(actual.getDeliveryStatusNotification()).isEqualTo(expected.getDeliveryStatusNotification());
    }

    private static final class CountingSource implements DataSource {
        private int reads;
        @Override public InputStream getInputStream() { reads++; return new ByteArrayInputStream(new byte[]{1, 2, 3}); }
        @Override public OutputStream getOutputStream() { throw new AssertionError("Read-only source"); }
        @Override public String getContentType() { return "application/octet-stream"; }
        @Override public String getName() { return "borrowed-content"; }
    }
}
