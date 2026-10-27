package org.simplejavamail.mailer.internal;

import jakarta.activation.DataSource;
import jakarta.mail.Message.RecipientType;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.IRecipientsBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.ContentEncodingConfig;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.email.config.OpenPgpEncryptionConfig;
import org.simplejavamail.api.email.config.OpenPgpSigningConfig;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.email.config.SmtpDeliveryOptions;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.mailer.internal.ConfigurationAdoptionCatalogue.Owner;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.mailer.internal.ConfigurationAdoptionCatalogue.assertApiCategory;
import static org.simplejavamail.mailer.internal.ConfigurationAdoptionCatalogue.assertValueBuilderCategory;
import static org.simplejavamail.mailer.internal.ConfigurationAdoptionCatalogue.Owner.*;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.SUCCESS;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

/** Public-entry contract tables; expected selection and merging never call production resolution helpers. */
class LayeredEmailConfigurationContractTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    enum Scalar {
        SUBJECT(EmailProperty.SUBJECT, EMAIL_TEMPLATE, null, "withSubject", "clearSubject"),
        PLAIN(EmailProperty.BODY_TEXT, EMAIL_TEMPLATE, null, "withPlainText", "appendPlainText", "prependPlainText", "clearPlainText"),
        HTML(EmailProperty.BODY_HTML, EMAIL_TEMPLATE, null, "withHTMLText", "appendHTMLText", "prependHTMLText", "clearHTMLText"),
        FROM(EmailProperty.FROM_RECIPIENT, EMAIL_TEMPLATE, null, "from", "clearFromRecipient"),
        BOUNCE(EmailProperty.BOUNCETO_RECIPIENT, EMAIL_TEMPLATE, null, "withBounceTo", "clearBounceTo"),
        MESSAGE_ID(EmailProperty.ID, EMAIL_TEMPLATE, null, "fixingMessageId", "clearId"),
        SENT_DATE(EmailProperty.SENT_DATE, EMAIL_TEMPLATE, null, "fixingSentDate", "clearSentDate"),
        ENCODING(EmailProperty.CONTENT_TRANSFER_ENCODING, EMAIL_ENCODING, ContentEncodingConfig.Builder.class,
                "withContentEncodingConfig", "useConfiguredContentEncodingConfig"),
        PLAIN_ENCODING(EmailProperty.PLAIN_TEXT_CONTENT_TRANSFER_ENCODING, EMAIL_ENCODING, ContentEncodingConfig.Builder.class,
                "withContentEncodingConfig", "useConfiguredContentEncodingConfig"),
        HTML_ENCODING(EmailProperty.HTML_TEXT_CONTENT_TRANSFER_ENCODING, EMAIL_ENCODING, ContentEncodingConfig.Builder.class,
                "withContentEncodingConfig", "useConfiguredContentEncodingConfig"),
        CALENDAR_ENCODING(EmailProperty.CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING, EMAIL_ENCODING, ContentEncodingConfig.Builder.class,
                "withContentEncodingConfig", "useConfiguredContentEncodingConfig"),
        SEND_PARTIAL(EmailProperty.SENDING_TO_ACCEPTED_RECIPIENTS, EMAIL_DELIVERY, SmtpDeliveryOptions.Builder.class,
                "withSmtpDeliveryOptions", "useConfiguredSmtpDeliveryOptions"),
        DSN(EmailProperty.DELIVERY_STATUS_NOTIFICATION, EMAIL_DELIVERY, DeliveryStatusNotification.DeliveryStatusNotificationBuilder.class,
                "withSmtpDeliveryOptions", "useConfiguredSmtpDeliveryOptions"),
        DKIM(EmailProperty.DKIM_SIGNING_CONFIG, EMAIL_ATOMIC_POLICY, DkimConfig.DkimConfigBuilder.class,
                "signWithDomainKey", "clearDkim"),
        SMIME_SIGNING(EmailProperty.SMIME_SIGNING_CONFIG, EMAIL_ATOMIC_POLICY, SmimeSigningConfig.SmimeSigningConfigBuilder.class,
                "signWithSmime", "clearSmimeSigning", "clearSmime"),
        SMIME_ENCRYPTION(EmailProperty.SMIME_ENCRYPTION_CONFIG, EMAIL_ATOMIC_POLICY, SmimeEncryptionConfig.SmimeEncryptionConfigBuilder.class,
                "encryptWithSmime", "clearSmimeEncryption", "clearSmime"),
        OPENPGP_SIGNING(EmailProperty.OPENPGP_SIGNING_CONFIG, EMAIL_ATOMIC_POLICY, OpenPgpSigningConfig.OpenPgpSigningConfigBuilder.class,
                "signWithOpenPgp", "clearOpenPgpSigning", "clearOpenPgp"),
        OPENPGP_ENCRYPTION(EmailProperty.OPENPGP_ENCRYPTION_CONFIG, EMAIL_ATOMIC_POLICY, OpenPgpEncryptionConfig.OpenPgpEncryptionConfigBuilder.class,
                "encryptWithOpenPgp", "clearOpenPgpEncryption", "clearOpenPgp");

