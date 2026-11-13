package org.simplejavamail.email.internal;

import jakarta.activation.DataSource;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.*;
import org.simplejavamail.api.email.config.*;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.email.ReceivedEmailInspection;
import org.simplejavamail.internal.email.ValueInspectionImpl;
import org.simplejavamail.internal.email.ParsedAttachmentResource;

import java.io.*;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.util.TestDataHelper.loadPkcs12KeyStore;

/** Materialized logical selection exercises copy, completion and snapshot ownership after ADR 0040 selection. */
class RetainedContentMergeContractTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
    enum EntryPoint { MATERIALIZED_BUILD, COPIED_VIEW }
    enum Scheme { NONE, SMIME, OPENPGP }
    enum ReceiptChoice { UNSET, AUTOMATIC, EXPLICIT, CLEARED, LEGACY_DISABLED }

    private static final List<String> OUTER_GETTERS = List.of(
            "getId", "getFromRecipient", "getReplyToRecipients", "getBounceToRecipient", "getSubject", "getSentDate",
            "getRecipients", "getToRecipients", "getCcRecipients", "getBccRecipients", "getSentDateAsInstant",
            "getUseDispositionNotificationTo", "getDispositionNotificationToRecipients", "getUseReturnReceiptTo",
            "getReturnReceiptToRecipients", "getDeliveryStatusNotification", "isTlsRequiredForOnwardDelivery",
            "getSendingToAcceptedRecipients", "getOverrideReceivers", "getDkimConfig", "getSmimeSigningConfig",
            "getSmimeEncryptionConfig", "getOpenPgpSigningConfig", "getOpenPgpEncryptionConfig", "isIgnoreDefaults",
            "isIgnoreOverrides", "getPropertiesNotToApplyDefaultValueFor", "getPropertiesNotToApplyOverrideValueFor",
            "getContentTransferEncoding");

    @Test
    void everyStoredFieldHasAnExplicitContractRole() {
        Map<String, String> roles = new LinkedHashMap<>();
        role(roles, "serialization", "serializationFormatVersion");
        role(roles, "input history, current associations and per-name composition intent", "sourceData", "problems", "selectionInspection", "explicitHeaderNames", "confidentialHeaderNames", "capturedReceiptGroups");
        role(roles, "derived or legacy alias", "defaultSuppression", "overrideSuppression", "dispositionNotificationTo",
                "returnReceiptTo");
        role(roles, "outer metadata/policy/history", "ignoreDefaults", "ignoreOverrides", "propertiesNotToApplyDefaultValueFor",
                "propertiesNotToApplyOverrideValueFor", "id", "fromRecipient", "replyToRecipients", "bounceToRecipient",
                "contentTransferEncoding", "subject", "recipients", "useDispositionNotificationTo",
                "dispositionNotificationToRecipients", "useReturnReceiptTo", "returnReceiptToRecipients",
                "deliveryStatusNotification", "deliveryStatusReport", "tlsRequiredForOnwardDelivery", "sendingToAcceptedRecipients",
                "overrideReceivers", "emailToForward", "dkimConfig", "smimeEncryptionConfig", "smimeSigningConfig",
                "openPgpSigningConfig", "openPgpEncryptionConfig", "sentDate");
        role(roles, "inner body", "text", "textHTML", "plainTextContentTransferEncoding", "htmlTextContentTransferEncoding");
        role(roles, "selected calendar", "calendarMethod", "textCalendar", "calendarTextContentTransferEncoding");
        role(roles, "selected resource occurrences", "embeddedImages", "attachments");
        role(roles, "keyed header selection", "headers");
        Set<String> declared = new TreeSet<>();
        Arrays.stream(Email.class.getDeclaredFields()).filter(f -> !Modifier.isStatic(f.getModifiers()))
                .forEach(f -> declared.add(f.getName()));
        assertThat(declared).containsExactlyInAnyOrderElementsOf(roles.keySet());
    }

    @ParameterizedTest
    @MethodSource("entryPointsAndSchemes")
    void completeMaterializedSelectionRetainsTheDeclaredOwners(EntryPoint point, Scheme scheme) throws Exception {
        Fixture fixture = new Fixture(scheme);
        Email outer = fixture.outer().buildEmail();
        InternalEmailPopulatingBuilder actualSource = fixture.outer();
        Map<String, Collection<String>> beforeHeaders = new LinkedHashMap<>(actualSource.getHeaders());
        Email result = materialize(actualSource, point);
        assertSelected(outer, fixture.inner, result, false);
        assertThat(actualSource.getHeaders()).isEqualTo(beforeHeaders);
        assertThat(actualSource.getPlainText()).isEqualTo("inner plain");
        assertThat(actualSource.getAttachments()).containsExactly(fixture.outerResource, fixture.outerResource, fixture.innerResource);
        assertThat(fixture.inner.getPlainText()).isEqualTo("inner plain");
        assertThat(fixture.inner.getHeaders().get("x-shared")).containsExactly("inner-one", "inner-two");
        assertThat(fixture.outerResource.getDataSource()).isSameAs(result.getAttachments().get(0).getDataSource());
        assertThat(fixture.innerResource.getDataSource()).isSameAs(result.getAttachments().get(2).getDataSource());
    }

    @ParameterizedTest
    @EnumSource(EntryPoint.class)
    void serializationAndRepeatedCopyPreserveTheSelectedStateWithoutRemerging(EntryPoint point) throws Exception {
        Fixture fixture = new Fixture(Scheme.SMIME);
        Email outer = fixture.outer().buildEmail();
        Email current = roundTrip(fixture.materialize(point));
        for (int i = 0; i < 3; i++) {
            assertSelected(outer, fixture.inner, current, true);
            current = MAIL.emailBuilder().copying(current).buildEmail();
        }
    }

    @ParameterizedTest
    @EnumSource(EntryPoint.class)
    void outgoingCompletionKeepsVisibleContentAndHistoricalInputWithoutRecreatingAReport(EntryPoint point) throws Exception {
        Fixture fixture = new Fixture(Scheme.NONE);
        Email input = fixture.materialize(point);
        try (org.simplejavamail.api.mailer.Mailer mailer = MAIL.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer()) {
            Email completed = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(input);
            assertThat(completed.getPlainText()).isEqualTo("inner plain");
            assertThat(completed.getHTMLText()).isEqualTo("<p>inner</p>");
            assertThat(completed.getAttachments()).hasSize(3);
            assertThat(completed.getOverrideReceivers()).isEqualTo(input.getOverrideReceivers());
            assertThat(completed.getDeliveryStatusReport()).isNull();
            assertThat(completed.getInspection().getOriginalMessage()).isSameAs(input.getInspection().getOriginalMessage());
            assertThat(completed.getSourceData()).isSameAs(input.getSourceData());
            assertThat(input.getDeliveryStatusReport().getEnvelopeId()).isEqualTo("outer-report");
        }
    }

    @ParameterizedTest
    @MethodSource("bodyAlternatives")
    void absentAndEmptySelectedAlternativesSurviveMaterializedBuildAndCopy(EntryPoint point, String innerPlain,
            String innerHtml) {
        InternalEmailPopulatingBuilder outer = draft();
        outer.withPlainText("outer plain").withHTMLText("<p>outer</p>")
                .withContentEncodingConfig(ContentEncodingConfig.builder()
                        .withPlainTextContentTransferEncoding(ContentTransferEncoding.BASE_64)
                        .withHTMLTextContentTransferEncoding(ContentTransferEncoding.BASE_64).build());
        outer.withPlainText(innerPlain).withHTMLText(innerHtml)
                .withContentEncodingConfig(ContentEncodingConfig.builder().build());
        Email result = materialize(outer, point);
        assertThat(result.getPlainText()).isEqualTo(innerPlain);
        assertThat(result.getHTMLText()).isEqualTo(innerHtml);
        assertThat(result.getPlainTextContentTransferEncoding()).isNull();
        assertThat(result.getHTMLTextContentTransferEncoding()).isNull();
    }

    @ParameterizedTest
    @EnumSource(EntryPoint.class)
    void theSelectedCalendarAndItsEncodingRemainPairedThroughCopy(EntryPoint point) {
        InternalEmailPopulatingBuilder outer = draft();
        outer.withCalendarText(CalendarMethod.CANCEL, "outer calendar")
                .withContentEncodingConfig(ContentEncodingConfig.builder()
                        .withCalendarTextContentTransferEncoding(ContentTransferEncoding.QUOTED_PRINTABLE).build());
        outer.withPlainText("selected body");
        Email result = materialize(outer, point);
        assertThat(result.getCalendarText()).isEqualTo("outer calendar");
        assertThat(result.getCalendarMethod()).isEqualTo(CalendarMethod.CANCEL);
        assertThat(result.getCalendarTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.QUOTED_PRINTABLE);
    }

    @ParameterizedTest
    @EnumSource(EntryPoint.class)
    void innerReportFactsStayOnTheirOwnViewWhenTheOuterHasNoReport(EntryPoint point) {
        Email inner = draft().withDeliveryStatusReport(report("inner-report")).buildEmail();
        InternalEmailPopulatingBuilder parent = draft();
        parent.withAttachment(ParsedAttachmentResource.withEmail(resource("report.eml", "report"), inner));
        Email result = materialize(parent, point);
        assertThat(result.getDeliveryStatusReport()).isNull();
        assertThat(result.getAttachments().get(0).getEmail().getDeliveryStatusReport().getEnvelopeId()).isEqualTo("inner-report");
    }

    @ParameterizedTest
    @MethodSource("receiptChoices")
    void supportedReceiptStatesStayWithOuterIncludingDocumentedLegacyDisabled(EntryPoint point, ReceiptChoice choice) {
        InternalEmailPopulatingBuilder outer = draft();
        switch (choice) {
            case AUTOMATIC: outer.withDispositionNotificationTo().withReturnReceiptTo(); break;
            case EXPLICIT: outer.withDispositionNotificationTo("outer-mdn@example.org").withReturnReceiptTo("outer-receipt@example.org"); break;
            case CLEARED: outer.withDispositionNotificationTo("old-mdn@example.org").withReturnReceiptTo("old-receipt@example.org")
                    .clearDispositionNotificationTo().clearReturnReceiptTo(); break;
            case LEGACY_DISABLED: outer.withDispositionNotificationToState(false, null).withReturnReceiptToState(false, null); break;
            default: break;
        }
        Email expected = outer.buildEmail();
        Email inner = MAIL.emailBuilder().startingBlank().withDispositionNotificationTo("inner-mdn@example.org")
                .withReturnReceiptTo("inner-receipt@example.org").buildEmail();
        outer.withAttachment(ParsedAttachmentResource.withEmail(resource("child.eml", "child"), inner));
        Email result = materialize(outer, point);
        assertThat(result.getUseDispositionNotificationTo()).isEqualTo(expected.getUseDispositionNotificationTo());
        assertThat(result.getDispositionNotificationToRecipients()).isEqualTo(expected.getDispositionNotificationToRecipients());
        assertThat(result.getUseReturnReceiptTo()).isEqualTo(expected.getUseReturnReceiptTo());
        assertThat(result.getReturnReceiptToRecipients()).isEqualTo(expected.getReturnReceiptToRecipients());
    }

    @Test
    void ordinaryBodiesAndCalendarStayReadableWithoutAnAdditionalView() {
        Email outer = draft().withPlainText("outer").withHTMLText("<p>outer</p>")
                .withCalendarText(CalendarMethod.REQUEST, "outer calendar").buildEmail();
        assertThat(outer.getPlainText()).isEqualTo("outer");
        assertThat(outer.getHTMLText()).isEqualTo("<p>outer</p>");
        assertThat(outer.getCalendarText()).isEqualTo("outer calendar");
    }

    @ParameterizedTest
    @EnumSource(EntryPoint.class)
    void materializedBuildAndCopyNeverReadBorrowedResourceOrForwardedContent(EntryPoint point) throws Exception {
        AtomicInteger reads = new AtomicInteger();
        DataSource source = new DataSource() {
            public InputStream getInputStream() { reads.incrementAndGet(); throw new AssertionError("copy read borrowed data"); }
            public OutputStream getOutputStream() { throw new AssertionError("copy wrote borrowed data"); }
            public String getContentType() { return "text/plain; charset=UTF-8"; }
            public String getName() { return "borrowed"; }
        };
        AttachmentResource borrowed = AttachmentResource.builder().withDataSource(source).withName("borrowed").build();
        MimeMessage forwarded = new MimeMessage(Session.getInstance(new Properties())) {
            @Override public void writeTo(OutputStream output) { throw new AssertionError("copy serialized forwarded content"); }
            @Override public Object getContent() { throw new AssertionError("copy read forwarded content"); }
        };
        Email inner = MAIL.emailBuilder().startingBlank().withPlainText("inner").withAttachments(List.of(borrowed)).buildEmail();
        InternalEmailPopulatingBuilder outer = draft().withForward(forwarded);
        outer.withPlainText(inner.getPlainText()).withAttachments(List.of(borrowed, borrowed, borrowed));
        Email result = materialize(outer, point);
        for (int i = 0; i < 3; i++) {
            assertThat(result.getAttachments()).containsExactly(borrowed, borrowed, borrowed);
            assertThat(result.getEmailToForward()).isSameAs(forwarded);
            result = MAIL.emailBuilder().copying(result).buildEmail();
        }
        assertThat(reads).hasValue(0);
    }

    @Test
    void explicitSerializationMayCaptureBorrowedDataWithoutGivingThatIoToMerge() throws Exception {
        AtomicInteger opens = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();
        DataSource source = new DataSource() {
            public InputStream getInputStream() {
                opens.incrementAndGet();
                return new ByteArrayInputStream("snapshot".getBytes(UTF_8)) {
                    @Override public void close() throws IOException { closes.incrementAndGet(); super.close(); }
                };
            }
            public OutputStream getOutputStream() { throw new AssertionError("readonly"); }
            public String getContentType() { return "text/plain"; }
            public String getName() { return "snapshot"; }
        };
        AttachmentResource resource = AttachmentResource.builder().withDataSource(source).build();
        Email email = MAIL.emailBuilder().startingBlank().withAttachments(List.of(resource)).buildEmail();
        Email copy = MAIL.emailBuilder().copying(email).buildEmail();
        assertThat(opens).hasValue(0);
        Email restored = roundTrip(copy);
        assertThat(opens.get()).isPositive();
        assertThat(closes.get()).isEqualTo(opens.get());
        assertThat(restored.getAttachments().get(0).readAllData()).isEqualTo("snapshot");
    }

    private static void assertSelected(Email outer, Email inner, Email result, boolean restored) throws Exception {
        org.assertj.core.api.SoftAssertions checks = new org.assertj.core.api.SoftAssertions();
        for (String getter : OUTER_GETTERS) {
            checks.assertThat(Email.class.getMethod(getter).invoke(result)).as("outer owner: " + getter)
                    .isEqualTo(Email.class.getMethod(getter).invoke(outer));
        }
        checks.assertThat(result.getPlainText()).isEqualTo("inner plain");
        checks.assertThat(result.getHTMLText()).isEqualTo("<p>inner</p>");
        checks.assertThat(result.getPlainTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
        checks.assertThat(result.getHTMLTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.BASE_64);
        checks.assertThat(result.getCalendarText()).isEqualTo("outer calendar");
        checks.assertThat(result.getCalendarMethod()).isEqualTo(CalendarMethod.CANCEL);
        checks.assertThat(result.getCalendarTextContentTransferEncoding()).isEqualTo(ContentTransferEncoding.QUOTED_PRINTABLE);
        checks.assertThat(result.getHeaders()).containsEntry("X-Shared", List.of("inner-one", "inner-two"))
                .containsEntry("X-Outer", List.of("outer")).containsEntry("X-Inner", List.of("inner"));
        checks.assertThat(result.getHeaders().keySet().stream().filter(k -> k.equalsIgnoreCase("X-Shared")).findFirst().orElseThrow())
                .isEqualTo("X-Shared");
        checks.assertThat(result.getAttachments()).extracting(AttachmentResource::getName).containsExactly("outer-file", "outer-file", "inner-file");
        checks.assertThat(result.getEmbeddedImages()).extracting(AttachmentResource::getName).containsExactly("outer-file", "inner-file");

        checks.assertThat(result.getAttachments().get(2).getContentId()).isEqualTo("inner@example.org");
        checks.assertThat(result.getAttachments().get(2).getDescription()).isEqualTo("inner description");
        checks.assertThat(result.getAttachments().get(2).getDataSource().getContentType()).isEqualTo("text/plain; charset=UTF-8");
        checks.assertThat(result.getDeliveryStatusReport().getEnvelopeId()).isEqualTo("outer-report");
        checks.assertThat(result.getInspection().getOriginalMessage().readAllBytes()).containsExactly(originalBytes());
        checks.assertThat(result.getInspection().getSmime().getSignatures()).isEmpty();
        checks.assertThat(result.getInspection().getOpenPgp().getSignatures()).isEmpty();
        checks.assertThat(result.getEmailToForward().getSubject()).isEqualTo("outer forward");
        checks.assertAll();
    }

    private static class Fixture {
        final Scheme scheme;
        final AttachmentResource outerResource = resource("outer-file", "outer");
        final AttachmentResource innerResource = resource("inner-file", "inner");
        final Email inner;
        Fixture(Scheme scheme) throws Exception {
            this.scheme = scheme;
            InternalEmailPopulatingBuilder b = configured("inner", scheme == Scheme.OPENPGP ? Scheme.SMIME : Scheme.OPENPGP);
            b.withPlainText("inner plain").withHTMLText("<p>inner</p>").withHeaders(headers(false))
                    .withAttachments(List.of(innerResource)).withEmbeddedImages(List.of(innerResource))
                    .withContentEncodingConfig(ContentEncodingConfig.builder().withContentTransferEncoding(ContentTransferEncoding.BIT7)
                            .withPlainTextContentTransferEncoding(ContentTransferEncoding.BASE_64)
                            .withHTMLTextContentTransferEncoding(ContentTransferEncoding.BASE_64).build());
            b.withDeliveryStatusReport(report("inner-report"));
            inner = b.buildEmail();
        }
        InternalEmailPopulatingBuilder outer() throws Exception {
            InternalEmailPopulatingBuilder b = configured("outer", scheme);
            b.withPlainText("inner plain").withHTMLText("<p>inner</p>").withCalendarText(CalendarMethod.CANCEL, "outer calendar")
                    .withHeaders(headers(true)).withHeader("X-Shared", "inner-one", true).withHeader("X-Shared", "inner-two")
                    .withHeader("X-Inner", "inner").withAttachments(List.of(outerResource, outerResource, innerResource)).withEmbeddedImages(List.of(outerResource, innerResource))
                    .withContentEncodingConfig(ContentEncodingConfig.builder().withContentTransferEncoding(ContentTransferEncoding.QUOTED_PRINTABLE)
                            .withPlainTextContentTransferEncoding(ContentTransferEncoding.BASE_64)
                            .withHTMLTextContentTransferEncoding(ContentTransferEncoding.BASE_64)
                            .withCalendarTextContentTransferEncoding(ContentTransferEncoding.QUOTED_PRINTABLE).build());
            b.withDeliveryStatusReport(report("outer-report"));
            final AttachmentResource original = AttachmentResource.builder().withName("source.eml").withData(originalBytes(), "message/rfc822").build();
            b.retainSelectionInspection(ReceivedEmailInspection.builder().plainText(ValueInspectionImpl.builder()
                    .origin(ValueOrigin.RECEIVED).availability(ValueAvailability.PRESENT).build())
                    .attachments(Collections.nCopies(3, ValueInspectionImpl.unknown())).embeddedImages(Collections.nCopies(2, ValueInspectionImpl.unknown()))
                    .originalAttachments(List.of(outerResource, outerResource, innerResource)).originalMessage(original).build());
            return b;
        }
        Email materialize(EntryPoint point) throws Exception { return RetainedContentMergeContractTest.materialize(outer(), point); }
    }

    private static InternalEmailPopulatingBuilder configured(String label, Scheme scheme) throws Exception {
        boolean outer = "outer".equals(label);
        InternalEmailPopulatingBuilder b = draft();
        b.fixingMessageId("<" + label + "@example.org>").from(label + "-from@example.org")
                .to(label + "-to@example.org").cc(label + "-cc@example.org").bcc(label + "-bcc@example.org")
                .withReplyTo(label + "-reply@example.org").withBounceTo(label + "-bounce@example.org")
                .withSubject(label + " subject").fixingSentDate(new Date(outer ? 1_000_000 : 2_000_000))
                .withOverrideReceivers(MAIL.recipientBuilder().withAddress(label + "-override@example.org").build())
                .withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults(outer).ignoringOverrides(!outer)
                        .dontApplyDefaultValueFor(outer ? EmailProperty.SUBJECT : EmailProperty.CALENDAR_METHOD)
                        .dontApplyOverrideValueFor(outer ? EmailProperty.CALENDAR_TEXT : EmailProperty.SUBJECT).build())
                .withSmtpDeliveryOptions(SmtpDeliveryOptions.builder()
                        .withDeliveryStatusNotification(outer ? DeliveryStatusNotification.NotifyOption.FAILURE : DeliveryStatusNotification.NotifyOption.SUCCESS)
                        .withDeliveryStatusNotificationReturnOption(outer ? DeliveryStatusNotification.ReturnOption.HEADERS_ONLY : DeliveryStatusNotification.ReturnOption.FULL_MESSAGE)
                        .fixingEnvelopeId(label + "-envid").withSendingToAcceptedRecipients(!outer).build())
                .signWithDomainKey(DkimConfig.builder().dkimPrivateKeyData(new byte[]{1}).dkimSigningDomain(label + ".example.org").dkimSelector(label).build());
        if (!outer) b.withSmtpDeliveryOptions(b.getSmtpDeliveryOptions().toBuilder().withTlsRequiredForOnwardDelivery().build());
        b.withDispositionNotificationTo(label + "-mdn@example.org");
        b.withReturnReceiptTo(label + "-receipt@example.org");
        final Properties properties = new Properties();
        properties.setProperty("mail.host", "localhost");
        MimeMessage forwarded = new MimeMessage(Session.getInstance(properties));
        forwarded.setSubject(label + " forward"); forwarded.setText(label + " forward body"); forwarded.saveChanges();
        b.withForward(forwarded);
        if (scheme == Scheme.SMIME) {
            Pkcs12Config keys = loadPkcs12KeyStore();
            KeyStore store = KeyStore.getInstance("PKCS12");
            store.load(new ByteArrayInputStream(keys.getPkcs12StoreData()), keys.getStorePassword());
            b.signWithSmime(SmimeSigningConfig.builder().pkcs12Config(keys).build())
                    .encryptWithSmime(SmimeEncryptionConfig.builder().x509Certificate((X509Certificate) store.getCertificate(keys.getKeyAlias())).build());
        } else if (scheme == Scheme.OPENPGP) {
            b.signWithOpenPgp(OpenPgpSigningConfig.builder().secretKeyRing(new byte[]{1}).passphrase(label).build())
                    .encryptWithOpenPgp(OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(new byte[]{2}).build());
        }
        return b;
    }

    private static byte[] originalBytes() {
        return "Content-Type: text/plain\r\n\r\nHistorical selected input\r\n".getBytes(UTF_8);
    }

    private static DeliveryStatusReport report(String id) {
        String eml = "From: report@example.org\r\nMIME-Version: 1.0\r\nContent-Type: multipart/report; report-type=delivery-status; boundary=r\r\n\r\n"
                + "--r\r\nContent-Type: text/plain\r\n\r\nReport\r\n--r\r\nContent-Type: message/delivery-status\r\n\r\n"
                + "Reporting-MTA: dns; mx.example.org\r\nOriginal-Envelope-Id: " + id + "\r\n\r\n"
                + "Final-Recipient: rfc822; recipient@example.org\r\nAction: failed\r\nStatus: 5.1.1\r\n\r\n--r--\r\n";
        DeliveryStatusReport report = MAIL.converter().fromEml(eml).toEmail().getDeliveryStatusReport();
        if (report == null) throw new AssertionError("Invalid report fixture");
        return report;
    }

    private static Map<String, List<String>> headers(boolean outer) {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put(outer ? "X-Shared" : "x-shared", outer ? List.of("outer-one", "outer-two") : List.of("inner-one", "inner-two"));
        values.put(outer ? "X-Outer" : "X-Inner", List.of(outer ? "outer" : "inner"));
        return values;
    }
    private static Recipient recipient(String address) { return MAIL.recipientBuilder().withAddress(address).build(); }
    private static AttachmentResource resource(String name, String label) {
        return AttachmentResource.builder().withName(name).withData(label.getBytes(UTF_8), "text/plain; charset=UTF-8")
                .withContentId(label + "@example.org").withDescription(label + " description")
                .withContentTransferEncoding(ContentTransferEncoding.BASE_64).build();
    }
    private static InternalEmailPopulatingBuilder draft() { return (InternalEmailPopulatingBuilder) MAIL.emailBuilder().startingBlank(); }
    private static Email materialize(InternalEmailPopulatingBuilder outer, EntryPoint point) {
        if (point == EntryPoint.MATERIALIZED_BUILD) return outer.buildEmail();
        Email separate = outer.buildEmail();
        return MAIL.emailBuilder().copying(separate).buildEmail();
    }
    private static Stream<Arguments> entryPointsAndSchemes() {
        return Arrays.stream(EntryPoint.values()).flatMap(p -> Arrays.stream(Scheme.values()).map(s -> Arguments.of(p, s)));
    }
    private static Stream<Arguments> bodyAlternatives() {
        return Arrays.stream(EntryPoint.values()).flatMap(p -> Stream.of(Arguments.of(p, null, null), Arguments.of(p, "", null),
                Arguments.of(p, null, ""), Arguments.of(p, "", "")));
    }
    private static Stream<Arguments> receiptChoices() {
        return Arrays.stream(EntryPoint.values()).flatMap(p -> Arrays.stream(ReceiptChoice.values()).map(c -> Arguments.of(p, c)));
    }
    private static void role(Map<String, String> map, String role, String... names) {
        for (String name : names) if (map.put(name, role) != null) throw new AssertionError("Duplicate inventory field " + name);
    }
    private static Email roundTrip(Email email) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(bytes)) { out.writeObject(email); }
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { return (Email) in.readObject(); }
    }
}
