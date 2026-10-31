package org.simplejavamail.recipient;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.IRecipientsBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.config.ConfigLoader;
import testutil.smtp.RecordingSmtpServer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static jakarta.mail.Message.RecipientType.BCC;
import static jakarta.mail.Message.RecipientType.CC;
import static jakarta.mail.Message.RecipientType.TO;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.FAILURE;
import static org.simplejavamail.api.email.config.DeliveryStatusNotification.NotifyOption.SUCCESS;

class OrderedRecipientListTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @Test
    void resultIsAnOrderedDetachedUnmodifiableListWithGroupMetadata() {
        final IRecipientsBuilder builder = mail.recipientsBuilder()
                .withRecipientsWithDefaultName("Fallback", TO,
                        "Zed <z@example.org>", "a@example.org", "Zed <z@example.org>")
                .withFixedDeliveryStatusNotificationNotifyOptions(FAILURE);

        final List<Recipient> first = builder.buildRecipients();
        builder.withRecipient("later@example.org", TO).withFixedDeliveryStatusNotificationNotifyOptions(SUCCESS);
        final List<Recipient> second = builder.buildRecipients();
        final Collection<Recipient> previousSourceType = first;

        assertThat(first).extracting(Recipient::getAddress).containsExactly("z@example.org", "a@example.org", "z@example.org");
        assertThat(first).extracting(Recipient::getName).containsExactly("Zed", "Fallback", "Zed");
        assertThat(first).allSatisfy(recipient -> assertThat(recipient.getDeliveryStatusNotificationNotifyOptions()).containsExactly(FAILURE));
        assertThat(second).extracting(Recipient::getAddress)
                .containsExactly("z@example.org", "a@example.org", "z@example.org", "later@example.org");
        assertThat(second).allSatisfy(recipient -> assertThat(recipient.getDeliveryStatusNotificationNotifyOptions()).containsExactly(SUCCESS));
        assertThat(previousSourceType).hasSize(3);
        assertThatThrownBy(first::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.add(first.get(0))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void listPassesDirectlyToOrderedEmailSettersAndSurvivesCopyAndSerialization() throws Exception {
        final List<Recipient> team = team();
        final Email original = mail.emailBuilder().startingBlank().from("sender@example.org")
                .withRecipients(team).withReplyTo(team).withOverrideReceivers(team).withPlainText("body").buildEmail();

        assertOrderedLists(original);
        assertOrderedLists(mail.emailBuilder().copying(original).buildEmail());
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(original);
        }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            assertOrderedLists((Email) input.readObject());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void explicitEnvelopeOrderAndDuplicateOccurrencesReachAngus(final boolean asynchronous) throws Exception {
        final List<Recipient> team = team();
        try (RecordingSmtpServer server = new RecordingSmtpServer();
             Mailer mailer = mailer(server)) {
            final Email email = mail.emailBuilder().startingBlank().from("sender@example.org").to("visible@example.org")
                    .withReplyTo(team).withOverrideReceivers(team).withPlainText("body").buildEmail();

            if (asynchronous) {
                mailer.async().sendMail(email).getCompletion().get(20, TimeUnit.SECONDS);
            } else {
                mailer.sync().sendMail(email);
            }

            assertThat(server.commands()).filteredOn(command -> command.startsWith("RCPT TO:"))
                    .containsExactly("RCPT TO:<z@example.org>", "RCPT TO:<a@example.org>", "RCPT TO:<z@example.org>");
            assertThat(server.messages()).hasSize(1);
            final MimeMessage received = new MimeMessage(Session.getInstance(new Properties()),
                    new ByteArrayInputStream(server.messages().get(0)));
            assertThat(received.getRecipients(TO)).extracting(Object::toString).containsExactly("visible@example.org");
            assertThat(Arrays.asList(received.getReplyTo())).extracting(Object::toString)
                    .containsExactly("z@example.org", "a@example.org", "z@example.org");
        }
    }

    @Test
    void ordinaryEnvelopeRetainsRoleGroupingAndEncounterOrderWithinEachRole() throws Exception {
        final List<Recipient> recipients = mail.recipientsBuilder()
                .withRecipient("cc-z@example.org", CC).withRecipient("to-z@example.org", TO)
                .withRecipient("bcc-z@example.org", BCC).withRecipient("to-a@example.org", TO)
                .withRecipient("cc-a@example.org", CC).withRecipient("to-z@example.org", TO).buildRecipients();
        assertThat(recipients).extracting(Recipient::getAddress).containsExactly("cc-z@example.org", "to-z@example.org",
                "bcc-z@example.org", "to-a@example.org", "cc-a@example.org", "to-z@example.org");

        try (RecordingSmtpServer server = new RecordingSmtpServer();
             Mailer mailer = mailer(server)) {
            final Email email = mail.emailBuilder().startingBlank().from("sender@example.org")
                    .withRecipients(recipients).withPlainText("body").buildEmail();
            mailer.sync().sendMail(email);

            assertThat(server.commands()).filteredOn(command -> command.startsWith("RCPT TO:"))
                    .containsExactly("RCPT TO:<to-z@example.org>", "RCPT TO:<to-a@example.org>", "RCPT TO:<to-z@example.org>",
                            "RCPT TO:<cc-z@example.org>", "RCPT TO:<cc-a@example.org>", "RCPT TO:<bcc-z@example.org>");
        }
    }

    private List<Recipient> team() {
        return mail.recipientsBuilder().withRecipient("z@example.org", TO).withRecipient("a@example.org", TO)
                .withRecipient("z@example.org", TO).buildRecipients();
    }

    private Mailer mailer(final RecordingSmtpServer server) {
        return mail.mailerBuilder().withSMTPServer("127.0.0.1", server.port())
                .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("probe.example.test").build()).buildMailer();
    }

    private void assertOrderedLists(final Email email) {
        assertThat(email.getRecipients()).extracting(Recipient::getAddress).containsExactly("z@example.org", "a@example.org", "z@example.org");
        assertThat(email.getReplyToRecipients()).extracting(Recipient::getAddress).containsExactly("z@example.org", "a@example.org", "z@example.org");
        assertThat(email.getOverrideReceivers()).extracting(Recipient::getAddress).containsExactly("z@example.org", "a@example.org", "z@example.org");
    }
}