        final EmailProperty property;
        final Owner category;
        final Class<?> valueBuilder;
        final String[] methods;

        Scalar(final EmailProperty property, final Owner category, final Class<?> valueBuilder, final String... methods) {
            this.property = property;
            this.category = category;
            this.valueBuilder = valueBuilder;
            this.methods = methods;
        }

        void verifyCategoryBinding() {
            assertApiCategory(category, EmailPopulatingBuilder.class, methods);
            if (valueBuilder != null) { assertValueBuilderCategory(category, valueBuilder); }
        }

        Object value(final int marker) {
            switch (this) {
                case FROM: case BOUNCE: return new Recipient("Person " + marker, "person" + marker + "@example.test", null, null);
                case SENT_DATE: return new Date(marker * 1000L);
                case ENCODING: case PLAIN_ENCODING: case HTML_ENCODING: case CALENDAR_ENCODING:
                    return marker == 1 ? ContentTransferEncoding.BIT7 : ContentTransferEncoding.BASE_64;
                case SEND_PARTIAL: return marker == 1;
                case DSN: return DeliveryStatusNotification.builder().notifyOptions(marker == 1 ? FAILURE : SUCCESS)
                        .envelopeId("job-" + marker).build();
                case DKIM: return DkimConfig.builder().dkimPrivateKeyData("key-" + marker).dkimSigningDomain("example.test").dkimSelector("selector-" + marker).build();
                case SMIME_SIGNING: return mock(SmimeSigningConfig.class);
                case SMIME_ENCRYPTION: return SmimeEncryptionConfig.builder().x509Certificate(mock(X509Certificate.class)).build();
                case OPENPGP_SIGNING: return OpenPgpSigningConfig.builder().secretKeyRing(new byte[] {(byte) marker}).build();
                case OPENPGP_ENCRYPTION: return OpenPgpEncryptionConfig.builder().addRecipientPublicKeyRing(new byte[] {(byte) marker}).build();
                default: return marker == 1 ? "alpha" : "beta";
            }
        }

