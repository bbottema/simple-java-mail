package org.simplejavamail.recipient;

import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.IRecipientsBuilder;
import org.simplejavamail.api.internal.clisupport.CliEmailRecipientBuilder;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ValidationConfig;
import org.simplejavamail.mailer.MailInvalidAddressException;
import org.simplejavamail.config.ConfigLoader;
import com.sanctionco.jmail.JMail;
import testutil.ConfigLoaderTestHelper;

import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.Properties;

import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.Collections.singleton;

import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;

class MailboxListConvenienceTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @ValueSource(strings = {",", ";"})
    void pluralBuilderParsesQuotedPunctuationBeforeApplyingDefaultNames(final String separator) {
        final String source = "\"Support @ Acme, Europe\" <support@example.org>" + separator + "audit@example.org";
        assertThat(mail.recipientsBuilder().withRecipientsWithDefaultName("Service desk", TO, source).buildRecipients())
                .containsExactly(new Recipient("Support @ Acme, Europe", "support@example.org", TO, null),
                        new Recipient("Service desk", "audit@example.org", TO, null));
    }

    @Test
    void emailSemicolonFallbackKeepsQuotedNameWhole() {
        final Email email = mail.emailBuilder().startingBlank()
                .to("\"Support @ Acme, Europe\" <support@example.org>;audit@example.org").buildEmail();
        assertThat(email.getRecipients()).containsExactly(
                new Recipient("Support @ Acme, Europe", "support@example.org", TO, null),
                new Recipient(null, "audit@example.org", TO, null));
    }

    @Test
    void typedGroupsExpandToAllMembersWithoutUsingTheGroupLabelAsTheirName() throws Exception {
        final InternetAddress group = new InternetAddress("Team: Alice <alice@example.org>,bob@example.org;");
        assertThat(mail.recipientsBuilder().withRecipientsFromAddressesWithDefaultName("Default",
                java.util.Collections.singletonList(group), TO).buildRecipients()).containsExactly(
                new Recipient("Alice", "alice@example.org", TO, null), new Recipient("Default", "bob@example.org", TO, null));
    }

    @Test
    void individualExtractionHelpersKeepTheCompletePluralSource() {
        final String source = "Alice <alice@example.org>,Bob <bob@example.org>";
        final Recipient addressOnly = mail.recipientBuilder().withName("Chosen").withAddressOnlyFrom(source).build();
        final Recipient defaultName = mail.recipientBuilder().withAddressAndNameOrDefault(source, "Default").build();
        assertThat(addressOnly.getAddress()).isEqualTo(source);
        assertThat(addressOnly.getName()).isEqualTo("Chosen");
        assertThat(defaultName.getAddress()).isEqualTo(source);
        assertThat(defaultName.getName()).isEqualTo("Default");
    }

    @Test
    void allListCapableEmailFieldsAndTheCliAdapterShareTheSameInterpretation() {
        final String source = "\"Support @ Acme, Europe\" <support@example.org>;audit@example.org";
        final EmailPopulatingBuilder builder = mail.emailBuilder().startingBlank().to(source).cc(source).bcc(source)
                .withReplyTo(source).withDispositionNotificationTo(source).withReturnReceiptTo(source);
        final Email email = builder.buildEmail();
        for (Collection<Recipient> recipients : asList(email.getToRecipients(), email.getCcRecipients(), email.getBccRecipients(),
                email.getReplyToRecipients(), email.getDispositionNotificationToRecipients(), email.getReturnReceiptToRecipients())) {
            assertThat(recipients).extracting(Recipient::getAddress).containsExactly("support@example.org", "audit@example.org");
            assertThat(recipients).extracting(Recipient::getName).containsExactly("Support @ Acme, Europe", null);
        }
        final EmailPopulatingBuilder cli = mail.emailBuilder().startingBlank();
        ((CliEmailRecipientBuilder) cli).withRecipients(null, false, TO, source);
        assertThat(cli.buildEmail().getRecipients()).isEqualTo(email.getToRecipients());
    }

    @Test
    void collectionsAndVarargsContainCompleteListsAndRetainDuplicates() {
        final String source = "Alice <alice@example.org>,bob@example.org";
        final Collection<Recipient> collection = mail.recipientsBuilder().withRecipientsWithDefaultName("Default",
                asList(source, "carol@example.org;alice@example.org"), TO).buildRecipients();
        final Collection<Recipient> varargs = mail.recipientsBuilder().withRecipientsWithDefaultName("Default", TO,
                source, "carol@example.org;alice@example.org").buildRecipients();
        assertThat(collection).containsExactlyElementsOf(varargs);
        assertThat(collection).extracting(Recipient::getAddress)
                .containsExactly("alice@example.org", "bob@example.org", "carol@example.org", "alice@example.org");
        assertThat(collection).extracting(Recipient::getName).containsExactly("Alice", "Default", "Default", "Default");
        assertThat(mail.recipientsBuilder().withRecipientsWithFixedName("Fixed", TO, source).buildRecipients())
                .extracting(Recipient::getName).containsExactly("Fixed", "Fixed");
        assertThat(mail.recipientsBuilder().withRecipientsWithFixedName(null, TO, source).buildRecipients())
                .extracting(Recipient::getName).containsExactly("Alice", null);
        assertThat(mail.emailBuilder().startingBlank().to("Fixed", source).buildEmail().getRecipients())
                .extracting(Recipient::getName).containsExactly("Fixed", "Fixed");
    }

    @Test
    void legacySeparatorsRespectQuotesEscapesNestedCommentsAndGroupTerminators() {
        final String source = "\"A \\\"quoted; @ name, here\\\"\" <alice@example.org>;"
                + "bob@example.org (Ops; @ team, (Europe));"
                + "Team: =?UTF-8?Q?Jos=C3=A9?= <jose@example.org>,carol@example.org;,"
                + "Undisclosed:;,alice@example.org;";
        final Collection<Recipient> recipients = mail.recipientsBuilder().withRecipientsWithDefaultName(null, TO, source).buildRecipients();
        assertThat(recipients).extracting(Recipient::getAddress)
                .containsExactly("alice@example.org", "bob@example.org", "jose@example.org", "carol@example.org", "alice@example.org");
        assertThat(recipients).extracting(Recipient::getName)
                .containsExactly("A \"quoted; @ name, here\"", "Ops; @ team, (Europe)", "José", null, null);
    }

    @Test
    void emptyGroupsHaveNoMembersInStringAndTypedForms() throws Exception {
        assertThat(mail.recipientsBuilder().withRecipientsWithDefaultName("Default", TO, "Undisclosed:;").buildRecipients()).isEmpty();
        assertThat(mail.recipientsBuilder().withRecipientsFromAddressesWithFixedName("Fixed",
                singletonList(new InternetAddress("Undisclosed:;")), TO).buildRecipients()).isEmpty();
        assertThat(mail.emailBuilder().startingBlank().withReplyTo(new InternetAddress("Undisclosed:;"))
                .withDispositionNotificationTo(new InternetAddress("Undisclosed:;"))
                .withReturnReceiptTo(new InternetAddress("Undisclosed:;")).buildEmail().getReplyToRecipients()).isEmpty();
    }

    @Test
    void typedAddressCollectionsFollowTheSameDefaultAndFixedNameRules() throws Exception {
        final java.util.List<InternetAddress> addresses = asList(new InternetAddress("\"Support @ Acme, Europe\" <support@example.org>"),
                new InternetAddress("Team: audit@example.org,support@example.org;"));
        final Collection<Recipient> defaults = mail.recipientsBuilder()
                .withRecipientsFromAddressesWithDefaultName("Default", addresses, TO).buildRecipients();
        assertThat(defaults).extracting(Recipient::getName).containsExactly("Support @ Acme, Europe", "Default", "Default");
        assertThat(defaults).extracting(Recipient::getAddress)
                .containsExactly("support@example.org", "audit@example.org", "support@example.org");
        assertThat(mail.recipientsBuilder().withRecipientsFromAddressesWithFixedName("Fixed", addresses, TO).buildRecipients())
                .extracting(Recipient::getName).containsExactly("Fixed", "Fixed", "Fixed");
        assertThat(addresses.get(0).getPersonal()).isEqualTo("Support @ Acme, Europe");
        assertThat(addresses.get(1).isGroup()).isTrue();
        final Email typed = mail.emailBuilder().startingBlank().withReplyTo(addresses.get(1))
                .withDispositionNotificationTo("Fixed", addresses.get(1)).withReturnReceiptTo(addresses.get(1)).buildEmail();
        assertThat(typed.getReplyToRecipients()).extracting(Recipient::getAddress).containsExactly("audit@example.org", "support@example.org");
        assertThat(typed.getDispositionNotificationToRecipients()).extracting(Recipient::getName).containsExactly("Fixed", "Fixed");
        assertThat(typed.getReturnReceiptToRecipients()).extracting(Recipient::getAddress).containsExactly("audit@example.org", "support@example.org");
    }

    @Test
    void configuredRecipientDefaultsUseTheSameParserWithoutChangingOtherFactories() {
        final Properties properties = new Properties();
        properties.setProperty("simplejavamail.defaults.to.address", "\"Support @ Acme, Europe\" <support@example.org>;audit@example.org");
        properties.setProperty("simplejavamail.defaults.cc.address", "Team: audit@example.org,backup@example.org;");
        properties.setProperty("simplejavamail.defaults.cc.name", "Fixed");
        final SimpleJavaMail configured = SimpleJavaMail.withConfig(ConfigLoader.builder().withProperties(properties).load());
        final Email email = configured.emailBuilder().startingBlank().buildEmailCompletedWithDefaultsAndOverrides();
        assertThat(email.getToRecipients()).extracting(Recipient::getName).containsExactly("Support @ Acme, Europe", null);
        assertThat(email.getToRecipients()).extracting(Recipient::getAddress).containsExactly("support@example.org", "audit@example.org");
        assertThat(email.getCcRecipients()).extracting(Recipient::getName).containsExactly("Fixed", "Fixed");
        assertThat(mail.emailBuilder().startingBlank().buildEmailCompletedWithDefaultsAndOverrides().getRecipients()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"broken <address", "(comment only)", "   ", "alice@example.org,broken <address"})
    void malformedSourcesAreRetainedVerbatimAndRejectedByTheConfiguredValidator(final String source) throws Exception {
        final Collection<Recipient> recipients = mail.recipientsBuilder().withRecipientsWithDefaultName("Default", TO,
                "valid@example.org", source).buildRecipients();
        assertThat(recipients).extracting(Recipient::getAddress).containsExactly("valid@example.org", source);
        final CustomMailer transport = mock(CustomMailer.class);
        try (Mailer mailer = mail.mailerBuilder().withCustomMailer(transport)
                .withValidationConfig(ValidationConfig.builder().withEmailValidator(JMail.strictValidator()).build()).buildMailer()) {
            final Email email = mail.emailBuilder().startingBlank().from("sender@example.org").withRecipients(recipients)
                    .withPlainText("body").buildEmail();
            assertThatThrownBy(() -> mailer.sync().sendMail(email)).isInstanceOf(MailInvalidAddressException.class);
        }
        verifyNoInteractions(transport);
    }

    @Test
    void missingStringsFailInsteadOfSilentlyDroppingAnEntry() {
        for (String missing : new String[]{null, ""}) {
            assertThatThrownBy(() -> mail.recipientsBuilder().withRecipientsWithDefaultName(null, TO, missing))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> mail.recipientsBuilder().withRecipient(missing, TO)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void groupPoliciesApplyAfterParsingAndPreparedRecipientsRemainUnparsed() {
        final X509Certificate certificate = mock(X509Certificate.class);
        final Recipient raw = new Recipient("Raw", "Embedded <raw@example.org>", TO, certificate, singleton(FAILURE));
        final IRecipientsBuilder builder = mail.recipientsBuilder().withDefaultSmimeCertificate(certificate)
                .withDefaultDeliveryStatusNotificationNotifyOptions(FAILURE)
                .withRecipientsWithDefaultName("Default", TO, "Alice <alice@example.org>;alice@example.org")
                .withRecipient(raw);
        final Collection<Recipient> recipients = builder.buildRecipients();
        assertThat(recipients).extracting(Recipient::getAddress)
                .containsExactly("alice@example.org", "alice@example.org", "Embedded <raw@example.org>");
        assertThat(recipients).allSatisfy(recipient -> {
            assertThat(recipient.getSmimeCertificate()).isSameAs(certificate);
            assertThat(recipient.getDeliveryStatusNotificationNotifyOptions()).containsExactly(FAILURE);
        });
        final Email original = mail.emailBuilder().startingBlank().withRecipients(recipients).buildEmail();
        assertThat(mail.emailBuilder().copying(original).buildEmail().getRecipients()).containsExactlyElementsOf(recipients);
        builder.withRecipient("extra@example.org", TO);
        assertThat(original.getRecipients()).hasSize(3);
        assertThat(raw.getAddress()).isEqualTo("Embedded <raw@example.org>");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Alice <alice@example.org>,Bob <bob@example.org>", "alice@example.org;bob@example.org",
            "Team: Alice <alice@example.org>;", "Undisclosed:;"})
    void singularExtractionHelpersNeverSelectTheFirstMember(final String source) throws Exception {
        for (Recipient recipient : asList(mail.recipientBuilder().withName("Chosen").withAddressOnlyFrom(source).build(),
                mail.recipientBuilder().withAddressAndNameOrDefault(source, "Default").build(),
                mail.recipientBuilder().withAddressAndFixedNameOrProvided(source, "Fixed").build(),
                mail.recipientsBuilder().withRecipient(source, TO).buildRecipients().iterator().next())) {
            assertThat(recipient.getAddress()).isEqualTo(source);
        }
        final InternetAddress typed = new InternetAddress();
        typed.setAddress(source);
        assertThat(mail.recipientBuilder().withName("Chosen").withAddressOnlyFrom(typed).build().getAddress()).isEqualTo(source);
        assertThat(mail.recipientBuilder().withAddressAndNameOrDefault(typed, "Default").build().getAddress()).isEqualTo(source);
    }
}
