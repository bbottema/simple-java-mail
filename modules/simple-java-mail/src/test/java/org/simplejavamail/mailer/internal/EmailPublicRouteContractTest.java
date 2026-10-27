package org.simplejavamail.mailer.internal;

import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.CalendarMethod;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.ContentEncodingConfig;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.email.config.ImageResolutionConfig;
import org.simplejavamail.api.email.config.SmtpDeliveryOptions;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.mailer.internal.EmailContractTestSupport.ADDRESS;
import static org.simplejavamail.mailer.internal.EmailContractTestSupport.NAME;
import static org.simplejavamail.mailer.internal.EmailContractTestSupport.TEXT;
import static org.simplejavamail.mailer.internal.EmailContractTestSupport.value;
import static testutil.configcontracts.ConfigurationContractCoverage.invoke;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

class EmailPublicRouteContractTest {
    static Stream<Method> routes() { return EmailContractTestSupport.routes(EmailPopulatingBuilder.class); }

    @ParameterizedTest(name = "{0}")
    @MethodSource("routes")
    void everyMessageChoiceOverloadChangesItsIntendedField(final Method method, @TempDir final Path directory) throws Exception {
        final EmailContractTestSupport fixture = new EmailContractTestSupport(directory);
        final String name = method.getName();
        if (name.contains("PlainText") || name.contains("HTMLText")) { body(method, fixture); }
        else if (name.equals("withSubject") || name.equals("clearSubject")) { scalar(method, fixture, "getSubject", "email.SUBJECT", "old", TEXT); }
        else if (name.equals("fixingMessageId") || name.equals("clearId")) { scalar(method, fixture, "getId", "email.ID", "<old@example.org>", "<new@example.org>"); }
        else if (name.contains("SentDate")) { date(method, fixture); }
        else if (name.equals("from") || name.equals("clearFromRecipient") || name.contains("BounceTo")) { address(method, fixture); }
        else if (name.contains("DispositionNotification") || name.contains("ReturnReceipt")) { receipt(method, fixture); }
        else if (name.equals("to") || name.equals("cc") || name.equals("bcc") || name.contains("Recipients")
                || name.contains("ReplyTo") || name.contains("OverrideReceivers")) { recipients(method, fixture); }
        else if (name.contains("Attachment") && !name.contains("SMIME") || name.contains("EmbeddedImage")) { resources(method, fixture); }
        else if (name.contains("Header")) { headers(method, fixture); }
        else if (name.contains("Calendar")) { calendar(method, fixture); }
        else if (name.contains("Smime") || name.contains("OpenPgp") || name.equals("signWithDomainKey") || name.equals("clearDkim")) { protection(method, fixture); }
        else if (name.contains("SMIME")) { mergePolicy(method, fixture); }
        else if (name.contains("Config") || name.contains("Options")) { groups(method, fixture); }
        else { throw new AssertionError("No Email route fixture for " + method); }
    }

    private static void scalar(final Method method, final EmailContractTestSupport fixture, final String getter,
            final String field, final String old, final String supplied) throws Exception {
        final EmailPopulatingBuilder builder = fixture.mail.emailBuilder().startingBlank();
        if (getter.equals("getSubject")) { builder.withSubject(old); }
        else { builder.fixingMessageId(old); }
        assertThat(invoke(builder, method, method.getParameterCount() == 0 ? new Object[0] : new Object[] {supplied})).isSameAs(builder);
        assertThat(value(builder.buildEmail(), getter)).isEqualTo(method.getName().startsWith("clear") ? null : supplied);
        record("field", field);
    }

    private static void body(final Method method, final EmailContractTestSupport fixture) throws Exception {
        final EmailPopulatingBuilder builder = fixture.mail.emailBuilder().startingBlank().withPlainText("old").withHTMLText("old");
        final String name = method.getName();
        final Object[] args = method.getParameterCount() == 0 ? new Object[0] : new Object[] {
                method.getParameterTypes()[0] == String.class ? TEXT : method.getParameterTypes()[0] == Path.class ? fixture.textFile : fixture.textFile.toFile()};
        assertThat(invoke(builder, method, args)).isSameAs(builder);
        final String expected = name.startsWith("clear") ? null : name.startsWith("append") ? "old" + TEXT
                : name.startsWith("prepend") ? TEXT + "old" : TEXT;
        final Email result = builder.buildEmail();
        assertThat(name.contains("Plain") ? result.getPlainText() : result.getHTMLText()).isEqualTo(expected);
        record("field", name.contains("Plain") ? "email.BODY_TEXT" : "email.BODY_HTML");
    }