        void set(final EmailPopulatingBuilder builder, final Object value) {
            if (value == null) { return; }
            switch (this) {
                case SUBJECT: builder.withSubject((String) value); break;
                case PLAIN: builder.withPlainText((String) value); break;
                case HTML: builder.withHTMLText((String) value); break;
                case FROM: builder.from((Recipient) value); break;
                case BOUNCE: builder.withBounceTo((Recipient) value); break;
                case MESSAGE_ID: builder.fixingMessageId((String) value); break;
                case SENT_DATE: builder.fixingSentDate((Date) value); break;
                case ENCODING: builder.withContentEncodingConfig(ContentEncodingConfig.builder().withContentTransferEncoding((ContentTransferEncoding) value).build()); break;
                case PLAIN_ENCODING: builder.withContentEncodingConfig(ContentEncodingConfig.builder().withPlainTextContentTransferEncoding((ContentTransferEncoding) value).build()); break;
                case HTML_ENCODING: builder.withContentEncodingConfig(ContentEncodingConfig.builder().withHTMLTextContentTransferEncoding((ContentTransferEncoding) value).build()); break;
                case CALENDAR_ENCODING: builder.withContentEncodingConfig(ContentEncodingConfig.builder().withCalendarTextContentTransferEncoding((ContentTransferEncoding) value).build()); break;
                case SEND_PARTIAL: builder.withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withSendingToAcceptedRecipients((Boolean) value).build()); break;
                case DSN: builder.withSmtpDeliveryOptions(SmtpDeliveryOptions.builder().withDeliveryStatusNotification((DeliveryStatusNotification) value).build()); break;
                case DKIM: builder.signWithDomainKey((DkimConfig) value); break;
                case SMIME_SIGNING: builder.signWithSmime((SmimeSigningConfig) value); break;
                case SMIME_ENCRYPTION: builder.encryptWithSmime((SmimeEncryptionConfig) value); break;
                case OPENPGP_SIGNING: builder.signWithOpenPgp((OpenPgpSigningConfig) value); break;
                case OPENPGP_ENCRYPTION: builder.encryptWithOpenPgp((OpenPgpEncryptionConfig) value); break;
                default: throw new AssertionError(this);
            }
        }

        Object get(final Email email) {
            switch (this) {
                case SUBJECT: return email.getSubject();
                case PLAIN: return email.getPlainText();
                case HTML: return email.getHTMLText();
                case FROM: return email.getFromRecipient();
                case BOUNCE: return email.getBounceToRecipient();
                case MESSAGE_ID: return email.getId();
                case SENT_DATE: return email.getSentDate();
                case ENCODING: return email.getContentTransferEncoding();
                case PLAIN_ENCODING: return email.getPlainTextContentTransferEncoding();
                case HTML_ENCODING: return email.getHTMLTextContentTransferEncoding();
                case CALENDAR_ENCODING: return email.getCalendarTextContentTransferEncoding();
                case SEND_PARTIAL: return email.getSendingToAcceptedRecipients();
                case DSN: return email.getDeliveryStatusNotification();
                case DKIM: return email.getDkimConfig();
                case SMIME_SIGNING: return email.getSmimeSigningConfig();
                case SMIME_ENCRYPTION: return email.getSmimeEncryptionConfig();
                case OPENPGP_SIGNING: return email.getOpenPgpSigningConfig();
                case OPENPGP_ENCRYPTION: return email.getOpenPgpEncryptionConfig();
                default: throw new AssertionError(this);
            }
        }
    }

    enum ScalarCase {
        DEFAULT(1,0,0,0), SUBMITTED(1,2,0,0), OVERRIDE(1,1,2,0), FIELD_OVERRIDE_SUPPRESSED(1,1,2,8),
        ABSENT(0,0,0,0), GLOBAL_DEFAULT_SUPPRESSED(1,0,0,1), FIELD_DEFAULT_SUPPRESSED(1,0,0,4),
        GLOBAL_OVERRIDE_SUPPRESSED(1,1,2,2), BOTH_IGNORED(1,0,2,3), OVERRIDE_WITHOUT_SUBMITTED(1,0,2,0), ALL_EQUIVALENT(1,1,1,0);
        final int defaults;
        final int submitted;
        final int overrides;
        final int flags;
        ScalarCase(final int defaults, final int submitted, final int overrides, final int flags) {
            this.defaults = defaults; this.submitted = submitted; this.overrides = overrides; this.flags = flags;
        }
    }

    @Test
    void scenarioCataloguesMakeEligibilityAndGroupPolicyCategoriesExplicit() {
        final Set<Integer> eligibility = new LinkedHashSet<>();
        for (ScalarCase scenario : ScalarCase.values()) { eligibility.add(scenario.flags); }
        assertThat(eligibility).containsExactlyInAnyOrder(0, 1, 2, 3, 4, 8);
        final Set<Integer> certificatePolicies = new LinkedHashSet<>();
        final Set<Integer> notifyPolicies = new LinkedHashSet<>();
        for (RecipientCase scenario : RecipientCase.values()) {
            certificatePolicies.add(scenario.certificatePolicy);
            notifyPolicies.add(scenario.notifyPolicy);
        }
        assertThat(certificatePolicies).containsExactlyInAnyOrder(0, 1, 2, 3);
        assertThat(notifyPolicies).containsExactlyInAnyOrder(0, 1, 2, 3);
    }

    @Test
    void everyScalarFamilyMapsToTheSameSelectionAndSuppressionRules() throws Exception {
        // Four mapping cases per field are sufficient once source authority and eligibility are covered as rules.
        for (Scalar field : Scalar.values()) {
            field.verifyCategoryBinding();
            for (ScalarCase scenario : new ScalarCase[] {ScalarCase.DEFAULT, ScalarCase.SUBMITTED, ScalarCase.OVERRIDE, ScalarCase.FIELD_OVERRIDE_SUPPRESSED}) {
                verifyScalar(field, scenario);
            }
        }
    }

    @Test
    void scalarEligibilityRulesAreCoveredOnceThroughARepresentativePublicField() throws Exception {
        assertValueBuilderCategory(EMAIL_TEMPLATE_OPTIONS, EmailTemplateOptions.Builder.class);
        assertApiCategory(EMAIL_TEMPLATE_OPTIONS, EmailPopulatingBuilder.class, "withTemplateOptions", "useConfiguredTemplateOptions");
        for (ScalarCase scenario : ScalarCase.values()) { verifyScalar(Scalar.PLAIN, scenario); }
    }

    private void verifyScalar(final Scalar field, final ScalarCase scenario) throws Exception {
        final Object[] values = {null, field.value(1), field.value(2)};
        final Object defaults = values[scenario.defaults];
        final Object submitted = values[scenario.submitted];
        final Object overrides = values[scenario.overrides];
        try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true)
                .withEmailDefaults(template(field, defaults)).withEmailOverrides(template(field, overrides)).buildMailer()) {
            final EmailPopulatingBuilder draft = mail.emailBuilder().startingBlank().withTemplateOptions(options(field.property, scenario.flags));
            field.set(draft, submitted);
            final Email raw = draft.buildEmail();
            final Object expected = choose(defaults, submitted, overrides, scenario.flags);
            final Email completed = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(raw);
            assertThat(field.get(completed)).as("%s %s", field, scenario).isEqualTo(expected);
            assertThat(field.get(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(completed))).isEqualTo(expected);
            assertThat(field.get(mail.emailBuilder().copying(completed).buildEmail())).isEqualTo(expected);
            assertThat(field.get(raw)).as("Submitted Email stays unchanged").isEqualTo(submitted);
            record("field", "email." + field.property.name());
            record("rule", "email.scalar-precedence");
            record("rule", "email.repeated-completion");
            if (scenario.flags != 0) { record("rule", "email.suppression"); }
            if (field.category == EMAIL_ATOMIC_POLICY) { record("rule", "email.atomic-policy"); }
        }
    }

    @Test
    void emptyTextRemainsAnExplicitScalarChoice() throws Exception {
        for (Scalar field : new Scalar[] {Scalar.SUBJECT, Scalar.PLAIN, Scalar.HTML}) {
            try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true).withEmailDefaults(template(field, "default"))
                    .withEmailOverrides(template(field, "")).buildMailer()) {
                assertThat(field.get(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(template(field, "submitted")))).isEqualTo("");
                final EmailPopulatingBuilder draft = mail.emailBuilder().startingBlank().withTemplateOptions(options(field.property, 2));
                field.set(draft, "");
                assertThat(field.get(draft.buildEmailCompletedWithDefaultsAndOverrides(mailer.getEmailGovernance()))).isEqualTo("");
            }
        }
    }

    enum CollectionField {
        TO(EmailProperty.TO_RECIPIENTS, EMAIL_RECIPIENT_COLLECTION, "to", "withRecipients", "clearRecipients"),
        CC(EmailProperty.CC_RECIPIENTS, EMAIL_RECIPIENT_COLLECTION, "cc", "withRecipients", "clearRecipients"),
        BCC(EmailProperty.BCC_RECIPIENTS, EMAIL_RECIPIENT_COLLECTION, "bcc", "withRecipients", "clearRecipients"),
        REPLY_TO(EmailProperty.REPLYTO_RECIPIENT, EMAIL_RECIPIENT_COLLECTION, "withReplyTo", "clearReplyTo"),
        ENVELOPE(EmailProperty.OVERRIDE_RECEIVERS, EMAIL_RECIPIENT_COLLECTION, "withOverrideReceivers", "clearOverrideReceivers"),
        ATTACHMENTS(EmailProperty.ATTACHMENTS, EMAIL_RESOURCE_COLLECTION,
                "withAttachment", "withAttachments", "withPreEncodedAttachment", "clearAttachments"),
        IMAGES(EmailProperty.EMBEDDED_IMAGES, EMAIL_RESOURCE_COLLECTION,
                "withEmbeddedImage", "withEmbeddedImages", "withPreEncodedEmbeddedImage", "clearEmbeddedImages");
        final EmailProperty property;
        final Owner category;
        final String[] methods;
        CollectionField(final EmailProperty property, final Owner category, final String... methods) {
            this.property = property; this.category = category; this.methods = methods;
        }
        boolean visibleRecipients() { return this == TO || this == CC || this == BCC; }
    }

    enum CollectionCase {
        DEFAULT("A A B", "", "", 0), SUBMITTED_DUPLICATES("", "A A B", "", 0),
        TEMPLATE_ADDITIONS("A A B", "A A B", "B C C", 0), FIELD_OVERRIDE_SUPPRESSED("A", "A A B", "B C C", 8),
        FIELD_DEFAULT_SUPPRESSED("A A B", "", "", 4), GLOBAL_DEFAULT_SUPPRESSED("A", "", "", 1),
        GLOBAL_OVERRIDE_SUPPRESSED("A", "A A B", "B C C", 2), BOTH_IGNORED("A", "A A B", "B C C", 3);
        final List<String> defaults;
        final List<String> submitted;
        final List<String> overrides;
        final int flags;
        CollectionCase(final String defaults, final String submitted, final String overrides, final int flags) {
            this.defaults = tokens(defaults); this.submitted = tokens(submitted); this.overrides = tokens(overrides); this.flags = flags;
        }
    }

    @Test
    void additiveRulesReachEveryRecipientAndResourceAdapter() throws Exception {
        final AtomicInteger reads = new AtomicInteger();
        final Map<String, AttachmentResource> resources = Map.of("A", resource("A", reads), "B", resource("B", reads), "C", resource("C", reads));
        for (CollectionField field : CollectionField.values()) {
            assertApiCategory(field.category, EmailPopulatingBuilder.class, field.methods);
            for (CollectionCase scenario : new CollectionCase[] {CollectionCase.DEFAULT, CollectionCase.SUBMITTED_DUPLICATES,
                    CollectionCase.TEMPLATE_ADDITIONS, CollectionCase.FIELD_OVERRIDE_SUPPRESSED}) {
                verifyCollection(field, scenario, resources, false);
            }
            if (field.visibleRecipients()) { verifyCollection(field, CollectionCase.FIELD_OVERRIDE_SUPPRESSED, resources, true); }
        }
        for (CollectionCase scenario : CollectionCase.values()) { verifyCollection(CollectionField.TO, scenario, resources, false); }
        // ALL_RECIPIENTS belongs to visible recipient roles, not Reply-To or envelope/resource collections.
        verifyCollection(CollectionField.REPLY_TO, CollectionCase.FIELD_OVERRIDE_SUPPRESSED, resources, true);
        assertThat(reads).hasValue(0);
    }

    private void verifyCollection(final CollectionField field, final CollectionCase scenario,
            final Map<String, AttachmentResource> resources, final boolean allRecipients) throws Exception {
        try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true)
                .withEmailDefaults(collectionTemplate(field, scenario.defaults, resources, 0, false))
                .withEmailOverrides(collectionTemplate(field, scenario.overrides, resources, 0, false)).buildMailer()) {
            final Email provided = collectionTemplate(field, scenario.submitted, resources, scenario.flags, allRecipients);
            final int effectiveFlags = allRecipients && !field.visibleRecipients() ? scenario.flags & ~12 : scenario.flags;
            final List<String> expected = merge(scenario.defaults, scenario.submitted, scenario.overrides, effectiveFlags);
            final Email completed = mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(provided);
            assertThat(collectionValues(field, completed)).as("%s %s all=%s", field, scenario, allRecipients).containsExactlyElementsOf(expected);
            assertThat(collectionValues(field, mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(completed))).containsExactlyElementsOf(expected);
            assertThat(collectionValues(field, provided)).containsExactlyElementsOf(scenario.submitted);
            record("field", "email." + field.property.name());
            if (allRecipients) { record("field", "email.ALL_RECIPIENTS"); }
            record("rule", "email.collection-additions");
            record("rule", "email.repeated-completion");
        }
    }

    enum HeaderCase {
        DEFAULT("one", "", "", 0), SUBMITTED("one", "two three", "", 0),
        OVERRIDE("one", "two three", "four", 0), OVERRIDE_SUPPRESSED("one", "two three", "four", 8),
        DEFAULT_SUPPRESSED("one", "", "", 4), GLOBAL_SUPPRESSION("one", "", "four", 3),
        INPUT_DUPLICATES("one one two", "", "", 0), ABSENT("", "", "", 0);
        final List<String> defaults;
        final List<String> submitted;
        final List<String> overrides;
        final int flags;
        HeaderCase(final String defaults, final String submitted, final String overrides, final int flags) {
            this.defaults = tokens(defaults); this.submitted = tokens(submitted); this.overrides = tokens(overrides); this.flags = flags;
        }
    }

    @Test
    void headersResolveCaseInsensitivelyAndReplaceTheWholeValueList() throws Exception {
        assertApiCategory(EMAIL_HEADER_COLLECTION, EmailPopulatingBuilder.class, "withHeader", "withHeaders", "clearHeaders");
        for (HeaderCase scenario : HeaderCase.values()) {
            try (Mailer mailer = mail.mailerBuilder().withTransportModeLoggingOnly(true)
                    .withEmailDefaults(mail.emailBuilder().startingBlank().withHeaders(Map.of("X-Layer", scenario.defaults)).withHeader("X-Default", "d").buildEmail())
                    .withEmailOverrides(mail.emailBuilder().startingBlank().withHeaders(Map.of("x-layer", scenario.overrides)).withHeader("X-Override", "o").buildEmail()).buildMailer()) {
                final Email input = mail.emailBuilder().startingBlank().withHeaders(Map.of("X-LAYER", scenario.submitted))
                        .withTemplateOptions(options(EmailProperty.HEADERS, scenario.flags)).buildEmail();
                final Map<String, Collection<String>> actual = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                actual.putAll(mailer.getEmailGovernance().produceEmailApplyingDefaultsAndOverrides(input).getHeaders());
                final List<String> selected = !scenario.overrides.isEmpty() && overridesAllowed(scenario.flags) ? scenario.overrides
                        : !scenario.submitted.isEmpty() ? scenario.submitted : defaultsAllowed(scenario.flags) ? scenario.defaults : List.of();
                // Public header setters suppress repeated equal values before template resolution starts.
                final List<String> expected = new ArrayList<>(new LinkedHashSet<>(selected));
                if (expected.isEmpty()) { assertThat(actual).doesNotContainKey("X-Layer"); }
                else { assertThat(actual.get("X-Layer")).containsExactlyElementsOf(expected); }
                assertThat(actual.containsKey("X-Default")).isEqualTo(defaultsAllowed(scenario.flags));
                assertThat(actual.containsKey("X-Override")).isEqualTo(overridesAllowed(scenario.flags));
                record("field", "email.HEADERS");
                record("rule", "email.headers");
            }
        }
    }

    enum RecipientCase {
        ABSENT(false,false,0,0), UNCHANGED(true,true,0,0), DEFAULT_ABSENT(false,false,1,1),
        DEFAULT_PRESENT(true,true,1,1), FIXED(true,true,2,2), CLEAR(true,true,3,3),
        INDEPENDENT_FIXED_CERTIFICATE(true,true,2,1), INDEPENDENT_NOTIFY_CLEAR(false,true,1,3),
        INDEPENDENT_CERTIFICATE_CLEAR(true,false,3,2), NOTIFY_ONLY(false,true,0,1), CERTIFICATE_ONLY(true,false,1,0);
        final boolean certificate;
        final boolean notify;
        final int certificatePolicy;
        final int notifyPolicy;
        RecipientCase(final boolean certificate, final boolean notify, final int certificatePolicy, final int notifyPolicy) {
            this.certificate = certificate; this.notify = notify; this.certificatePolicy = certificatePolicy; this.notifyPolicy = notifyPolicy;
        }
    }

    @Test
    void recipientGroupDefaultFixedClearAndRestorationRemainIndependent() {
        assertValueBuilderCategory(RECIPIENT_GROUP_POLICY, IRecipientsBuilder.class);
        final X509Certificate original = mock(X509Certificate.class);
        final X509Certificate configured = mock(X509Certificate.class);
        for (RecipientCase scenario : RecipientCase.values()) {
            final boolean hasCertificate = scenario.certificate;
            final boolean hasNotify = scenario.notify;
            final int certificatePolicy = scenario.certificatePolicy;
            final int notifyPolicy = scenario.notifyPolicy;
            final Recipient input = new Recipient("Original", "person@example.test", RecipientType.TO,
                    hasCertificate ? original : null, hasNotify ? List.of(FAILURE) : List.of());
            final IRecipientsBuilder group = mail.recipientsBuilder().withRecipients(input, input);
            if (certificatePolicy == 1) { group.withDefaultSmimeCertificate(configured); }
            if (certificatePolicy == 2) { group.withFixedSmimeCertificate(configured); }
            if (certificatePolicy == 3) { group.clearingSmimeCertificates(); }
            if (notifyPolicy == 1) { group.withDefaultDeliveryStatusNotificationNotifyOptions(SUCCESS); }
            if (notifyPolicy == 2) { group.withFixedDeliveryStatusNotificationNotifyOptions(SUCCESS); }
            if (notifyPolicy == 3) { group.clearingDeliveryStatusNotificationNotifyOptions(); }
            final X509Certificate expectedCertificate = certificatePolicy == 3 ? null : certificatePolicy == 2
                    || certificatePolicy == 1 && !hasCertificate ? configured : hasCertificate ? original : null;
            final List<DeliveryStatusNotification.NotifyOption> expectedNotify = notifyPolicy == 3 ? List.of()
                    : notifyPolicy == 2 || notifyPolicy == 1 && !hasNotify ? List.of(SUCCESS) : hasNotify ? List.of(FAILURE) : List.of();
            assertThat(group.buildRecipients()).hasSize(2).allSatisfy(recipient -> {
                assertThat(recipient.getSmimeCertificate()).isSameAs(expectedCertificate);
                assertThat(recipient.getDeliveryStatusNotificationNotifyOptions()).containsExactlyElementsOf(expectedNotify);
            });
            assertThat(group.resetSmimeCertificatePolicy().resetDeliveryStatusNotificationNotifyPolicy().buildRecipients()).containsExactly(input, input);
            record("field", "recipient.certificate");
            record("field", "recipient.notify");
            record("rule", "recipient.policy");
        }
    }

    private static List<String> tokens(final String value) {
        return value.isEmpty() ? List.of() : List.of(value.split(" "));
    }

    private Email template(final Scalar field, final Object value) {
        final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank();
        field.set(builder, value);
        return builder.buildEmail();
    }

    private static Object choose(final Object defaults, final Object submitted, final Object overrides, final int flags) {
        return overridesAllowed(flags) && overrides != null ? overrides : submitted != null ? submitted : defaultsAllowed(flags) ? defaults : null;
    }

    private static EmailTemplateOptions options(final EmailProperty field, final int flags) {
        final EmailTemplateOptions.Builder builder = EmailTemplateOptions.builder().ignoringDefaults((flags & 1) != 0).ignoringOverrides((flags & 2) != 0);
        if ((flags & 4) != 0) { builder.dontApplyDefaultValueFor(field); }
        if ((flags & 8) != 0) { builder.dontApplyOverrideValueFor(field); }
        return builder.build();
    }

    private static boolean defaultsAllowed(final int flags) { return (flags & 5) == 0; }
    private static boolean overridesAllowed(final int flags) { return (flags & 10) == 0; }

    private static List<String> merge(final List<String> defaults, final List<String> submitted, final List<String> overrides, final int flags) {
        final List<String> result = new ArrayList<>();
        if (overridesAllowed(flags)) {
            for (String value : overrides) { if (!submitted.contains(value) && !result.contains(value)) { result.add(value); } }
        }
        result.addAll(submitted);
        if (defaultsAllowed(flags)) {
            for (String value : defaults) { if (!result.contains(value)) { result.add(value); } }
        }
        return result;
    }

    private Email collectionTemplate(final CollectionField field, final List<String> values, final Map<String, AttachmentResource> resources,
            final int flags, final boolean allRecipients) {
        final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank()
                .withTemplateOptions(options(allRecipients ? EmailProperty.ALL_RECIPIENTS : field.property, flags));
        for (String value : values) {
            final RecipientType type = field == CollectionField.CC ? RecipientType.CC : field == CollectionField.BCC ? RecipientType.BCC : RecipientType.TO;
            final Recipient recipient = new Recipient(value, value + "@example.test", type, null);
            switch (field) {
                case TO: case CC: case BCC: builder.withRecipients(recipient); break;
                case REPLY_TO: builder.withReplyTo(recipient); break;
                case ENVELOPE: builder.withOverrideReceivers(recipient); break;
                case ATTACHMENTS: builder.withAttachment(resources.get(value)); break;
                case IMAGES: builder.withEmbeddedImage(resources.get(value)); break;
                default: throw new AssertionError(field);
            }
        }
        return builder.buildEmail();
    }

    private static List<String> collectionValues(final CollectionField field, final Email email) {
        if (field == CollectionField.ATTACHMENTS || field == CollectionField.IMAGES) {
            return (field == CollectionField.ATTACHMENTS ? email.getAttachments() : email.getEmbeddedImages()).stream()
                    .map(AttachmentResource::getName).collect(Collectors.toList());
        }
        final Collection<Recipient> recipients = field == CollectionField.REPLY_TO ? email.getReplyToRecipients()
                : field == CollectionField.ENVELOPE ? email.getOverrideReceivers() : email.getRecipients();
        return recipients.stream().map(Recipient::getName).collect(Collectors.toList());
    }

    private static AttachmentResource resource(final String name, final AtomicInteger reads) {
        final DataSource borrowed = new DataSource() {
            @Override public InputStream getInputStream() { reads.incrementAndGet(); return new ByteArrayInputStream(new byte[] {1}); }
            @Override public OutputStream getOutputStream() { throw new UnsupportedOperationException(); }
            @Override public String getContentType() { return "image/png"; }
            @Override public String getName() { return name; }
        };
        return AttachmentResource.builder().withName(name).withDataSource(borrowed).withContentId(name).build();
    }
}
