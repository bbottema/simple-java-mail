package org.simplejavamail.email.internal;

import com.sanctionco.jmail.JMail;
import jakarta.mail.Message.RecipientType;
import jakarta.mail.internet.InternetAddress;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ValidationConfig;
import org.simplejavamail.mailer.MailInvalidAddressException;
import testutil.ConfigLoaderTestHelper;

import java.security.cert.X509Certificate;
import java.nio.charset.StandardCharsets;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;

class EmailAddressConvenienceTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @ParameterizedTest
    @ValueSource(strings = {"Team:alice@example.org;", "Undisclosed:;"})
    void parsedGroupsRemainReadableWithoutWeakeningOutgoingConveniences(final String group) {
        for (String header : new String[]{"From", "Reply-To"}) {
            final String eml = "From: " + ("From".equals(header) ? group : "Sender <sender@example.org>") + "\r\n"
                    + ("Reply-To".equals(header) ? "Reply-To: " + group + "\r\n" : "")
                    + "To: receiver@example.org\r\nSubject: Import\r\nContent-Type: text/plain\r\n\r\nbody\r\n";
            final Email imported = mail.converter().fromEml(eml).toEmail();
            assertThat("From".equals(header) ? imported.getFromRecipient().getAddress()
                    : imported.getReplyToRecipients().get(0).getAddress()).isEqualTo(group);

            final byte[] original = eml.getBytes(StandardCharsets.UTF_8);
            final Email exact = mail.emailBuilder().startingFromExactEml(original)
                    .withEnvelopeSender("sender@example.org").withEnvelopeRecipients("receiver@example.org").buildEmail();
            assertThat(mail.converter().fromEmail(exact).toEmlBytes()).isEqualTo(original);
        }
        assertThatThrownBy(() -> blank().from(group)).isInstanceOf(IllegalArgumentException.class);
        assertThat(blank().withReplyTo(group).buildEmail().getReplyToRecipients()).hasSize(group.equals("Undisclosed:;") ? 0 : 1);
    }

    @ParameterizedTest
    @EnumSource(Field.class)
    void stringConveniencesInterpretOneMailboxAndNamePrecedence(final Field field) {
        assertMailbox(field.string(blank(), null, "alice@example.org", false), field, null, "alice@example.org");
        assertMailbox(field.string(blank(), null, "Alice <alice@example.org>", false), field, "Alice", "alice@example.org");
        assertMailbox(field.string(blank(), "Accounts", "Alice <alice@example.org>", true), field, "Accounts", "alice@example.org");
        for (String absentName : new String[]{null, "", " \t "}) {
            assertMailbox(field.string(blank(), absentName, "Alice <alice@example.org>", true), field, "Alice", "alice@example.org");
            assertMailbox(field.string(blank(), absentName, "alice@example.org", true), field, null, "alice@example.org");
        }
    }

    @ParameterizedTest
    @EnumSource(Field.class)
    void punctuationAndEncodedPersonalNamesAreSingleMailboxes(final Field field) {
        assertMailbox(field.string(blank(), null, "\"Doe, Alice; Accounts\" <alice@example.org>", false),
                field, "Doe, Alice; Accounts", "alice@example.org");
        assertMailbox(field.string(blank(), null, "=?UTF-8?Q?Jos=C3=A9?= <jose@example.org>", false),
                field, "José", "jose@example.org");
    }

    @ParameterizedTest
    @EnumSource(value = Field.class, names = {"FROM", "REPLY", "BOUNCE", "DISPOSITION", "RETURN_RECEIPT"})
    void typedConveniencesUseTheSameMailboxAndNamePrecedence(final Field field) throws Exception {
        final InternetAddress mailbox = new InternetAddress("Alice <alice@example.org>");
        assertMailbox(field.typed(blank(), null, mailbox, false), field, "Alice", "alice@example.org");
        assertMailbox(field.typed(blank(), "Accounts", mailbox, true), field, "Accounts", "alice@example.org");
        for (String absentName : new String[]{null, "", " \t "}) {
            assertMailbox(field.typed(blank(), absentName, mailbox, true), field, "Alice", "alice@example.org");
        }
        assertMailbox(field.typed(blank(), null, new InternetAddress("alice@example.org"), true), field, null, "alice@example.org");
        assertThat(mailbox.getPersonal()).isEqualTo("Alice");
        assertThat(mailbox.getAddress()).isEqualTo("alice@example.org");
    }

    @ParameterizedTest
    @EnumSource(value = Field.class, names = {"FROM", "BOUNCE"})
    void rejectsParsedListsAndGroupsBeforeMutatingTheBuilder(final Field field) {
        for (String source : new String[]{"alice@example.org, bob@example.org", "Team:alice@example.org;", "Undisclosed:;"}) {
            final EmailPopulatingBuilder builder = blank();
            final Email before = builder.buildEmail();
            assertThatThrownBy(() -> field.string(builder, "Accounts", source, true))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("one mailbox")
                    .hasMessageContaining("recipientsBuilder()");
            assertThat(builder.buildEmail()).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Field.class, names = {"FROM", "BOUNCE"})
    void typedInputsCannotBypassSingleMailboxChecks(final Field field) {
        for (String source : new String[]{"alice@example.org, bob@example.org", "Team:alice@example.org;", "Undisclosed:;"}) {
            final InternetAddress mailbox = new InternetAddress();
            mailbox.setAddress(source);
            assertThatThrownBy(() -> field.typed(blank(), null, mailbox, false))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("recipientsBuilder()");
        }
    }

    @ParameterizedTest
    @EnumSource(Field.class)
    void malformedOrMailboxFreeSourcesRetainTheirTextForSendTimeValidation(final Field field) throws Exception {
        final CustomMailer transport = mock(CustomMailer.class);
        try (Mailer mailer = mail.mailerBuilder()
                .withCustomMailer(transport)
                .withValidationConfig(ValidationConfig.builder().withEmailValidator(JMail.strictValidator()).build())
                .buildMailer()) {
            for (String source : new String[]{"broken <address", "(comment only)", "   ", " \t ", "one@example.org;two@example.org"}) {
                if (source.equals("one@example.org;two@example.org") && field != Field.FROM && field != Field.BOUNCE) {
                    continue; // A semicolon list is now valid on the collection-valued conveniences.
                }
                final Email email = field.string(blank(), "Chosen", source, true).buildEmail();
                assertMailbox(email, field, "Chosen", source);
                assertThatThrownBy(() -> mailer.sync().sendMail(email)).isInstanceOf(MailInvalidAddressException.class);
            }
            verifyNoInteractions(transport);
        }
    }

    @ParameterizedTest
    @EnumSource(Field.class)
    void missingRequiredStringsFailWithoutChangingExistingChoices(final Field field) {
        for (String missing : new String[]{null, ""}) {
            final EmailPopulatingBuilder builder = blank();
            final Email before = builder.buildEmail();
            assertThatThrownBy(() -> field.string(builder, null, missing, true)).isInstanceOf(IllegalArgumentException.class);
            assertThat(builder.buildEmail()).isEqualTo(before);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Field.class, names = {"FROM", "REPLY", "BOUNCE", "DISPOSITION", "RETURN_RECEIPT"})
    void typedMissingAddressesStillFail(final Field field) {
        final InternetAddress missing = new InternetAddress();
        assertThatThrownBy(() -> field.typed(blank(), null, missing, false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> field.typed(blank(), null, null, true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullableBounceClearAndScalarReplacementRemainAvailable() {
        final EmailPopulatingBuilder builder = blank().from("Old <old@example.org>")
                .from("New <new@example.org>").withBounceTo("Old <old-bounce@example.org>")
                .withBounceTo("New <new-bounce@example.org>");
        assertThat(builder.getFromRecipient().getAddress()).isEqualTo("new@example.org");
        assertThat(builder.getBounceToRecipient().getAddress()).isEqualTo("new-bounce@example.org");
        assertThat(builder.withBounceTo((String) null).getBounceToRecipient()).isNull();
        assertThat(builder.withBounceTo("bounce@example.org").withBounceTo((Recipient) null).getBounceToRecipient()).isNull();
    }

    @Test
    void copyingAndRepeatedAddressCallsAppendWithoutChangingTheOriginal() {
        final Recipient first = new Recipient("Original", "original@example.org", null, null);
        final Recipient extra = new Recipient("Extra", "extra@example.org", null, null);
        final EmailPopulatingBuilder originalBuilder = blank().withReplyTo(first).to("alice@example.org");
        final Email original = originalBuilder.buildEmail();
        for (EmailPopulatingBuilder copied : asList(mail.emailBuilder().copying(original), mail.emailBuilder().copying(originalBuilder))) {
            final Email added = copied.withReplyTo("Extra <extra@example.org>").withReplyTo(asList(first, extra))
                    .withReplyTo(emptyList()).to("alice@example.org").withRecipients(emptyList()).withRecipients().buildEmail();
            assertThat(added.getReplyToRecipients()).containsExactly(first, extra, first, extra);
            assertThat(added.getRecipients()).extracting(Recipient::getAddress)
                    .containsExactly("base@example.org", "alice@example.org", "alice@example.org");
            assertThat(copied.clearReplyTo().withReplyTo("replacement@example.org").buildEmail().getReplyToRecipients())
                    .extracting(Recipient::getAddress).containsExactly("replacement@example.org");
        }
        assertThat(original.getReplyToRecipients()).containsExactly(first);
        assertThat(originalBuilder.getReplyToRecipients()).containsExactly(first);
        assertThat(original.getRecipients()).hasSize(2);
    }

    @Test
    void preparedRecipientsKeepRawAddressesMetadataOrderAndDuplicates() {
        final X509Certificate certificate = mock(X509Certificate.class);
        final Recipient prepared = new Recipient("Chosen", "Embedded <alice@example.org>", TO, certificate,
                java.util.Collections.singleton(FAILURE));
        final Email email = blank().clearRecipients().withRecipients(prepared).to("Bob <bob@example.org>")
                .withRecipients(asList(prepared, prepared)).buildEmail();
        assertThat(email.getRecipients()).containsExactly(prepared, new Recipient("Bob", "bob@example.org", TO, null), prepared, prepared);
        assertThat(email.getRecipients().get(0).getAddress()).isEqualTo("Embedded <alice@example.org>");
        assertThat(email.getRecipients().get(0).getSmimeCertificate()).isSameAs(certificate);
        assertThat(email.getRecipients().get(0).getDeliveryStatusNotificationNotifyOptions()).containsExactly(FAILURE);
    }

    private EmailPopulatingBuilder blank() {
        return mail.emailBuilder().startingBlank().from("sender@example.org").to("base@example.org").withPlainText("body");
    }

    private static void assertMailbox(final EmailPopulatingBuilder builder, final Field field, final String name, final String address) {
        assertMailbox(builder.buildEmail(), field, name, address);
    }

    private static void assertMailbox(final Email email, final Field field, final String name, final String address) {
        final Recipient recipient = field.read(email);
        assertThat(recipient.getName()).as("%s display name", field).isEqualTo(name);
        assertThat(recipient.getAddress()).as("%s address", field).isEqualTo(address);
        assertThat(recipient.getType()).isEqualTo(field.type);
    }

    private enum Field {
        FROM(null), TO_FIELD(TO), CC_FIELD(CC), BCC_FIELD(BCC), REPLY(null), BOUNCE(null), DISPOSITION(null), RETURN_RECEIPT(null);

        private final RecipientType type;

        Field(final RecipientType type) { this.type = type; }

        EmailPopulatingBuilder string(final EmailPopulatingBuilder builder, final String name, final String address, final boolean named) {
            switch (this) {
                case FROM: return named ? builder.from(name, address) : builder.from(address);
                case TO_FIELD: return named ? builder.to(name, address) : builder.to(address);
                case CC_FIELD: return named ? builder.cc(name, address) : builder.cc(address);
                case BCC_FIELD: return named ? builder.bcc(name, address) : builder.bcc(address);
                case REPLY: return named ? builder.withReplyTo(name, address) : builder.withReplyTo(address);
                case BOUNCE: return named ? builder.withBounceTo(name, address) : builder.withBounceTo(address);
                case DISPOSITION: return named ? builder.withDispositionNotificationTo(name, address) : builder.withDispositionNotificationTo(address);
                case RETURN_RECEIPT: return named ? builder.withReturnReceiptTo(name, address) : builder.withReturnReceiptTo(address);
                default: throw new AssertionError(this);
            }
        }

        EmailPopulatingBuilder typed(final EmailPopulatingBuilder builder, final String name, final InternetAddress address, final boolean named) {
            switch (this) {
                case FROM: return named ? builder.from(name, address) : builder.from(address);
                case REPLY: return named ? builder.withReplyTo(name, address) : builder.withReplyTo(address);
                case BOUNCE: return named ? builder.withBounceTo(name, address) : builder.withBounceTo(address);
                case DISPOSITION: return named ? builder.withDispositionNotificationTo(name, address) : builder.withDispositionNotificationTo(address);
                case RETURN_RECEIPT: return named ? builder.withReturnReceiptTo(name, address) : builder.withReturnReceiptTo(address);
                default: throw new AssertionError(this);
            }
        }

        Recipient read(final Email email) {
            switch (this) {
                case FROM: return email.getFromRecipient();
                case TO_FIELD: case CC_FIELD: case BCC_FIELD:
                    return email.getRecipients().stream().filter(recipient -> type.equals(recipient.getType())).reduce((first, last) -> last).orElseThrow(AssertionError::new);
                case REPLY: return email.getReplyToRecipients().get(email.getReplyToRecipients().size() - 1);
                case BOUNCE: return email.getBounceToRecipient();
                case DISPOSITION: return email.getDispositionNotificationToRecipients().get(0);
                case RETURN_RECEIPT: return email.getReturnReceiptToRecipients().get(0);
                default: throw new AssertionError(this);
            }
        }
    }
}
