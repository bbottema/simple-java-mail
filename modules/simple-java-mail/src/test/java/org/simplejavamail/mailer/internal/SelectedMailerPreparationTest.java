package org.simplejavamail.mailer.internal;

import jakarta.mail.Message.RecipientType;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Provider;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.config.ConfigLoader;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;



@Timeout(20)
class SelectedMailerPreparationTest {
    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

    @Test
	void invokingThePrimaryUsesTheSelectedFailoverMailersTemplatesWithoutPrimaryTemplateContamination() throws Exception {
		final UUID clusterKey = UUID.randomUUID();
		final List<Message> messagesSentByPrimary = new CopyOnWriteArrayList<>();
		final List<Message> messagesSentByFailoverMailer = new CopyOnWriteArrayList<>();
		try (Mailer primaryMailer = builder(clusterKey, messagesSentByPrimary).withEmailOverrides(template("primary")).buildMailer();
				 Mailer failoverMailer = builder(clusterKey, messagesSentByFailoverMailer).withEmailOverrides(template("failover")).buildMailer()) {
            final Email original = mail.emailBuilder().startingBlank().withRecipients(SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress("customer@example.org").withType(RecipientType.TO).build())
                    .withPlainText("Original content").buildEmail();
			primaryMailer.sync().sendMail(original);
			primaryMailer.sync().sendMail(original);
			assertThat(messagesSentByPrimary).hasSize(1);
			assertThat(messagesSentByFailoverMailer).hasSize(1);
			assertTemplate(messagesSentByPrimary.get(0), "primary");
			assertTemplate(messagesSentByFailoverMailer.get(0), "failover");
            assertThat(original.getFromRecipient()).isNull();
            assertThat(original.getHeaders()).isEmpty();
            assertThat(original.getRecipients()).hasSize(1);
        }
    }

