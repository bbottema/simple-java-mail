package org.simplejavamail.internal.clisupport;

import jakarta.mail.Message;
import jakarta.mail.util.ByteArrayDataSource;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.ContentEncodingConfig;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.email.config.SmtpDeliveryOptions;

import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateFactory;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Independent Email option readbacks; the common fixture owns the actual generated parser and binding phase. */
final class CliEmailInputFixture {
    private CliEmailInputFixture() { }

    static void seed(final String option, final Object current, final CliConfigurationInputContractTest.FilesFixture files) throws Exception {
        if (current instanceof ExactEmailBuilder) {
            final ExactEmailBuilder exact = (ExactEmailBuilder) current;
            exact.withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withDeliveryStatusNotification(
                    DeliveryStatusNotification.ReturnOption.FULL_MESSAGE, DeliveryStatusNotification.NotifyOption.SUCCESS).fixingEnvelopeId("before").build());
            return;
        }
        if (!(current instanceof EmailPopulatingBuilder)) { return; }
        final EmailPopulatingBuilder email = (EmailPopulatingBuilder) current;
        email.from("Before", "before@example.test").to("before-to@example.test").withReplyTo("before-reply@example.test")
                .withBounceTo("before-bounce@example.test").withSubject("before").withPlainText("before").withHTMLText("before")
                .withCalendarText(CalendarMethod.PUBLISH, "before-calendar").fixingMessageId("before-id").fixingSentDate(new Date(0))
                .withHeader("X-Seed", "seed").withDispositionNotificationTo("before-notify@example.test")
                .withReturnReceiptTo("before-return@example.test").withOverrideReceivers(new Recipient("Before", "before-override@example.test", Message.RecipientType.TO, null));
        final ByteArrayDataSource data = new ByteArrayDataSource("seed".getBytes(StandardCharsets.UTF_8), "text/plain");
        email.withAttachment("seed", data).withEmbeddedImage("seed", data);
        email.withContentEncodingConfig(ContentEncodingConfig.builder().withContentTransferEncoding(ContentTransferEncoding.BIT8)
                .withPlainTextContentTransferEncoding(ContentTransferEncoding.BIT8).withHTMLTextContentTransferEncoding(ContentTransferEncoding.BIT8)
                .withCalendarTextContentTransferEncoding(ContentTransferEncoding.BIT8).build());
        email.withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForFiles(false)
                .withEmbeddedImageAutoResolutionForClassPathResources(false).withEmbeddedImageAutoResolutionForURLs(false)
                .withEmbeddedImageBaseDir("before").withEmbeddedImageBaseClassPath("before").withEmbeddedImageBaseUrl("https://before.example.test/")
                .allowingEmbeddedImageOutsideBaseDir(false).allowingEmbeddedImageOutsideBaseClassPath(false).allowingEmbeddedImageOutsideBaseUrl(false)
                .embeddedImageAutoResolutionMustBeSuccesful(false).build());
        email.withTemplateOptions(EmailTemplateOptions.builder().dontApplyDefaultValueFor(EmailProperty.SUBJECT)
                .dontApplyOverrideValueFor(EmailProperty.SUBJECT).build());
        email.withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withDeliveryStatusNotification(
                DeliveryStatusNotification.ReturnOption.FULL_MESSAGE, DeliveryStatusNotification.NotifyOption.SUCCESS)
                .fixingEnvelopeId("before").withSendingToAcceptedRecipients(false).build());
        if (option.equals("--email:clearTlsRequiredForOnwardDelivery")) {
            email.withSmtpDeliveryOptions(email.getSmtpDeliveryOptions().toBuilder().withTlsRequiredForOnwardDelivery().build());
        }
        if (option.equals("--email:clearSMIMESignedAttachmentMergingBehavior")) { email.notMergingSingleSMIMESignedAttachment(); }
        if (option.equals("--email:withDispositionNotificationToEnabled")) { email.clearDispositionNotificationTo(); }
        if (option.equals("--email:withReturnReceiptToEnabled")) { email.clearReturnReceiptTo(); }
        if (option.contains("Dkim")) { email.signWithDomainKey(DkimConfig.builder().dkimPrivateKeyData("opaque").dkimSigningDomain("example.test").dkimSelector("cli").build()); }
        if (option.contains("OpenPgp")) {
            email.signWithOpenPgp(OpenPgpSigningConfig.builder().secretKeyRing(new byte[] {1}).build());
            email.encryptWithOpenPgp(OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(new byte[] {2}).build());
        }
        if (option.contains("Smime")) {
            email.signWithSmime(SmimeSigningConfig.builder().pkcs12Config(Pkcs12Config.builder().pkcs12Store(new byte[] {1})
                    .storePassword("before").keyAlias("before").keyPassword("before").build()).build());
            email.encryptWithSmime(SmimeEncryptionConfig.builder().x509Certificate(files.certificate.toFile()).build());
        }
    }

    static void assertBound(final String option, final Object bound, final List<Object> arguments,
            final CliConfigurationInputContractTest.FilesFixture files) throws Exception {
        final String name = option.substring("--email:".length());
        if (name.equals("startingFromExactEml")) {
            final ExactEmailBuilder exact = (ExactEmailBuilder) bound;
            assertThat(exact.getEnvelopeRecipients()).isEmpty();
            final Email parsed = exact.withEnvelopeRecipients("recipient@example.test").buildEmail();
            assertThat(parsed.getSubject()).isEqualTo("CLI source");
            return;
        }
        if (bound instanceof ExactEmailBuilder) { assertExact(name, (ExactEmailBuilder) bound, arguments); return; }
        final EmailPopulatingBuilder email = (EmailPopulatingBuilder) bound;
        switch (name) {
            case "startingBlank": assertThat(email.getSubject()).isNull(); assertThat(email.getRecipients()).isEmpty(); return;
            case "copying": assertThat(email.getSubject()).isEqualTo("CLI source"); assertThat(email.getPlainText()).contains("CLI source body"); return;
            case "forwarding": assertThat(email.getSubject()).contains("CLI source"); assertThat(email.getEmailToForward()).isSameAs(arguments.get(0)); return;
            case "replyingTo": case "replyingToAll": case "replyingToAllWithDefaultQuoteMarkup": case "replyingToSender": case "replyingToSenderWithDefaultQuoteMarkup":
                assertThat(email.getSubject()).contains("CLI source"); assertThat(email.getRecipients()).extracting(Recipient::getAddress).contains("source@example.test"); return;
            case "withSubject": assertThat(email.getSubject()).isEqualTo(arguments.get(0)); return;
            case "clearSubject": assertThat(email.getSubject()).isNull(); return;
            case "withPlainText": assertThat(email.getPlainText()).isEqualTo(arguments.get(0)); return;
            case "withPlainTextFromFile": assertThat(email.getPlainText()).isEqualTo(CliConfigurationInputContractTest.FILE_BODY); return;
            case "appendText": assertThat(email.getPlainText()).isEqualTo("before" + arguments.get(0)); return;
            case "prependText": assertThat(email.getPlainText()).isEqualTo(arguments.get(0) + "before"); return;
            case "appendTextFromFile": assertThat(email.getPlainText()).isEqualTo("before" + CliConfigurationInputContractTest.FILE_BODY); return;
            case "prependTextFromFile": assertThat(email.getPlainText()).isEqualTo(CliConfigurationInputContractTest.FILE_BODY + "before"); return;
            case "clearPlainText": assertThat(email.getPlainText()).isNull(); return;
            case "withHTMLText": assertThat(email.getHTMLText()).isEqualTo(arguments.get(0)); return;
            case "withHTMLTextFromFile": assertThat(email.getHTMLText()).isEqualTo(CliConfigurationInputContractTest.FILE_BODY); return;
            case "appendTextHTML": assertThat(email.getHTMLText()).isEqualTo("before" + arguments.get(0)); return;
            case "prependTextHTML": assertThat(email.getHTMLText()).isEqualTo(arguments.get(0) + "before"); return;
            case "appendTextHTMLFromFile": assertThat(email.getHTMLText()).isEqualTo("before" + CliConfigurationInputContractTest.FILE_BODY); return;
            case "prependTextHTMLFromFile": assertThat(email.getHTMLText()).isEqualTo(CliConfigurationInputContractTest.FILE_BODY + "before"); return;
            case "clearHTMLText": assertThat(email.getHTMLText()).isNull(); return;
            case "fixingMessageId": assertThat(email.getId()).isEqualTo(arguments.get(0)); return;
            case "clearId": assertThat(email.getId()).isNull(); return;
            case "fixingSentDate": assertThat(email.getSentDate()).isEqualTo(arguments.get(0)); return;
            case "clearSentDate": assertThat(email.getSentDate()).isNull(); return;
            case "from": assertContact(email.getFromRecipient(), arguments); return;
            case "clearFromRecipient": assertThat(email.getFromRecipient()).isNull(); return;
            case "withBounceTo": assertContact(email.getBounceToRecipient(), arguments); return;
            case "clearBounceTo": assertThat(email.getBounceToRecipient()).isNull(); return;
            case "to": case "cc": case "bcc": case "withRecipients":
                final Message.RecipientType type = name.equals("cc") ? Message.RecipientType.CC : name.equals("bcc") ? Message.RecipientType.BCC
                        : name.equals("withRecipients") ? (Message.RecipientType) arguments.get(2) : Message.RecipientType.TO;
                final List<Recipient> recipients = email.getRecipients().stream().filter(recipient -> recipient.getType() == type).collect(java.util.stream.Collectors.toList());
                assertThat(recipients).extracting(Recipient::getAddress).contains("alpha@example.test", "beta@example.test");
                assertThat(recipients.get(recipients.size() - 1).getName()).isEqualTo(arguments.get(0)); return;
            case "clearRecipients": assertThat(email.getRecipients()).isEmpty(); return;
            case "withReplyTo": assertContact(email.getReplyToRecipients().get(email.getReplyToRecipients().size() - 1), arguments); return;
            case "clearReplyTo": assertThat(email.getReplyToRecipients()).isEmpty(); return;
            case "withDispositionNotificationTo": assertContact(email.getDispositionNotificationToRecipients().get(0), arguments); assertThat(email.getUseDispositionNotificationTo()).isTrue(); return;
            case "withDispositionNotificationToEnabled": assertThat(email.getUseDispositionNotificationTo()).isTrue(); return;
            case "clearDispositionNotificationTo": assertThat(email.getDispositionNotificationToRecipients()).isEmpty(); assertThat(email.getUseDispositionNotificationTo()).isNull(); return;
            case "withReturnReceiptTo": assertContact(email.getReturnReceiptToRecipients().get(0), arguments); assertThat(email.getUseReturnReceiptTo()).isTrue(); return;
            case "withReturnReceiptToEnabled": assertThat(email.getUseReturnReceiptTo()).isTrue(); return;
            case "clearReturnReceiptTo": assertThat(email.getReturnReceiptToRecipients()).isEmpty(); assertThat(email.getUseReturnReceiptTo()).isNull(); return;
            case "withCalendarText": assertThat(email.getCalendarMethod()).isEqualTo(arguments.get(0)); assertThat(email.getCalendarText()).isEqualTo(arguments.get(1)); return;
            case "clearCalendarText": assertThat(email.getCalendarMethod()).isNull(); assertThat(email.getCalendarText()).isNull(); return;
            case "withHeader": assertThat(email.getHeaders()).containsEntry((String) arguments.get(0), List.of((String) arguments.get(1))); return;
            case "clearHeaders": assertThat(email.getHeaders()).isEmpty(); return;
            case "withAttachment": case "withDescribedAttachment": case "withEncodedDescribedAttachment":
                assertResource(email.getAttachments().get(email.getAttachments().size() - 1), arguments); return;
            case "withEmbeddedImage": assertResource(email.getEmbeddedImages().get(email.getEmbeddedImages().size() - 1), arguments); return;
            case "clearAttachments": assertThat(email.getAttachments()).isEmpty(); return;
            case "clearEmbeddedImages": assertThat(email.getEmbeddedImages()).isEmpty(); return;
            case "clearOverrideReceivers": assertThat(email.getOverrideReceivers()).isEmpty(); return;
            case "withContentTransferEncoding": assertThat(email.getContentEncodingConfig().getContentTransferEncoding()).isEqualTo(arguments.get(0)); return;
            case "clearContentTransferEncoding": assertThat(email.getContentEncodingConfig().getContentTransferEncoding()).isEqualTo(ContentTransferEncoding.QUOTED_PRINTABLE); return;
            case "withPlainTextContentTransferEncoding": assertThat(email.getContentEncodingConfig().getPlainTextContentTransferEncoding()).isEqualTo(arguments.get(0)); return;
            case "clearPlainTextContentTransferEncoding": assertThat(email.getContentEncodingConfig().getPlainTextContentTransferEncoding()).isNull(); return;
            case "withHTMLTextContentTransferEncoding": assertThat(email.getContentEncodingConfig().getHTMLTextContentTransferEncoding()).isEqualTo(arguments.get(0)); return;
            case "clearHTMLTextContentTransferEncoding": assertThat(email.getContentEncodingConfig().getHTMLTextContentTransferEncoding()).isNull(); return;
            case "withCalendarTextContentTransferEncoding": assertThat(email.getContentEncodingConfig().getCalendarTextContentTransferEncoding()).isEqualTo(arguments.get(0)); return;
            case "clearCalendarTextContentTransferEncoding": assertThat(email.getContentEncodingConfig().getCalendarTextContentTransferEncoding()).isNull(); return;
            case "ignoringDefaultsYesNo": assertThat(email.getTemplateOptions().isIgnoreDefaults()).isTrue(); return;
            case "ignoringOverridesYesNo": assertThat(email.getTemplateOptions().isIgnoreOverrides()).isTrue(); return;
            case "clearDefaultValueSuppressions": assertThat(email.getTemplateOptions().getPropertiesNotToApplyDefaultValueFor()).isNullOrEmpty(); return;
            case "clearOverrideValueSuppressions": assertThat(email.getTemplateOptions().getPropertiesNotToApplyOverrideValueFor()).isNullOrEmpty(); return;
            case "notMergingSingleSMIMESignedAttachment": assertThat(email.isMergeSingleSMIMESignedAttachment()).isFalse(); return;
            case "clearSMIMESignedAttachmentMergingBehavior": assertThat(email.isMergeSingleSMIMESignedAttachment()).isTrue(); return;
            case "clearDkim": assertThat(email.getDkimConfig()).isNull(); return;
            case "clearOpenPgp": assertThat(email.getOpenPgpSigningConfig()).isNull(); assertThat(email.getOpenPgpEncryptionConfig()).isNull(); return;
            case "clearOpenPgpSigning": assertThat(email.getOpenPgpSigningConfig()).isNull(); assertThat(email.getOpenPgpEncryptionConfig()).isNotNull(); return;
            case "clearOpenPgpEncryption": assertThat(email.getOpenPgpEncryptionConfig()).isNull(); assertThat(email.getOpenPgpSigningConfig()).isNotNull(); return;
            case "clearSmime": assertThat(email.getSmimeSigningConfig()).isNull(); assertThat(email.getSmimeEncryptionConfig()).isNull(); return;
            case "clearSmimeSigning": assertThat(email.getSmimeSigningConfig()).isNull(); assertThat(email.getSmimeEncryptionConfig()).isNotNull(); return;
            case "clearSmimeEncryption": assertThat(email.getSmimeEncryptionConfig()).isNull(); assertThat(email.getSmimeSigningConfig()).isNotNull(); return;
            case "signWithSmime":
                assertThat(email.getSmimeSigningConfig().getPkcs12Config().getPkcs12StoreData()).containsExactly((byte) 1, (byte) 2, (byte) 3);
                assertThat(email.getSmimeSigningConfig().getPkcs12Config().getStorePassword()).containsExactly(((String) arguments.get(1)).toCharArray());
                assertThat(email.getSmimeSigningConfig().getPkcs12Config().getKeyAlias()).isEqualTo(arguments.get(2));
                assertThat(email.getSmimeSigningConfig().getPkcs12Config().getKeyPassword()).containsExactly(((String) arguments.get(3)).toCharArray());
                assertThat(email.getSmimeSigningConfig().getSignatureAlgorithm()).isEqualTo(arguments.get(4)); return;
            case "encryptWithSmime":
                try (java.io.InputStream input = java.nio.file.Files.newInputStream(files.certificate)) {
                    assertThat(email.getSmimeEncryptionConfig().getX509Certificate().getEncoded())
                            .containsExactly(CertificateFactory.getInstance("X.509").generateCertificate(input).getEncoded());
                }
                assertThat(email.getSmimeEncryptionConfig().getKeyEncapsulationAlgorithm()).isEqualTo(arguments.get(1));
                assertThat(email.getSmimeEncryptionConfig().getCipherAlgorithm()).isEqualTo(arguments.get(2)); return;
            case "withDeliveryStatusNotificationNotifyOptions": case "withDeliveryStatusNotificationReturnOption": case "fixingEnvelopeId":
            case "clearDeliveryStatusNotification": case "withSendingToAcceptedRecipients": case "withTlsRequiredForOnwardDelivery": case "clearTlsRequiredForOnwardDelivery":
                assertDelivery(name, email.getSmtpDeliveryOptions(), arguments); return;
            default: assertImage(name, email.getImageResolutionConfig(), arguments, files);
        }
    }

    private static void assertExact(final String name, final ExactEmailBuilder email, final List<Object> arguments) {
        switch (name) {
            case "withEnvelopeRecipients": assertThat(email.getEnvelopeRecipients()).extracting(Recipient::getAddress).containsExactly("before@example.test", "alpha@example.test", "beta@example.test"); return;
            case "withEnvelopeSender": assertThat(email.getEnvelopeSender().getAddress()).isEqualTo(arguments.get(0)); return;
            case "withEnvelopeDsnNotifyOptions": assertDelivery("withDeliveryStatusNotificationNotifyOptions", email.getSmtpDeliveryOptions(), arguments); return;
            case "withEnvelopeDsnReturnOption": assertDelivery("withDeliveryStatusNotificationReturnOption", email.getSmtpDeliveryOptions(), arguments); return;
            case "fixingExactEnvelopeId": assertDelivery("fixingEnvelopeId", email.getSmtpDeliveryOptions(), arguments); return;
            case "withExactSendingToAcceptedRecipients": assertDelivery("withSendingToAcceptedRecipients", email.getSmtpDeliveryOptions(), arguments); return;
            case "withExactTlsRequiredForOnwardDelivery": assertDelivery("withTlsRequiredForOnwardDelivery", email.getSmtpDeliveryOptions(), arguments); return;
            default: throw new AssertionError("Missing exact CLI binding assertion: " + name);
        }
    }

    private static void assertDelivery(final String name, final SmtpDeliveryOptions options, final List<Object> arguments) {
        switch (name) {
            case "withDeliveryStatusNotificationNotifyOptions": assertThat(options.getDeliveryStatusNotification().getNotifyOptions()).containsExactlyInAnyOrder((DeliveryStatusNotification.NotifyOption[]) arguments.get(0)); return;
            case "withDeliveryStatusNotificationReturnOption": assertThat(options.getDeliveryStatusNotification().getReturnOption()).isEqualTo(arguments.get(0)); return;
            case "fixingEnvelopeId": assertThat(options.getDeliveryStatusNotification().getEnvelopeId()).isEqualTo(arguments.get(0)); return;
            case "clearDeliveryStatusNotification": assertThat(options.getDeliveryStatusNotification()).isNull(); return;
            case "withSendingToAcceptedRecipients": assertThat(options.getSendingToAcceptedRecipients()).isTrue(); return;
            case "withTlsRequiredForOnwardDelivery": assertThat(options.isTlsRequiredForOnwardDelivery()).isTrue(); return;
            case "clearTlsRequiredForOnwardDelivery": assertThat(options.isTlsRequiredForOnwardDelivery()).isFalse(); return;
            default: throw new AssertionError(name);
        }
    }

    private static void assertImage(final String name, final ImageResolutionConfig images, final List<Object> arguments,
            final CliConfigurationInputContractTest.FilesFixture files) {
        switch (name) {
            case "withEmbeddedImageAutoResolutionForFiles": assertThat(images.getEmbeddedImageAutoResolutionForFiles()).isTrue(); return;
            case "withEmbeddedImageAutoResolutionForClassPathResources": assertThat(images.getEmbeddedImageAutoResolutionForClassPathResources()).isTrue(); return;
            case "withEmbeddedImageAutoResolutionForURLs": assertThat(images.getEmbeddedImageAutoResolutionForURLs()).isTrue(); return;
            case "allowingEmbeddedImageOutsideBaseDir": assertThat(images.getAllowEmbeddedImageOutsideBaseDir()).isTrue(); return;
            case "allowingEmbeddedImageOutsideBaseClassPath": assertThat(images.getAllowEmbeddedImageOutsideBaseClassPath()).isTrue(); return;
            case "allowingEmbeddedImageOutsideBaseUrl": assertThat(images.getAllowEmbeddedImageOutsideBaseUrl()).isTrue(); return;
            case "embeddedImageAutoResolutionMustBeSuccesful": assertThat(images.getEmbeddedImageAutoResolutionMustBeSuccesful()).isTrue(); return;
            case "withEmbeddedImageBaseDir": assertThat(images.getEmbeddedImageBaseDir()).isEqualTo(arguments.get(0)); return;
            case "withEmbeddedImageBaseClassPath": assertThat(images.getEmbeddedImageBaseClassPath()).isEqualTo(arguments.get(0)); return;
            case "withEmbeddedImageBaseUrl": assertThat(images.getEmbeddedImageBaseUrl().toExternalForm()).isEqualTo(arguments.get(0)); return;
            case "clearEmbeddedImageBaseDir": assertThat(images.getEmbeddedImageBaseDir()).isNull(); assertThat(images.isEmbeddedImageBaseDirConfigured()).isTrue(); return;
            case "clearEmbeddedImageBaseClassPath": assertThat(images.getEmbeddedImageBaseClassPath()).isNull(); assertThat(images.isEmbeddedImageBaseClassPathConfigured()).isTrue(); return;
            case "clearEmbeddedImageBaseUrl": assertThat(images.getEmbeddedImageBaseUrl()).isNull(); assertThat(images.isEmbeddedImageBaseUrlConfigured()).isTrue(); return;
            default: throw new AssertionError("Missing independent Email CLI binding assertion: " + name);
        }
    }

    private static void assertContact(final Recipient recipient, final List<Object> arguments) {
        assertThat(recipient.getName()).isEqualTo(arguments.get(0));
        assertThat(recipient.getAddress()).isEqualTo(arguments.get(1));
    }

    private static void assertResource(final AttachmentResource resource, final List<Object> arguments) throws Exception {
        assertThat(resource.getName()).isEqualTo(arguments.get(0));
        assertThat(resource.readAllData()).isEqualTo(CliConfigurationInputContractTest.FILE_BODY);
        if (arguments.size() >= 3) { assertThat(resource.getDescription()).isEqualTo(arguments.get(2)); }
        if (arguments.size() >= 4) { assertThat(resource.getContentTransferEncoding()).isEqualTo(arguments.get(3)); }
    }
}
