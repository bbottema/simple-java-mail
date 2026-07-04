package org.simplejavamail.mailer.internal;

import jakarta.mail.util.ByteArrayDataSource;
import lombok.val;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.email.ContentTransferEncoding.BASE_64;
import static org.simplejavamail.api.email.ContentTransferEncoding.QUOTED_PRINTABLE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.DELAY;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.SUCCESS;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.FULL_MESSAGE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.ReturnOption.HEADERS_ONLY;
import static org.simplejavamail.internal.config.EmailProperty.DELIVERY_STATUS_NOTIFICATION;

public class EmailGovernanceImplTest {

    @Test
    public void produceEmailApplyingDefaultsAndOverrides_DispositionNotificationTo() {
        ConfigLoaderTestHelper.clearConfigProperties();

        val defaults = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .clearDispositionNotificationTo()
                .clearReplyTo()
                .withDispositionNotificationTo("disposition.notificationTo@domain.com")
                .buildEmail();
        val overrides1 = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .clearDispositionNotificationTo()
                .clearReplyTo()
                .withDispositionNotificationTo("disposition.notificationTo.override@domain.com")
                .buildEmail();
        val overrides2 = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .clearDispositionNotificationTo()
                .clearReplyTo()
                .withReplyTo("replyto@domain.com")
                .withDispositionNotificationTo()
                .buildEmail();
        val userEmail = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .clearDispositionNotificationTo()
                .clearReplyTo()
                .from("from@domain.com")
                .withDispositionNotificationTo()
                .buildEmail();

        EmailAssert.assertThat(new EmailGovernanceImpl(null, null, null, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseDispositionNotificationTo(true)
                .hasDispositionNotificationTo(new Recipient(null, "from@domain.com", null, null));

        EmailAssert.assertThat(new EmailGovernanceImpl(null, defaults, null, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseDispositionNotificationTo(true)
                .hasDispositionNotificationTo(new Recipient(null, "disposition.notificationTo@domain.com", null, null));

        EmailAssert.assertThat(new EmailGovernanceImpl(null, defaults, overrides1, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseReturnReceiptTo(true)
                .hasDispositionNotificationTo(new Recipient(null, "disposition.notificationTo.override@domain.com", null, null));

        EmailAssert.assertThat(new EmailGovernanceImpl(null, defaults, overrides2, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseReturnReceiptTo(true)
                .hasDispositionNotificationTo(new Recipient(null, "disposition.notificationTo@domain.com", null, null));

        EmailAssert.assertThat(new EmailGovernanceImpl(null, null, overrides2, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseDispositionNotificationTo(true)
                .hasDispositionNotificationTo(new Recipient(null, "replyto@domain.com", null, null));
    }

    @Test
    public void produceEmailApplyingDefaultsAndOverrides_ReturnReceiptTo() {
        ConfigLoaderTestHelper.clearConfigProperties();

        val defaults = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .clearReturnReceiptTo()
                .clearReplyTo()
                .withReturnReceiptTo("return.receiptTo@domain.com")
                .buildEmail();
        val overrides1 = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .clearReturnReceiptTo()
                .clearReplyTo()
                .withReturnReceiptTo("return.receiptTo.override@domain.com")
                .buildEmail();
        val overrides2 = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .clearReturnReceiptTo()
                .clearReplyTo()
                .withReplyTo("replyto@domain.com")
                .withReturnReceiptTo()
                .buildEmail();
        val userEmail = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .clearReturnReceiptTo()
                .clearReplyTo()
                .from("from@domain.com")
                .withReturnReceiptTo()
                .buildEmail();

        EmailAssert.assertThat(new EmailGovernanceImpl(null, null, null, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseReturnReceiptTo(true)
                .hasReturnReceiptTo(new Recipient(null, "from@domain.com", null, null));

        EmailAssert.assertThat(new EmailGovernanceImpl(null, defaults, null, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseReturnReceiptTo(true)
                .hasReturnReceiptTo(new Recipient(null, "return.receiptTo@domain.com", null, null));

        EmailAssert.assertThat(new EmailGovernanceImpl(null, defaults, overrides1, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseReturnReceiptTo(true)
                .hasReturnReceiptTo(new Recipient(null, "return.receiptTo.override@domain.com", null, null));

        EmailAssert.assertThat(new EmailGovernanceImpl(null, defaults, overrides2, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseReturnReceiptTo(true)
                .hasReturnReceiptTo(new Recipient(null, "return.receiptTo@domain.com", null, null));

        EmailAssert.assertThat(new EmailGovernanceImpl(null, null, overrides2, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseReturnReceiptTo(true)
                .hasReturnReceiptTo(new Recipient(null, "replyto@domain.com", null, null));
    }

    @Test
    public void produceEmailApplyingDefaultsAndOverrides_DeliveryStatusNotification() {
        ConfigLoaderTestHelper.clearConfigProperties();

        val defaults = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .withDeliveryStatusNotification(HEADERS_ONLY, FAILURE)
                .buildEmail();
        val overrides = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .withDeliveryStatusNotification(FULL_MESSAGE, DELAY)
                .buildEmail();
        val userEmail = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .withDeliveryStatusNotification(SUCCESS)
                .buildEmail();
        val userEmailWithoutDsn = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .buildEmail();
        val userEmailIgnoringDsnDefault = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .dontApplyDefaultValueFor(DELIVERY_STATUS_NOTIFICATION)
                .buildEmail();

        assertThat(new EmailGovernanceImpl(null, null, null, null).produceEmailApplyingDefaultsAndOverrides(userEmail)
                .getDeliveryStatusNotification())
                .isEqualTo(DeliveryStatusNotification.of(SUCCESS));

        assertThat(new EmailGovernanceImpl(null, defaults, null, null).produceEmailApplyingDefaultsAndOverrides(userEmailWithoutDsn)
                .getDeliveryStatusNotification())
                .isEqualTo(DeliveryStatusNotification.of(HEADERS_ONLY, FAILURE));

        assertThat(new EmailGovernanceImpl(null, defaults, overrides, null).produceEmailApplyingDefaultsAndOverrides(userEmail)
                .getDeliveryStatusNotification())
                .isEqualTo(DeliveryStatusNotification.of(FULL_MESSAGE, DELAY));

        assertThat(new EmailGovernanceImpl(null, defaults, null, null).produceEmailApplyingDefaultsAndOverrides(userEmailIgnoringDsnDefault)
                .getDeliveryStatusNotification())
                .isNull();
    }

    @Test
    public void produceEmailApplyingDefaultsAndOverrides_PreEncodedAttachmentResources() throws Exception {
        ConfigLoaderTestHelper.clearConfigProperties();

        val defaults = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .withPreEncodedAttachment("default-preencoded.txt", new ByteArrayDataSource("ZGVmYXVsdA==", "text/plain"), "default description", BASE_64, "default-attachment-cid")
                .withPreEncodedEmbeddedImage("default-logo", new ByteArrayDataSource("ZGVmYXVsdA==", "image/png"), BASE_64, "default-image-cid")
                .buildEmail();
        val overrides = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .withPreEncodedAttachment("override-preencoded.txt", new ByteArrayDataSource("b3ZlcnJpZGU=", "text/plain"), "override description", QUOTED_PRINTABLE, "override-attachment-cid")
                .withPreEncodedEmbeddedImage("override-logo", new ByteArrayDataSource("b3ZlcnJpZGU=", "image/png"), QUOTED_PRINTABLE, "override-image-cid")
                .buildEmail();
        val userEmail = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .withPreEncodedAttachment("provided-preencoded.txt", new ByteArrayDataSource("cHJvdmlkZWQ=", "text/plain"), "provided description", BASE_64, "provided-attachment-cid")
                .withPreEncodedEmbeddedImage("provided-logo", new ByteArrayDataSource("cHJvdmlkZWQ=", "image/png"), BASE_64, "provided-image-cid")
                .buildEmail();

        val resolved = new EmailGovernanceImpl(null, defaults, overrides, null).produceEmailApplyingDefaultsAndOverrides(userEmail);

        assertThat(resolved.getAttachments())
                .extracting(AttachmentResource::getName)
                .containsSubsequence("override-preencoded.txt", "provided-preencoded.txt", "default-preencoded.txt");
        assertPreEncodedResource(resolved.getAttachments(), "override-preencoded.txt", QUOTED_PRINTABLE, "override description", "override-attachment-cid");
        assertPreEncodedResource(resolved.getAttachments(), "provided-preencoded.txt", BASE_64, "provided description", "provided-attachment-cid");
        assertPreEncodedResource(resolved.getAttachments(), "default-preencoded.txt", BASE_64, "default description", "default-attachment-cid");

        assertThat(resolved.getEmbeddedImages())
                .extracting(AttachmentResource::getName)
                .containsSubsequence("override-logo", "provided-logo", "default-logo");
        assertPreEncodedResource(resolved.getEmbeddedImages(), "override-logo", QUOTED_PRINTABLE, null, "override-image-cid");
        assertPreEncodedResource(resolved.getEmbeddedImages(), "provided-logo", BASE_64, null, "provided-image-cid");
        assertPreEncodedResource(resolved.getEmbeddedImages(), "default-logo", BASE_64, null, "default-image-cid");
    }

    private static void assertPreEncodedResource(List<AttachmentResource> resources, String name, ContentTransferEncoding encoding, String description, String contentId) {
        val resource = resources.stream()
                .filter(candidate -> name.equals(candidate.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("Expected resource <" + name + "> to be present"));

        assertThat(resource.getPreEncodedContentTransferEncoding()).isEqualTo(encoding);
        assertThat(resource.getContentTransferEncoding()).isNull();
        assertThat(resource.getDescription()).isEqualTo(description);
        assertThat(resource.getContentId()).isEqualTo(contentId);
    }
}
