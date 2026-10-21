package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.email.config.EmailTemplateOptions;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.converter.EmailConverter;
import org.subethamail.wiser.WiserMessage;
import testutil.ConfigLoaderTestHelper;
import testutil.testrules.SmtpServerExtension;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.email.EmailProperty.SUBJECT;
import static org.simplejavamail.mailer.internal.LockedEmailConfigurationTest.locked;

@Timeout(45)
class RepeatableCompletionSmtpTest {

    @RegisterExtension final SmtpServerExtension firstServer = new SmtpServerExtension(0, null, null, false);
    @RegisterExtension final SmtpServerExtension secondServer = new SmtpServerExtension(0, null, null, false);
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @Test
    void completedAndRehearsedEmailsCanBeSentAgainWithoutRepeatingMimePartsOrEnvelopeRecipients() throws Exception {
        final Email defaults = template("first");
        try (Mailer mailer = builder(mail, firstServer, UUID.randomUUID()).withEmailDefaults(defaults).buildMailer()) {
            final Email completed = message().buildEmailCompletedWithDefaultsAndOverrides(mailer.getEmailGovernance());
            final Email rehearsed = mailer.rehearse(completed).getEffectiveEmail();

            final MailSubmissionReceipt receipt = mailer.async().sendMail(rehearsed).getCompletion().get(20, TimeUnit.SECONDS);

            assertThat(receipt.getAcceptedRecipients()).containsExactly("visible@example.org", "first@example.org");
            assertThat(firstServer.getMessages()).hasSize(2);
            final Email received = EmailConverter.mimeMessageToEmail(firstServer.getMessages().get(0).getMimeMessage());
            assertThat(received.getAttachments()).hasSize(1);
            assertThat(received.getAttachments().get(0).readAllBytes()).containsExactly("shared file".getBytes(US_ASCII));
            assertThat(received.getEmbeddedImages()).hasSize(1);
            assertThat(received.getReplyToRecipients()).extracting(Recipient::getAddress).containsExactly("reply@example.org");
            assertThat(rehearsed.getRecipients()).containsExactlyElementsOf(completed.getRecipients());
        }
    }

    @Test
    void lockedDisplayNamesStayStableThroughCompletionRehearsalAndSmtpSending() throws Exception {
        final SimpleJavaMail lockedMail = locked(Map.of("defaults.to.name", "Company", "defaults.replyto.name", "Company"));
        final Email defaults = mail.emailBuilder().startingBlank().withRecipients(mail.recipientBuilder().to("extra@example.org"))
                .withReplyTo("reply@example.org").buildEmail();
        try (Mailer mailer = builder(lockedMail, firstServer, UUID.randomUUID()).withEmailDefaults(defaults).buildMailer()) {
            final Email completed = message().buildEmailCompletedWithDefaultsAndOverrides(mailer.getEmailGovernance());
            final Email rehearsed = mailer.rehearse(completed).getEffectiveEmail();

            assertThat(mailer.sync().sendMail(rehearsed).getAcceptedRecipients()).containsExactly("visible@example.org", "extra@example.org");

            assertThat(firstServer.getMessages()).hasSize(2);
            final Email received = EmailConverter.mimeMessageToEmail(firstServer.getMessages().get(0).getMimeMessage());
            assertThat(received.getToRecipients()).extracting(Recipient::getName).containsExactly("Company", "Company");
            assertThat(received.getToRecipients()).extracting(Recipient::getAddress).containsExactly("visible@example.org", "extra@example.org");
            assertThat(received.getReplyToRecipients()).singleElement().satisfies(recipient -> {
                assertThat(recipient.getName()).isEqualTo("Company");
                assertThat(recipient.getAddress()).isEqualTo("reply@example.org");
            });
        }
    }