    private static void date(final Method method, final EmailContractTestSupport fixture) {
        final EmailPopulatingBuilder builder = fixture.mail.emailBuilder().startingBlank().fixingSentDate(new Date(1));
        final Object[] args = method.getParameterCount() == 0 ? new Object[0] : new Object[] {
                method.getParameterTypes()[0] == Date.class ? new Date(2000) : Instant.ofEpochMilli(2000)};
        assertThat(invoke(builder, method, args)).isSameAs(builder);
        assertThat(builder.buildEmail().getSentDate()).isEqualTo(method.getName().startsWith("clear") ? null : new Date(2000));
        record("field", "email.SENT_DATE");
    }

    private static Object[] addressArguments(final Method method, final EmailContractTestSupport fixture) throws Exception {
        if (method.getParameterCount() == 0) { return new Object[0]; }
        final Class<?> first = method.getParameterTypes()[0];
        if (first == Recipient.class) { return new Object[] {fixture.recipient}; }
        if (first == InternetAddress.class) { return new Object[] {new InternetAddress(ADDRESS, NAME)}; }
        if (method.getParameterCount() == 2) { return new Object[] {NAME, method.getParameterTypes()[1] == String.class
                ? ADDRESS : new InternetAddress(ADDRESS, "Embedded")}; }
        return new Object[] {ADDRESS};
    }

    private static void address(final Method method, final EmailContractTestSupport fixture) throws Exception {
        final EmailPopulatingBuilder builder = fixture.mail.emailBuilder().startingBlank().from("old", "old@example.org").withBounceTo("old@example.org");
        assertThat(invoke(builder, method, addressArguments(method, fixture))).isSameAs(builder);
        final boolean from = method.getName().equals("from") || method.getName().equals("clearFromRecipient");
        final Recipient result = from ? builder.buildEmail().getFromRecipient() : builder.buildEmail().getBounceToRecipient();
        if (method.getName().startsWith("clear")) { assertThat(result).isNull(); }
        else {
            assertThat(result.getAddress()).isEqualTo(ADDRESS);
            assertThat(result.getName()).isEqualTo(method.getParameterCount() == 1 && method.getParameterTypes()[0] == String.class ? null : NAME);
        }
        record("field", from ? "email.FROM_RECIPIENT" : "email.BOUNCETO_RECIPIENT");
    }

    private static void receipt(final Method method, final EmailContractTestSupport fixture) throws Exception {
        final EmailPopulatingBuilder builder = fixture.mail.emailBuilder().startingBlank().withDispositionNotificationTo("old@example.org")
                .withReturnReceiptTo("old@example.org");
        final Object[] args = method.getParameterCount() == 1 && Collection.class.isAssignableFrom(method.getParameterTypes()[0])
                ? new Object[] {List.of(fixture.recipient)} : addressArguments(method, fixture);
        assertThat(invoke(builder, method, args)).isSameAs(builder);
        final Email result = builder.buildEmail();
        final boolean disposition = method.getName().contains("Disposition");
        final List<Recipient> destinations = disposition ? result.getDispositionNotificationToRecipients() : result.getReturnReceiptToRecipients();
        final Boolean requested = disposition ? result.getUseDispositionNotificationTo() : result.getUseReturnReceiptTo();
        if (method.getName().startsWith("clear")) { assertThat(requested).isNull(); assertThat(destinations).isEmpty(); }
        else { assertThat(requested).isTrue();
            if (method.getParameterCount() == 0) { assertThat(destinations).isEmpty(); }
            else { assertThat(destinations).extracting(Recipient::getAddress).containsExactly(ADDRESS); } }
        record("field", disposition ? "email.DISPOSITION_NOTIFICATION_TO" : "email.RETURN_RECEIPT_TO");
        record("field", disposition ? "email.USE_DISPOSITION_NOTIFICATION_TO" : "email.USE_RETURN_RECEIPT_TO");
    }

