package org.simplejavamail.email.internal;

import jakarta.activation.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.config.ContentEncodingConfig;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import org.simplejavamail.api.email.config.SmtpDeliveryOptions;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.converter.EmailConverter;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;

class GroupedEmailConfigurationTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @Test
    void replacementDropsEarlierGroupChoicesAndRetainsIndependentGroups() {
        final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank()
                .withContentEncodingConfig(ContentEncodingConfig.builder()
                        .withContentTransferEncoding(ContentTransferEncoding.BASE_64).build())
                .withSmtpDeliveryOptions(SmtpDeliveryOptions.builder()
                        .withTlsRequiredForOnwardDelivery().withSendingToAcceptedRecipients(true).build())
                .withContentEncodingConfig(ContentEncodingConfig.builder()
                        .withPlainTextContentTransferEncoding(ContentTransferEncoding.QUOTED_PRINTABLE).build())
                .withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withSendingToAcceptedRecipients(false).build());
        final Email email = builder.buildEmail();
        assertThat(email.getContentTransferEncoding()).isNull();
        assertThat(email.getPlainTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.QUOTED_PRINTABLE);
        assertThat(email.isTlsRequiredForOnwardDelivery()).isFalse();
        assertThat(email.getSendingToAcceptedRecipients()).isFalse();
        assertThat(builder.getContentEncodingConfig().getContentTransferEncoding()).isNull();
    }

    @Test
    void replacementAndRestorationKeepGovernanceDeferredAndFactorySnapshotsIndependent() {
        final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.defaults.content.transfer.encoding", "7bit",
                "simplejavamail.defaults.sendtoacceptedrecipients", "true",
                "simplejavamail.defaults.delivery.status.notification.notify", "FAILURE")).load());
        final EmailPopulatingBuilder builder = configured.emailBuilder().startingBlank()
                .withContentEncodingConfig(ContentEncodingConfig.builder()
                        .withContentTransferEncoding(ContentTransferEncoding.BASE_64).build())
                .withSmtpDeliveryOptions(SmtpDeliveryOptions.builder()
                        .withSendingToAcceptedRecipients(false).fixingEnvelopeId("first").build());
        final Email first = builder.buildEmail();
        builder.withContentEncodingConfig(ContentEncodingConfig.builder()
                .withPlainTextContentTransferEncoding(ContentTransferEncoding.BIT8).build());
        builder.withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withTlsRequiredForOnwardDelivery().build());
        final Email raw = builder.buildEmail();
        assertThat(raw.getContentTransferEncoding()).isNull();
        assertThat(raw.getDeliveryStatusNotification()).isNull();
        assertThat(raw.getSendingToAcceptedRecipients()).isNull();
        final Email resolved = builder.buildEmailCompletedWithDefaultsAndOverrides();
        assertThat(resolved.getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BIT7);
        assertThat(resolved.getPlainTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BIT8);
        assertThat(resolved.getSendingToAcceptedRecipients()).isTrue();
        assertThat(resolved.getDeliveryStatusNotification().getNotifyOptions()).containsExactly(FAILURE);
        assertThat(resolved.isTlsRequiredForOnwardDelivery()).isTrue();
        final Email restored = builder.useConfiguredContentEncodingConfig().useConfiguredSmtpDeliveryOptions()
                .buildEmailCompletedWithDefaultsAndOverrides();
        assertThat(restored.getPlainTextContentTransferEncoding()).isNull();
        assertThat(restored.getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BIT7);
        assertThat(restored.isTlsRequiredForOnwardDelivery()).isFalse();
        assertThat(first.getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
        assertThat(first.getSendingToAcceptedRecipients()).isFalse();
        assertThat(first.getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("first");
        assertThat(mail.emailBuilder().startingBlank().buildEmailCompletedWithDefaultsAndOverrides().getContentTransferEncoding()).isNull();
    }

    @Test
    void imageReplacementAndClearRestoreCapturedConfiguration(@TempDir final Path directory) throws Exception {
        final Path captured = Files.createDirectory(directory.resolve("captured"));
        final Path replacement = Files.createDirectory(directory.resolve("replacement"));
        Files.write(captured.resolve("captured.png"), new byte[] {1});
        Files.write(replacement.resolve("replacement.png"), new byte[] {2});
        final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.embeddedimages.dynamicresolution.enable.dir", "true",
                "simplejavamail.embeddedimages.dynamicresolution.base.dir", captured.toString(),
                "simplejavamail.embeddedimages.dynamicresolution.mustbesuccesful", "true")).load());
        final EmailPopulatingBuilder builder = configured.emailBuilder().startingBlank()
                .withImageResolutionConfig(ImageResolutionConfig.builder()
                        .withEmbeddedImageAutoResolutionForFiles(false).withEmbeddedImageBaseDir("earlier-base").build())
                .withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageBaseDir(replacement.toString()).build());
        assertThat(builder.getImageResolutionConfig().getEmbeddedImageAutoResolutionForFiles()).isNull();
        assertThat(builder.getImageResolutionConfig().getEmbeddedImageBaseDir()).isEqualTo(replacement.toString());
        assertThat(builder.withHTMLText("<img src='replacement.png'>").buildEmail().getEmbeddedImages()).hasSize(1);
        builder.useConfiguredImageResolutionConfig();
        assertThat(builder.getImageResolutionConfig()).isEqualTo(ImageResolutionConfig.builder().build());
        assertThat(builder.clearEmbeddedImages().withHTMLText("<img src='captured.png'>").buildEmail().getEmbeddedImages()).hasSize(1);
    }

    @Test
    void templateOptionsAndCollectionsAreDetachedAndRemainIndependentOfLocks() {
        final EmailTemplateOptions.Builder choices = EmailTemplateOptions.builder()
                .ignoringDefaults().dontApplyOverrideValueFor(EmailProperty.SUBJECT);
        final EmailTemplateOptions first = choices.build();
        choices.ignoringDefaults(false).allowOverrideValueFor(EmailProperty.SUBJECT);
        assertThat(first.isIgnoreDefaults()).isTrue();
        assertThat(first.getPropertiesNotToApplyOverrideValueFor()).containsExactly(EmailProperty.SUBJECT);
        assertThatThrownBy(() -> first.getPropertiesNotToApplyOverrideValueFor().clear()).isInstanceOf(UnsupportedOperationException.class);
        final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank().withTemplateOptions(first);
        final Email original = builder.buildEmail();
        builder.withTemplateOptions(choices.build());
        assertThat(original.isIgnoreDefaults()).isTrue();
        assertThat(builder.buildEmail().isIgnoreDefaults()).isFalse();
        final SimpleJavaMail locked = SimpleJavaMail.withConfig(ConfigLoader.builder().withMap(Map.of(
                "simplejavamail.locked.embeddedimages.dynamicresolution.enable.dir", "false")).load());
        assertThatThrownBy(() -> locked.emailBuilder().startingBlank()
                .withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForFiles(true).build())
                .withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().ignoringOverrides().build()).buildEmail())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void exactSmtpGroupRetainsAuthoritativeBytesAndOrdinaryResourcesRemainBorrowed() {
        final byte[] eml = "From: sender@example.org\r\nTo: visible@example.org\r\nSubject: Original\r\n\r\nbody\r\n".getBytes(US_ASCII);
        final Email exact = mail.emailBuilder().startingFromExactEml(eml).withEnvelopeRecipients("actual@example.org")
                .withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withTlsRequiredForOnwardDelivery()
                        .withDeliveryStatusNotificationNotifyOptions(FAILURE).fixingEnvelopeId("job-42").build()).buildEmail();
        assertThat(EmailConverter.emailToEMLByteArray(exact)).containsExactly(eml);
        assertThat(exact.getDeliveryStatusNotification().getEnvelopeId()).isEqualTo("job-42");
        final AtomicInteger reads = new AtomicInteger();
        final DataSource source = new DataSource() {
            public InputStream getInputStream() { reads.incrementAndGet(); return new ByteArrayInputStream(new byte[] {1}); }
            public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            public String getContentType() { return "application/octet-stream"; }
            public String getName() { return "borrowed"; }
        };
        final Email original = mail.emailBuilder().startingBlank().withAttachment("borrowed", source)
                .withContentEncodingConfig(ContentEncodingConfig.builder().withContentTransferEncoding(ContentTransferEncoding.BASE_64).build())
                .buildEmail();
        final Email copied = mail.emailBuilder().copying(original).buildEmail();
        assertThat(reads).hasValue(0);
        assertThat(copied.getAttachments().get(0).getDataSource()).isSameAs(source);
    }
}