    @Test
    void initiatingNameLocksAccompanyTheSelectedClusterMembersTemplateComparison() throws Exception {
        final SimpleJavaMail lockedMail = locked(Map.of("defaults.to.name", "Company", "defaults.replyto.name", "Company"));
        final Email firstDefaults = mail.emailBuilder().startingBlank().withRecipients(mail.recipientBuilder().to("extra@example.org"))
                .withReplyTo("reply@example.org").buildEmail();
        final Email secondDefaults = mail.emailBuilder().copying(firstDefaults)
                .withRecipients(mail.recipientBuilder().to("selected-only@example.org")).buildEmail();
        final UUID cluster = UUID.randomUUID();
        try (Mailer first = builder(lockedMail, firstServer, cluster).withEmailDefaults(firstDefaults).buildMailer();
                Mailer second = builder(mail, secondServer, cluster).withEmailDefaults(secondDefaults)
                        .withEmailOverrides(mail.emailBuilder().startingBlank().withHeader("X-Destination", "second").buildEmail()).buildMailer()) {
            final Email completed = message().buildEmailCompletedWithDefaultsAndOverrides(first.getEmailGovernance());

            first.sync().sendMail(completed);
            first.sync().sendMail(completed);

            assertThat(firstServer.getMessages()).hasSize(2);
            assertThat(secondServer.getMessages()).extracting(WiserMessage::getEnvelopeReceiver)
                    .containsExactly("visible@example.org", "extra@example.org", "selected-only@example.org");
            assertThat(secondServer.getMessages().get(0).getMimeMessage().getHeader("X-Destination")).containsExactly("second");
            final Email received = EmailConverter.mimeMessageToEmail(secondServer.getMessages().get(0).getMimeMessage());
            assertThat(received.getToRecipients()).extracting(Recipient::getName).containsExactly("Company", "Company", "Company");
            assertThat(received.getReplyToRecipients()).singleElement().satisfies(recipient -> {
                assertThat(recipient.getName()).isEqualTo("Company");
                assertThat(recipient.getAddress()).isEqualTo("reply@example.org");
            });
        }
    }

    @Test
    void anotherMailerAddsMissingDefaultsAndOverridesWhileLocksAndSuppressionRemainBinding() throws Exception {
        final SimpleJavaMail lockedMail = locked(Map.of("defaults.bcc.address", "archive@example.org"));
        final Email secondDefaults = mail.emailBuilder().copying(template("second"))
                .withAttachment("second.txt", "second file".getBytes(US_ASCII), "text/plain").buildEmail();
        try (Mailer first = builder(lockedMail, firstServer, UUID.randomUUID()).withEmailDefaults(template("first")).buildMailer();
                Mailer second = builder(lockedMail, secondServer, UUID.randomUUID()).withEmailDefaults(secondDefaults)
                        .withEmailOverrides(mail.emailBuilder().startingBlank().withSubject("excluded subject")
                                .withHeader("X-Destination", "second").buildEmail()).buildMailer()) {
            final Email completed = message().withTemplateOptions(EmailTemplateOptions.builder()
                    .dontApplyOverrideValueFor(SUBJECT).build()).buildEmailCompletedWithDefaultsAndOverrides(first.getEmailGovernance());

            final MailSubmissionReceipt receipt = second.sync().sendMail(completed);

            assertThat(receipt.getAcceptedRecipients()).containsExactly("visible@example.org", "first@example.org", "archive@example.org", "second@example.org");
            assertThat(firstServer.getMessages()).isEmpty();
            assertThat(secondServer.getMessages()).hasSize(4);
            final WiserMessage delivered = secondServer.getMessages().get(0);
            assertThat(delivered.getMimeMessage().getSubject()).isEqualTo("first subject");
            assertThat(delivered.getMimeMessage().getHeader("X-Destination")).containsExactly("second");
            final Email received = EmailConverter.mimeMessageToEmail(delivered.getMimeMessage());
            assertThat(received.getAttachments()).extracting(resource -> resource.getName()).containsExactly("shared.txt", "second.txt");
            assertThat(received.getEmbeddedImages()).hasSize(1);
            assertThat(completed.getBccRecipients()).extracting(Recipient::getAddress).containsExactly("first@example.org", "archive@example.org");
        }
    }