    private static void recipients(final Method method, final EmailContractTestSupport fixture) throws Exception {
        final EmailPopulatingBuilder builder = fixture.mail.emailBuilder().startingBlank();
        final String name = method.getName();
        final String getter;
        final String field;
        if (name.contains("ReplyTo")) { builder.withReplyTo("old@example.org"); getter = "getReplyToRecipients"; field = "email.REPLYTO_RECIPIENT"; }
        else if (name.contains("OverrideReceivers")) { builder.withOverrideReceivers(new Recipient("old", "old@example.org", TO, null)); getter = "getOverrideReceivers"; field = "email.OVERRIDE_RECEIVERS"; }
        else { getter = "getRecipients"; field = name.equals("cc") ? "email.CC_RECIPIENTS" : name.equals("bcc") ? "email.BCC_RECIPIENTS" : "email.TO_RECIPIENTS";
            if (name.startsWith("clear")) { builder.to("old@example.org"); } }
        final Object[] args;
        if (method.getParameterCount() == 0) { args = new Object[0]; }
        else if (method.getParameterTypes()[0] == Recipient[].class) { args = new Object[] {new Recipient[] {fixture.recipient}}; }
        else if (Collection.class.isAssignableFrom(method.getParameterTypes()[0])) { args = new Object[] {List.of(fixture.recipient)}; }
        else { args = addressArguments(method, fixture); }
        assertThat(invoke(builder, method, args)).isSameAs(builder);
        @SuppressWarnings("unchecked") final Collection<Recipient> selected = (Collection<Recipient>) value(builder.buildEmail(), getter);
        if (name.startsWith("clear")) { assertThat(selected).isEmpty(); }
        else {
            final boolean appended = name.contains("ReplyTo") || name.contains("OverrideReceivers");
            assertThat(selected).extracting(Recipient::getAddress).containsExactlyElementsOf(appended ? List.of("old@example.org", ADDRESS) : List.of(ADDRESS));
            if (name.equals("to") || name.equals("cc") || name.equals("bcc")) {
                assertThat(selected.iterator().next().getType()).isEqualTo(name.equals("to") ? TO : name.equals("cc") ? jakarta.mail.Message.RecipientType.CC : jakarta.mail.Message.RecipientType.BCC);
            }
        }
        record("field", field);
    }

    private static void resources(final Method method, final EmailContractTestSupport fixture) throws Exception {
        final EmailPopulatingBuilder builder = fixture.mail.emailBuilder().startingBlank();
        final String name = method.getName();
        final boolean image = name.contains("EmbeddedImage");
        if (name.startsWith("clear")) { if (image) { builder.withEmbeddedImage(fixture.resource); } else { builder.withAttachment(fixture.resource); } }
        final Object[] args;
        if (method.getParameterCount() == 0) { args = new Object[0]; }
        else if (method.getParameterTypes()[0] == AttachmentResource.class) { args = new Object[] {fixture.resource}; }
        else if (method.getParameterTypes()[0] == List.class) { args = new Object[] {List.of(fixture.resource)}; }
        else if (method.getParameterTypes()[0] == Path.class) { args = new Object[] {fixture.textFile}; }
        else if (method.getParameterTypes()[1] == Path.class) { args = new Object[] {"new.txt", fixture.textFile}; }
        else {
            final boolean preEncoded = name.contains("PreEncoded");
            final byte[] bytes = preEncoded ? java.util.Base64.getEncoder().encode(TEXT.getBytes(StandardCharsets.UTF_8)) : TEXT.getBytes(StandardCharsets.UTF_8);
            final Object data = method.getParameterTypes()[1] == byte[].class ? bytes : fixture.resource.getDataSource();
            args = method.getParameterCount() == 2 ? new Object[] {"new.txt", data}
                    : method.getParameterCount() == 4 ? new Object[] {"new.txt", data, "text/plain", ContentTransferEncoding.BASE_64}
                    : method.getParameterTypes()[2] == ContentTransferEncoding.class ? new Object[] {"new.txt", data, ContentTransferEncoding.BASE_64}
                    : new Object[] {"new.txt", data, "text/plain"};
        }
        assertThat(invoke(builder, method, args)).isSameAs(builder);
        final List<AttachmentResource> selected = image ? builder.buildEmail().getEmbeddedImages() : builder.buildEmail().getAttachments();
        if (name.startsWith("clear")) { assertThat(selected).isEmpty(); }
        else {
            assertThat(selected).hasSize(1);
            final boolean encodedBytes = name.contains("PreEncoded") && method.getParameterTypes()[1] == byte[].class;
            assertThat(selected.get(0).readAllBytes()).containsExactly(encodedBytes
                    ? java.util.Base64.getEncoder().encode(TEXT.getBytes(StandardCharsets.UTF_8)) : TEXT.getBytes(StandardCharsets.UTF_8));
            if (name.contains("PreEncoded")) { assertThat(selected.get(0).getPreEncodedContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64); }
            if (method.getParameterTypes()[0] == AttachmentResource.class || method.getParameterTypes()[0] == List.class) {
                assertThat(selected.get(0).getDataSource()).isSameAs(fixture.resource.getDataSource());
            }
            if (method.getParameterCount() > 1 && method.getParameterTypes()[1] == jakarta.activation.DataSource.class) {
                assertThat(selected.get(0).getDataSource()).isSameAs(fixture.resource.getDataSource());
            }
        }
        record("field", image ? "email.EMBEDDED_IMAGES" : "email.ATTACHMENTS");
    }