    @Test
    @Timeout(30)
    void concurrentUseOfOneOriginalEmailDoesNotMixSelectedTemplates() throws Exception {
		final UUID clusterKey = UUID.randomUUID();
		final List<Message> messagesSentByPrimary = new CopyOnWriteArrayList<>();
		final List<Message> messagesSentByFailoverMailer = new CopyOnWriteArrayList<>();
        final CountDownLatch submitting = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean concurrentPhase = new AtomicBoolean();
        final Runnable waitForBoth = () -> {
            if (!concurrentPhase.get()) {
                return;
            }
            submitting.countDown();
            try {
                assertThat(release.await(10, SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError(interrupted);
            }
        };
		try (Mailer primaryMailer = builder(clusterKey, messagesSentByPrimary, waitForBoth).withThreadPoolSize(2).withConnectionPoolExpireAfterMillis(0)
						.withEmailOverrides(template("primary")).buildMailer();
				 Mailer failoverMailer = builder(clusterKey, messagesSentByFailoverMailer, waitForBoth).withConnectionPoolExpireAfterMillis(0)
						.withEmailOverrides(template("failover")).buildMailer()) {
            final Email original = mail.emailBuilder().startingBlank().withRecipients(SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress("customer@example.org").withType(RecipientType.TO).build())
                    .withPlainText("Original content").buildEmail();
            // Initialize both physical connections and MIME support before testing simultaneous ownership, not startup throughput.
			primaryMailer.sync().sendMail(original);
			primaryMailer.sync().sendMail(original);
			messagesSentByPrimary.clear();
			messagesSentByFailoverMailer.clear();
            concurrentPhase.set(true);
			final CompletableFuture<?> first = primaryMailer.async().sendMail(original).getCompletion();
			final CompletableFuture<?> second = primaryMailer.async().sendMail(original).getCompletion();
            try {
                assertThat(submitting.await(10, SECONDS)).isTrue();
            } finally {
                release.countDown();
            }
            CompletableFuture.allOf(first, second).get(5, SECONDS);
			assertThat(messagesSentByPrimary).hasSize(1);
			assertThat(messagesSentByFailoverMailer).hasSize(1);
			assertTemplate(messagesSentByPrimary.get(0), "primary");
			assertTemplate(messagesSentByFailoverMailer.get(0), "failover");
            assertThat(original.getFromRecipient()).isNull();
            assertThat(original.getHeaders()).isEmpty();
            assertThat(original.getRecipients()).hasSize(1);
        } finally {
            release.countDown();
        }
    }

    @Test
    void asyncPreparationWaitsForExecutorAndReportsFailureThere() throws Exception {
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final CountDownLatch occupied = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        executor.execute(() -> {
            occupied.countDown();
            try {
                release.await();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        assertThat(occupied.await(5, SECONDS)).isTrue();
        final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
        final List<Thread> observers = new CopyOnWriteArrayList<>();
        try (Mailer mailer = mail.mailerBuilder().withSMTPServer("localhost", 25).withTransportModeLoggingOnly(true)
                .withExecutorService(executor).withMailSendResultObserver(outcome -> {
                    outcomes.add(outcome);
                    observers.add(Thread.currentThread());
                }).buildMailer()) {
            final var send = mailer.async().sendMail(mail.emailBuilder().startingBlank().buildEmail());
            try {
                assertThat(send.getCompletion()).isNotDone();
                assertThat(outcomes).isEmpty();
            } finally {
                release.countDown();
            }
            final Throwable failure = send.getCompletion().handle((receipt, cause) -> cause).get(5, SECONDS);
            assertThat(failure).isNotNull();
            assertThat(outcomes).hasSize(1);
            assertThat(outcomes.get(0).getFailure()).containsSame(failure);
            assertThat(outcomes.get(0).getReadyAt()).isPresent();
            assertThat(outcomes.get(0).getStartedAt()).isPresent();
            assertThat(outcomes.get(0).getSubmissionReceipt()).isEmpty();
            assertThat(outcomes.get(0).getDiagnostics().orElseThrow().getPreparation().isFailureObservedHere()).isTrue();
            assertThat(observers.get(0)).isNotSameAs(Thread.currentThread());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private Email template(final String owner) {
        return mail.emailBuilder().startingBlank().from(owner + "@example.org").withHeader("X-Relay", owner)
                .withRecipients(SimpleJavaMail.fromDefaults().recipientBuilder().withName(null).withAddress(owner + "-maintainer@example.org").withType(RecipientType.BCC).build()).buildEmail();
    }

    private static void assertTemplate(final Message message, final String owner) throws MessagingException {
        assertThat(message.getFrom()).extracting(Object::toString).containsExactly(owner + "@example.org");
        assertThat(message.getHeader("X-Relay")).containsExactly(owner);
        assertThat(message.getAllRecipients()).extracting(Object::toString)
                .containsExactly("customer@example.org", owner + "-maintainer@example.org");
    }

    private MailerFromSessionBuilderImpl builder(final UUID cluster, final List<Message> sent) throws Exception {
        return builder(cluster, sent, () -> { });
    }

    private MailerFromSessionBuilderImpl builder(final UUID cluster, final List<Message> sent, final Runnable beforeSend) throws Exception {
        final Properties properties = new Properties();
        properties.setProperty("mail.transport.protocol", "smtp");
        properties.setProperty("mail.smtp.host", "localhost");
        properties.setProperty("mail.smtp.port", "25");
        properties.put("test.sent", sent);
        properties.put("test.before-send", beforeSend);
        final Session session = Session.getInstance(properties);
        final Provider provider = new Provider(Provider.Type.TRANSPORT, "smtp", RecordingTransport.class.getName(), "test", "1");
        session.setProvider(provider);
        return (MailerFromSessionBuilderImpl) mail.mailerBuilder(session).withClusterKey(cluster).withConnectionPoolCoreSize(0)
                .withConnectionPoolMaxSize(1).withConnectionPoolLoadBalancingStrategy(LoadBalancingStrategy.ROUND_ROBIN);
    }

    public static final class RecordingTransport extends Transport {
        private final List<Message> sent;
        private final Runnable beforeSend;

        @SuppressWarnings("unchecked")
        public RecordingTransport(final Session session, final URLName name) {
            super(session, name);
            sent = (List<Message>) session.getProperties().get("test.sent");
            beforeSend = (Runnable) session.getProperties().get("test.before-send");
        }

        @Override
        protected boolean protocolConnect(final String host, final int port, final String user, final String password) {
            return true;
        }

        @Override
        public void sendMessage(final Message message, final Address[] recipients) throws MessagingException {
            beforeSend.run();
            assertThat(Arrays.asList(recipients)).containsExactly(message.getAllRecipients());
            sent.add(message);
        }
    }
}