    @Test
    void selectedClusterMemberCompletesSubmittedMaterializedValuesUsingItsOwnPolicy() throws Exception {
        final UUID cluster = UUID.randomUUID();
        try (Mailer first = builder(mail, firstServer, cluster).withEmailDefaults(template("first"))
                        .withEmailOverrides(mail.emailBuilder().startingBlank().withHeader("X-Destination", "first").buildEmail()).buildMailer();
                Mailer second = builder(mail, secondServer, cluster).withEmailDefaults(template("second"))
                        .withEmailOverrides(mail.emailBuilder().startingBlank().withSubject("second override")
                                .withHeader("X-Destination", "second").buildEmail()).buildMailer()) {
            final Email completed = message().buildEmailCompletedWithDefaultsAndOverrides(first.getEmailGovernance());

            first.sync().sendMail(completed);
            first.sync().sendMail(completed);

            assertThat(firstServer.getMessages()).hasSize(2);
            assertThat(secondServer.getMessages()).hasSize(3);
            assertThat(firstServer.getMessages().get(0).getMimeMessage().getHeader("X-Destination")).containsExactly("first");
            assertThat(secondServer.getMessages().get(0).getMimeMessage().getHeader("X-Destination")).containsExactly("second");
            assertThat(secondServer.getMessages().get(0).getMimeMessage().getSubject()).isEqualTo("second override");
            assertThat(secondServer.getMessages()).extracting(WiserMessage::getEnvelopeReceiver)
                    .containsExactly("visible@example.org", "first@example.org", "second@example.org");
            assertThat(EmailConverter.mimeMessageToEmail(secondServer.getMessages().get(0).getMimeMessage()).getAttachments()).hasSize(1);
            assertThat(completed.getBccRecipients()).extracting(Recipient::getAddress).containsExactly("first@example.org");
        }
    }

    @Test
    void completedEmailsCannotBypassLockedConflictsAndExactMessagesRetainTheirBytes() throws Exception {
        final SimpleJavaMail lockedMail = locked(Map.of("defaults.subject", "locked subject", "defaults.bcc.address", "archive@example.org"));
        try (Mailer mailer = builder(lockedMail, firstServer, UUID.randomUUID()).buildMailer()) {
            final Email conflicting = message().withSubject("different subject")
                    .withTemplateOptions(EmailTemplateOptions.builder().ignoringDefaults().ignoringOverrides().build())
                    .buildEmailCompletedWithDefaultsAndOverrides();
            assertThatThrownBy(() -> mailer.sync().sendMail(conflicting)).hasMessageContaining("locked.defaults.subject");
            assertThat(firstServer.getMessages()).isEmpty();
        }
        final byte[] bytes = ("From: sender@example.org\r\nTo: visible@example.org\r\nSubject: unchanged\r\n"
                + "Message-ID: <exact-completion@example.org>\r\n\r\nexact body\r\n").getBytes(US_ASCII);
        final Email exact = mail.emailBuilder().startingFromExactEml(bytes).withEnvelopeRecipients("visible@example.org").buildEmail();
        try (Mailer mailer = builder(locked(Map.of("defaults.bcc.address", "archive@example.org")), firstServer, UUID.randomUUID())
                .withEmailDefaults(template("ignored")).buildMailer()) {
            final Email rehearsed = mailer.rehearse(exact).getEffectiveEmail();
            assertThat(mailer.rehearse(rehearsed).getEmlBytes()).containsExactly(bytes);
            assertThat(mailer.sync().sendMail(rehearsed).getAcceptedRecipients()).containsExactly("visible@example.org", "archive@example.org");
            assertThat(firstServer.getMessages()).hasSize(2);
            assertThat(firstServer.getMessages().get(0).getData()).containsExactly(bytes);
        }
    }

    private EmailPopulatingBuilder message() {
        return mail.emailBuilder().startingBlank().from("sender@example.org").withRecipients(mail.recipientBuilder().to("visible@example.org"))
                .withHTMLText("<p>body</p><img src=\"cid:logo\">").fixingMessageId("<repeatable-completion@example.org>");
    }

    private Email template(final String destination) {
        return mail.emailBuilder().startingBlank().withSubject(destination + " subject")
                .withRecipients(mail.recipientBuilder().bcc(destination + "@example.org"))
                .withReplyTo("reply@example.org").withAttachment("shared.txt", "shared file".getBytes(US_ASCII), "text/plain")
                .withEmbeddedImage("logo", new byte[] {1, 2, 3}, "image/png").buildEmail();
    }

    private static MailerRegularBuilder<?> builder(final SimpleJavaMail factory, final SmtpServerExtension server, final UUID cluster) {
        return factory.mailerBuilder().withSMTPServer("localhost", server.getPort())
                .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("localhost").build())
                .withProperty("mail.host", "localhost")
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withClusterKey(cluster).withCoreSize(0).withMaxSize(1)
                        .withLoadBalancingStrategy(LoadBalancingStrategy.ROUND_ROBIN).build());
    }
}