    private static void headers(final Method method, final EmailContractTestSupport fixture) {
        final EmailPopulatingBuilder builder = fixture.mail.emailBuilder().startingBlank().withHeader("X-Route", "old");
        final Object[] args = method.getName().equals("clearHeaders") ? new Object[0] : method.getName().equals("withHeaders")
                ? new Object[] {Map.of("X-Route", List.of("new"))} : method.getParameterCount() == 3
                ? new Object[] {"x-route", "new", true} : new Object[] {"x-route", "new"};
        assertThat(invoke(builder, method, args)).isSameAs(builder);
        if (method.getName().startsWith("clear")) { assertThat(builder.buildEmail().getHeaders()).isEmpty(); }
        else { assertThat(builder.buildEmail().getHeaders().get("X-Route")).containsExactlyElementsOf(method.getParameterCount() == 3 ? List.of("new") : List.of("old", "new")); }
        record("field", "email.HEADERS");
    }

    private static void calendar(final Method method, final EmailContractTestSupport fixture) {
        final EmailPopulatingBuilder builder = fixture.mail.emailBuilder().startingBlank().withCalendarText(CalendarMethod.REQUEST, "old");
        assertThat(invoke(builder, method, method.getParameterCount() == 0 ? new Object[0] : new Object[] {CalendarMethod.CANCEL, TEXT})).isSameAs(builder);
        final Email result = builder.buildEmail();
        assertThat(result.getCalendarText()).isEqualTo(method.getName().startsWith("clear") ? null : TEXT);
        assertThat(result.getCalendarMethod()).isEqualTo(method.getName().startsWith("clear") ? null : CalendarMethod.CANCEL);
        record("field", "email.CALENDAR_TEXT"); record("field", "email.CALENDAR_METHOD");
    }

