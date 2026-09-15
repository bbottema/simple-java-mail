package org.simplejavamail.mailer;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.MailSend;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static jakarta.mail.Message.RecipientType.TO;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Timeout(30)
class MailerExecutionViewsTest {

	@Test
	void cachedViewsStartNoWorkersOrConnectionsAndSurviveAsReferencesAfterShutdown() throws Exception {
		final RecordingMailer transport = new RecordingMailer();
		final Mailer mailer = builder(transport).buildMailer();
		final Mailer.Sync sync = mailer.sync();
		final Mailer.Async async = mailer.async();
		final ThreadPoolExecutor executor = (ThreadPoolExecutor) mailer.getOperationalConfig().getExecutorService();
		try {
			for (int access = 0; access < 100; access++) {
				assertThat(mailer.sync()).isSameAs(sync);
				assertThat(mailer.async()).isSameAs(async);
			}
			assertThat(executor.getPoolSize()).isZero();
			assertThat(executor.getTaskCount()).isZero();
			assertThat(transport.sessions).isEmpty();
		} finally {
			mailer.close();
		}
		assertThat(mailer.sync()).isSameAs(sync);
		assertThat(mailer.async()).isSameAs(async);
		assertThatThrownBy(() -> sync.sendMail(email("closed"))).isInstanceOf(RejectedExecutionException.class);
		assertThatThrownBy(() -> async.sendMail(email("closed")).getCompletion().get(5, SECONDS))
				.isInstanceOf(ExecutionException.class).hasCauseInstanceOf(RejectedExecutionException.class);
		final AtomicInteger iteratorCalls = new AtomicInteger();
		final Iterable<Email> untouched = () -> {
			iteratorCalls.incrementAndGet();
			return List.of(email("untouched")).iterator();
		};
		assertThatThrownBy(() -> sync.sendMailsInSimpleBatch(untouched)).isInstanceOf(RejectedExecutionException.class);
		assertThatThrownBy(() -> async.sendMailsInSimpleBatch(untouched).getCompletion().get(5, SECONDS))
				.isInstanceOf(ExecutionException.class).hasCauseInstanceOf(RejectedExecutionException.class);
		assertThatThrownBy(sync::probeConnection).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> async.probeConnection().get(5, SECONDS)).isInstanceOf(ExecutionException.class);
		assertThat(iteratorCalls).hasValue(0);
		assertThat(transport.sessions).isEmpty();
	}

	@Test
	void simultaneousModesAndAllOperationFamiliesShareTheSameOwner() throws Exception {
		final RecordingMailer transport = new RecordingMailer();
		final List<MailSendOutcome> outcomes = new CopyOnWriteArrayList<>();
		try (Mailer mailer = builder(transport).withMailSendObserver(outcomes::add).buildMailer()) {
			transport.owner.set(mailer);
			final Mailer.Sync sync = mailer.sync();
			final Mailer.Async async = mailer.async();
			final MailSend<MailSubmissionReceipt> background = async.sendMail(email("blocked"));
			try {
				assertThat(transport.started.await(5, SECONDS)).isTrue();
				final MailSubmissionReceipt foreground = sync.sendMail(email("foreground"));
				assertThat(background.getCompletion()).isNotDone();
				assertThat(outcomes).hasSize(1);
				assertThat(outcomes.get(0).getSubmissionReceipt()).containsSame(foreground);
			} finally {
				transport.release.countDown();
			}
			final MailSubmissionReceipt backgroundReceipt = background.getCompletion().get(5, SECONDS);
			assertThat(outcomes.get(1).getSubmissionReceipt()).containsSame(backgroundReceipt);
			sync.sendMailsInSimpleBatch(List.of(email("sync batch 1"), email("sync batch 2")));
			assertThat(async.sendMailsInSimpleBatch(List.of(email("async batch 1"), email("async batch 2")))
					.getCompletion().get(5, SECONDS)).isNull();
			assertThat(outcomes).hasSize(6);
			sync.testConnection();
			assertThat(transport.synchronousTestOwnerLock).hasValue(true);
			async.testConnection().get(5, SECONDS);
			assertThat(sync.probeConnection().isSupported()).isFalse();
			assertThat(sync.probeConnection(true).isSupported()).isFalse();
			assertThat(async.probeConnection().get(5, SECONDS).isSupported()).isFalse();
			assertThat(async.probeConnection(true).get(5, SECONDS).isSupported()).isFalse();
			assertThat(outcomes).hasSize(6); // tests/probes are not email attempts
			assertThat(transport.sessions).hasSize(8).allSatisfy(session -> assertThat(session).isSameAs(mailer.getSession()));
			assertThat(transport.configurations).allSatisfy(config -> assertThat(config).isSameAs(mailer.getOperationalConfig()));
			assertThat(transport.sendThreads.get(0)).isNotSameAs(Thread.currentThread());
			assertThat(transport.sendThreads.get(1)).isSameAs(Thread.currentThread());
		}
	}

	private static MailerRegularBuilder<?> builder(final CustomMailer transport) {
		return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder()
				.withSMTPServer("localhost", 25).withThreadPoolSize(1).withConnectionPoolCoreSize(0).withCustomMailer(transport);
	}

	private static Email email(final String subject) {
		return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
				.from("sender@example.org").withRecipients(EmailHelper.parsedRecipients(null, false, TO, "receiver@example.org"))
				.withSubject(subject).withPlainText("Execution views test").buildEmail();
	}

	private static final class RecordingMailer implements CustomMailer {
		private final List<Session> sessions = new CopyOnWriteArrayList<>();
		private final List<OperationalConfig> configurations = new CopyOnWriteArrayList<>();
		private final List<Thread> sendThreads = new CopyOnWriteArrayList<>();
		private final AtomicReference<Mailer> owner = new AtomicReference<>();
		private final AtomicReference<Boolean> synchronousTestOwnerLock = new AtomicReference<>();
		private final CountDownLatch started = new CountDownLatch(1);
		private final CountDownLatch release = new CountDownLatch(1);

		@Override
		public void testConnection(final OperationalConfig config, final Session session) {
			sessions.add(session);
			configurations.add(config);
			synchronousTestOwnerLock.compareAndSet(null, Thread.holdsLock(owner.get()));
		}

		@Override
		public void sendMessage(final OperationalConfig config, final Session session, final Email email, final MimeMessage message) {
			sessions.add(session);
			configurations.add(config);
			sendThreads.add(Thread.currentThread());
			if (email.getSubject().equals("blocked")) {
				started.countDown();
				try {
					assertThat(release.await(10, SECONDS)).isTrue();
				} catch (InterruptedException failure) {
					Thread.currentThread().interrupt();
					throw new AssertionError(failure);
				}
			}
		}
	}
}
