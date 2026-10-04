package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionException;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.config.ConfigLoader;
import testutil.smtp.ScriptedSmtpServer;
import testutil.testrules.SmtpServerExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.mailer.internal.LockedEmailConfigurationTest.locked;
import static org.simplejavamail.mailer.internal.SmtpDsnCharacterizationTest.finishConnection;
import static org.simplejavamail.recipient.RecipientBuilder.to;

@Timeout(40)
class LockedConfigurationSendingTest {

	@RegisterExtension final SmtpServerExtension smtp = new SmtpServerExtension(0, null, null, false);
	private final SimpleJavaMail ordinary = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

	@Test
	void concurrentPooledSendsAlwaysIncludeTheArchiveWithoutLeakingRecipientsOrOutcomes() throws Exception {
		final SimpleJavaMail factory = locked(Map.of("defaults.bcc.address", "archive@example.org", "smtp.host", "localhost"));
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		try (Mailer mailer = builder(factory).withConnectionPoolMaxSize(3).withMailSendObserver(outcomes::add).buildMailer()) {
			final List<CompletableFuture<MailSubmissionReceipt>> completions = new ArrayList<>();
			for (int index = 0; index < 12; index++) {
				completions.add(mailer.async().sendMail(email("mail-" + index)).getCompletion());
			}
			CompletableFuture.allOf(completions.toArray(new CompletableFuture<?>[0])).get(15, SECONDS);
			for (int index = 0; index < completions.size(); index++) {
				assertThat(completions.get(index).join().getAcceptedRecipients()).containsExactly("mail-" + index + "@example.org", "archive@example.org");
			}
			assertThat(outcomes).hasSize(12).allSatisfy(outcome -> assertThat(outcome.isSuccessful()).isTrue());
		}
		assertThat(smtp.getMessages()).hasSize(24);
	}

	@Test
	void batchesStopAtAConflictAndOpenConnectionSendsRetainTheArchive() throws Exception {
		final SimpleJavaMail factory = locked(Map.of("defaults.subject", "Locked", "defaults.bcc.address", "archive@example.org"));
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		final Email conflict = ordinary.emailBuilder().copying(email("conflict")).withSubject("Other").buildEmail();
		try (Mailer mailer = builder(factory).withMailSendObserver(outcomes::add).buildMailer()) {
			assertThatThrownBy(() -> mailer.sync().sendMailsInSimpleBatch(List.of(email("first"), conflict, email("untouched"))))
					.hasRootCauseInstanceOf(IllegalArgumentException.class);
			assertThat(outcomes).hasSize(2);
			assertThat(outcomes.get(0).isSuccessful()).isTrue();
			assertThat(outcomes.get(1).getSubmissionReceipt()).isEmpty();
			mailer.withOpenConnection(sender -> sender.sendMail(email("open")));
		}
		assertThat(smtp.getMessages()).hasSize(4);
		assertThat(smtp.getMessages()).allSatisfy(message -> assertThat(message.getEnvelopeReceiver()).isNotEqualTo("untouched@example.org"));
	}

	@Test
	void theActualArchiveEnvelopeIsCostedBeforeWaitingForRecipientAllowance() throws Exception {
		try (Mailer mailer = builder(locked(Map.of("defaults.bcc.address", "archive@example.org")))
				.withRecipientRateLimit(1, java.time.Duration.ofMinutes(1)).buildMailer()) {
			assertThatThrownBy(() -> mailer.sync().sendMail(email("recipient")))
					.hasMessageContaining("2 envelope recipients").hasMessageContaining("only 1");
		}
		assertThat(smtp.getMessages()).isEmpty();
	}

	@Test
	void aLockedDsnRequestRejectsBeforeMailFromWhenTheServerDoesNotSupportIt() throws Exception {
		try (ScriptedSmtpServer server = new ScriptedSmtpServer(1, peer -> {
			peer.greet("250 localhost");
			finishConnection(peer); // Only health checks and QUIT; MAIL FROM here makes the script fail.
		}); Mailer mailer = locked(Map.of("defaults.delivery.status.notification.notify", "FAILURE")).mailerBuilder()
				.withSMTPServer("localhost", server.port()).withSmtpClientHostname("probe.example.test")
				.withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1).withSessionTimeout(5000).buildMailer()) {
			assertThatThrownBy(() -> mailer.sync().sendMail(email("notify"))).isInstanceOf(MailSubmissionException.class)
					.hasRootCauseMessage("This send requires its delivery-notification settings to be honored, "
							+ "but the connected SMTP server does not advertise DSN support. Simple Java Mail cannot submit the email with that requirement. "
							+ "Use a server that advertises DSN, or remove the mandatory notification requirement, "
							+ "including any notification settings fixed by simplejavamail.locked.* properties. No message was submitted.");
		}
	}

	@Test
	void compatibleClusterMembersRetainTheSameLocksWhileConflictingDestinationsAreRejected() throws Exception {
		final SimpleJavaMail factory = locked(Map.of("smtp.host", "localhost", "defaults.bcc.address", "archive@example.org"));
		final UUID cluster = UUID.randomUUID();
		try (Mailer first = builder(factory).withClusterKey(cluster).buildMailer();
				Mailer second = builder(factory).withClusterKey(cluster).buildMailer();
				Mailer incompatible = ordinary.mailerBuilder().withSMTPServer("elsewhere.invalid", 25).withConnectionPoolCoreSize(0).buildMailer()) {
			SessionBasedEmailToMimeMessageConverter.verifySelectedSession(first.getSession(), second.getSession());
			first.sync().sendMail(email("cluster"));
			assertThatThrownBy(() -> SessionBasedEmailToMimeMessageConverter.verifySelectedSession(first.getSession(), incompatible.getSession()))
					.hasMessageContaining("locked.smtp.host");
		}
		assertThat(smtp.getMessages()).hasSize(2);
	}

	private MailerRegularBuilder<?> builder(final SimpleJavaMail factory) {
		return factory.mailerBuilder().withSMTPServer("localhost", smtp.getWiser().getServer().getPortAllocated())
				.withProperty("mail.from", "sender@example.org").withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1);
	}

	private Email email(final String id) {
		return ordinary.emailBuilder().startingBlank().from("sender@example.org").withRecipients(to(null, id + "@example.org"))
				.withPlainText("Synthetic test.").fixingMessageId("<" + id + "@example.org>").buildEmail();
	}
}