    private static void protection(final Method method, final EmailContractTestSupport fixture) throws Exception {
        final EmailPopulatingBuilder builder = fixture.mail.emailBuilder().startingBlank();
        final String name = method.getName();
        final boolean clear = name.startsWith("clear");
        final String getter;
        final String field;
        final Object chosen;
        if (name.contains("Dkim") || name.equals("signWithDomainKey")) { getter = "getDkimConfig"; field = "email.DKIM_SIGNING_CONFIG"; chosen = fixture.dkim; builder.signWithDomainKey(fixture.dkim); }
        else if (name.contains("OpenPgp")) {
            if (name.contains("Encryption") || name.startsWith("encrypt")) { getter = "getOpenPgpEncryptionConfig"; field = "email.OPENPGP_ENCRYPTION_CONFIG"; chosen = fixture.pgpEncryption; builder.encryptWithOpenPgp(fixture.pgpEncryption); }
            else { getter = "getOpenPgpSigningConfig"; field = "email.OPENPGP_SIGNING_CONFIG"; chosen = fixture.pgpSigning; builder.signWithOpenPgp(fixture.pgpSigning); }
        } else {
            if (name.contains("Encryption") || name.startsWith("encrypt")) { getter = "getSmimeEncryptionConfig"; field = "email.SMIME_ENCRYPTION_CONFIG"; chosen = fixture.smimeEncryption; builder.encryptWithSmime(fixture.smimeEncryption); }
            else { getter = "getSmimeSigningConfig"; field = "email.SMIME_SIGNING_CONFIG"; chosen = fixture.smimeSigning; builder.signWithSmime(fixture.smimeSigning); }
        }
        if (name.equals("clearSmime")) { builder.encryptWithSmime(fixture.smimeEncryption); }
        if (name.equals("clearOpenPgp")) { builder.encryptWithOpenPgp(fixture.pgpEncryption); }
        assertThat(invoke(builder, method, clear ? new Object[0] : new Object[] {chosen})).isSameAs(builder);
        assertThat(value(builder.buildEmail(), getter)).isEqualTo(clear ? null : chosen);
        if (name.equals("clearSmime")) { assertThat(builder.buildEmail().getSmimeEncryptionConfig()).isNull(); }
        if (name.equals("clearOpenPgp")) { assertThat(builder.buildEmail().getOpenPgpEncryptionConfig()).isNull(); }
        record("field", field);
    }

    private static void mergePolicy(final Method method, final EmailContractTestSupport fixture) {
        final EmailPopulatingBuilder builder = fixture.mail.emailBuilder().startingBlank().notMergingSingleSMIMESignedAttachment();
        assertThat(invoke(builder, method)).isSameAs(builder);
        assertThat(builder.isMergeSingleSMIMESignedAttachment()).isEqualTo(method.getName().startsWith("clear"));
    }

    private static void groups(final Method method, final EmailContractTestSupport fixture) {
        final EmailPopulatingBuilder builder = fixture.mail.emailBuilder().startingBlank();
        final String name = method.getName();
        final boolean configured = name.startsWith("useConfigured");
        if (name.contains("ContentEncoding")) {
            builder.withContentEncodingConfig(ContentEncodingConfig.builder().withContentTransferEncoding(ContentTransferEncoding.BASE_64).build());
            final ContentEncodingConfig chosen = ContentEncodingConfig.builder().withContentTransferEncoding(ContentTransferEncoding.BIT7).build();
            assertThat(invoke(builder, method, configured ? new Object[0] : new Object[] {chosen})).isSameAs(builder);
            assertThat(builder.getContentEncodingConfig().getContentTransferEncoding()).isEqualTo(configured ? null : ContentTransferEncoding.BIT7);
        } else if (name.contains("ImageResolution")) {
            builder.withImageResolutionConfig(ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForFiles(false).build());
            final ImageResolutionConfig chosen = ImageResolutionConfig.builder().withEmbeddedImageAutoResolutionForFiles(true).build();
            assertThat(invoke(builder, method, configured ? new Object[0] : new Object[] {chosen})).isSameAs(builder);
            assertThat(builder.getImageResolutionConfig().getEmbeddedImageAutoResolutionForFiles()).isEqualTo(configured ? null : true);
        } else if (name.contains("TemplateOptions")) {
            builder.withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().build());
            final EmailTemplateOptions chosen = EmailTemplateOptions.builder().ignoringOverrides().build();
            assertThat(invoke(builder, method, configured ? new Object[0] : new Object[] {chosen})).isSameAs(builder);
            assertThat(builder.getTemplateOptions().isIgnoreOverrides()).isEqualTo(!configured);
            assertThat(builder.getTemplateOptions().isIgnoreDefaults()).isFalse();
        } else if (name.contains("SmtpDeliveryOptions")) {
            builder.withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withDeliveryStatusNotification(DeliveryStatusNotification.NotifyOption.FAILURE).build());
            final SmtpDeliveryOptions chosen = SmtpDeliveryOptions.builder().withTlsRequiredForOnwardDelivery().build();
            assertThat(invoke(builder, method, configured ? new Object[0] : new Object[] {chosen})).isSameAs(builder);
            assertThat(builder.getSmtpDeliveryOptions().isTlsRequiredForOnwardDelivery()).isEqualTo(!configured);
        } else { throw new AssertionError("No group fixture for " + method); }
    }
}
