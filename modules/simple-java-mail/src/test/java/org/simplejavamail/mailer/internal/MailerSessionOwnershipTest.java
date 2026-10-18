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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedStatic;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.BatchModule;

import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;


/** Public construction/send regressions: an accepted Mailer must keep its Session's governance until it has drained. */
@Timeout(30)
class MailerSessionOwnershipTest {

	private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void rejectedSecondOwnerLeavesPooledSyncAndAsyncSendingUnchanged(final boolean asynchronous) throws Exception {
		final State state = new State();
		final Session session = session(new Properties(), state);
		try (Mailer owner = builder(session, "owner-a").buildMailer()) {
			send(owner, asynchronous);
			final Properties before = (Properties) session.getProperties().clone();
			for (int attempt = 0; attempt < 2; attempt++) {
				assertThatThrownBy(() -> builder(session, "owner-b").withDebugLogging(true)
						.withProxy("unwanted-proxy.example.test", 1080).withProperty("mail.smtp.host", "unwanted.example.test").buildMailer())
						.isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already belongs to another Mailer")
						.hasMessageContaining("Properties object").hasMessageContaining("defaults, overrides and connection settings")
						.hasMessageContaining("Reuse the existing Mailer").hasMessageContaining("independent Properties");
				assertThat(session.getProperties()).isEqualTo(before);
				assertThat(session.getDebug()).isFalse();
			}
			send(owner, asynchronous);
			assertThat(state.senders).containsExactly("owner-a@example.test", "owner-a@example.test");
			assertThat(state.headers).containsExactly("owner-a", "owner-a");
		}
	}

	@Test
	void differentSessionsCannotShareAnOwnedPropertiesObject() throws Exception {
		final Properties properties = new Properties();
		final State state = new State();
		final Session first = session(properties, state);
		final Session second = session(properties, state);
		assertThat(first).isNotSameAs(second);
		assertThat(first.getProperties()).isSameAs(second.getProperties());
		try (Mailer owner = builder(first, "owner-a").buildMailer()) {
			final Properties before = (Properties) properties.clone();
			assertThatThrownBy(() -> builder(second, "owner-b").buildMailer()).isInstanceOf(IllegalArgumentException.class);
			assertThat(properties).isEqualTo(before);
			owner.sync().sendMail(email());
			assertThat(state.senders).containsExactly("owner-a@example.test");
		}
	}

	@Test
	void sessionOwnershipAlsoProtectsDirectSendingWithoutTheBatchModule() throws Exception {
		final State state = new State();
		final Session session = session(new Properties(), state);
		try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
			modules.when(ModuleLoader::batchModuleAvailable).thenReturn(false);
			try (Mailer owner = builder(session, "direct-owner").buildMailer()) {
				owner.sync().sendMail(email());
				assertThatThrownBy(() -> builder(session, "other").buildMailer()).isInstanceOf(IllegalArgumentException.class);
				owner.sync().sendMail(email());
				assertThat(state.senders).containsExactly("direct-owner@example.test", "direct-owner@example.test");
			}
		}
	}

	@Test
	void libraryCreatedSessionsCannotBeClaimedByAnotherMailerEither() throws Exception {
		try (Mailer owner = mail.mailerBuilder().withSMTPServer("localhost", 25).withTransportModeLoggingOnly(true).buildMailer()) {
			final Properties before = (Properties) owner.getSession().getProperties().clone();
			assertThatThrownBy(() -> builder(owner.getSession(), "other").buildMailer()).isInstanceOf(IllegalArgumentException.class);
			assertThat(owner.getSession().getProperties()).isEqualTo(before);
		}
	}

	@Test
	void independentSessionsKeepTheirOwnTemplatesEvenWithTheSameSmtpSettings() throws Exception {
		final State firstState = new State();
		final State secondState = new State();
		try (Mailer first = builder(session(new Properties(), firstState), "owner-a").buildMailer();
			 Mailer second = builder(session(new Properties(), secondState), "owner-b").buildMailer()) {
			first.sync().sendMail(email());
			second.sync().sendMail(email());
			first.async().sendMail(email()).getCompletion().get(5, SECONDS);
			assertThat(firstState.senders).containsExactly("owner-a@example.test", "owner-a@example.test");
			assertThat(secondState.senders).containsExactly("owner-b@example.test");
		}
	}

	@Test
	void constructionClaimsOwnershipBeforeConfiguringTheSession() throws Exception {
		final BuildProperties properties = new BuildProperties();
		final Session session = session(properties, new State());
		final CountDownLatch configuring = new CountDownLatch(1);
		final CountDownLatch finish = new CountDownLatch(1);
		properties.afterBuilderProperties = () -> {
			configuring.countDown();
			await(finish);
		};
		final ExecutorService constructing = Executors.newSingleThreadExecutor();
		final Future<Mailer> first = constructing.submit(() -> builder(session, "owner-a").withProperty("test.build", "first").buildMailer());
		try {
			assertThat(configuring.await(5, SECONDS)).isTrue();
			assertThatThrownBy(() -> builder(session, "owner-b").buildMailer()).isInstanceOf(IllegalArgumentException.class);
		} finally {
			finish.countDown();
			try (Mailer owner = first.get(5, SECONDS)) {
				owner.sync().sendMail(email());
			}
			constructing.shutdownNow();
		}
	}

	@Test
	void failedFirstConstructionReleasesOnlyItsOwnOwnershipAndConverter() throws Exception {
		final BuildProperties properties = new BuildProperties();
		final State state = new State();
		final Session session = session(properties, state);
		final IllegalStateException failure = new IllegalStateException("test configuration failure");
		properties.afterBuilderProperties = () -> { throw failure; };
		assertThatThrownBy(() -> builder(session, "failed").withProperty("test.build", "fail").buildMailer()).isSameAs(failure);
		properties.afterBuilderProperties = () -> { };
		try (Mailer replacement = builder(session, "replacement").buildMailer()) {
			replacement.sync().sendMail(email());
			assertThat(state.senders).containsExactly("replacement@example.test");
		}
	}

	@Test
	void completedShutdownAllowsReusingTheSessionWithNewGovernance() throws Exception {
		final State state = new State();
		final Session session = session(new Properties(), state);
		try (Mailer first = builder(session, "owner-a").buildMailer()) {
			first.sync().sendMail(email());
		}
		try (Mailer replacement = builder(session, "owner-b").buildMailer()) {
			replacement.async().sendMail(email()).getCompletion().get(5, SECONDS);
			assertThat(state.senders).containsExactly("owner-a@example.test", "owner-b@example.test");
		}
	}

	@Test
	void drainingShutdownKeepsOwnershipUntilAcceptedSendsFinish() throws Exception {
		final State state = new State();
		final CountDownLatch submitting = new CountDownLatch(1);
		final CountDownLatch finish = new CountDownLatch(1);
		final Session session = session(new Properties(), state);
		try (Mailer first = builder(session, "owner-a").buildMailer()) {
			// Initialize provider/MIME support before testing the shutdown interleaving, not cold-start duration.
			first.sync().sendMail(email());
			state.senders.clear();
			state.headers.clear();
			state.beforeSend = () -> { submitting.countDown(); await(finish); };
			final CompletableFuture<?> send = first.async().sendMail(email()).getCompletion();
			assertThat(submitting.await(5, SECONDS)).isTrue();
			final Future<Void> shutdown = first.closeAsync();
			try {
				assertThat(shutdown.isDone()).isFalse();
				assertThatThrownBy(() -> builder(session, "owner-b").buildMailer()).isInstanceOf(IllegalArgumentException.class);
			} finally {
				finish.countDown();
			}
			send.get(5, SECONDS);
			shutdown.get(5, SECONDS);
			state.beforeSend = () -> { };
			try (Mailer replacement = builder(session, "owner-b").buildMailer()) {
				first.close();
				replacement.sync().sendMail(email());
			}
			assertThat(state.senders).containsExactly("owner-a@example.test", "owner-b@example.test");
		} finally {
			finish.countDown();
		}
	}

	@Test
	void failedPoolRegistrationReleasesOwnershipAfterSuccessfulRollback() throws Exception {
		final Session session = session(new Properties(), new State());
		final IllegalStateException failure = new IllegalStateException("test pool registration failure");
		try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
			final BatchModule batch = mock(BatchModule.class);
			modules.when(ModuleLoader::loadBatchModule).thenReturn(batch);
			doThrow(failure).when(batch).registerToCluster(any(), any(), any(), any(), any());
			when(batch.shutdownConnectionPools(session)).thenReturn(CompletableFuture.completedFuture(null));
			assertThatThrownBy(() -> builder(session, "failed").buildMailer()).isSameAs(failure);
		}
		try (Mailer replacement = builder(session, "replacement").buildMailer()) {
			replacement.sync().sendMail(email());
		}
	}

	@Test
	void unresolvedPoolCleanupDoesNotAllowAnotherOwnerToTakeOver() throws Exception {
		final Session session = session(new Properties(), new State());
		final IllegalStateException failure = new IllegalStateException("test pool registration failure");
		final IllegalStateException cleanup = new IllegalStateException("test unresolved pool cleanup");
		try (MockedStatic<ModuleLoader> modules = mockStatic(ModuleLoader.class, CALLS_REAL_METHODS)) {
			final BatchModule batch = mock(BatchModule.class);
			modules.when(ModuleLoader::loadBatchModule).thenReturn(batch);
			doThrow(failure).when(batch).registerToCluster(any(), any(), any(), any(), any());
			when(batch.shutdownConnectionPools(session)).thenReturn(CompletableFuture.failedFuture(cleanup));
			assertThatThrownBy(() -> builder(session, "failed").buildMailer()).isSameAs(failure);
			assertThat(failure.getSuppressed()).hasSize(1);
			assertThat(failure.getSuppressed()[0]).hasCause(cleanup);
			assertThatThrownBy(() -> builder(session, "replacement").buildMailer()).isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("already belongs to another Mailer");
		}
	}

	private MailerFromSessionBuilderImpl builder(final Session session, final String owner) {
		return (MailerFromSessionBuilderImpl) SimpleJavaMail.withConfig(ConfigLoader.builder().load()).mailerBuilder(session)
				.withEmailOverrides(mail.emailBuilder().startingBlank().from(owner + "@example.test").withHeader("X-Owner", owner).buildEmail())
				.withClusterKey(UUID.randomUUID()).withConnectionPoolCoreSize(0).withConnectionPoolMaxSize(1)
				// This fixture verifies ownership, not wall-clock expiry.
				.withConnectionPoolExpireAfterMillis(0);
	}

	private Email email() {
		// Ownership tests must not wait for host DNS while Jakarta Mail generates a Message-ID.
		return mail.emailBuilder().startingBlank().fixingMessageId("<ownership-test@example.test>")
				.withRecipients(mail.recipientBuilder().withAddress("recipient@example.test").withType(RecipientType.TO).build())
				.withPlainText("ownership test").buildEmail();
	}

	private void send(final Mailer mailer, final boolean asynchronous) throws Exception {
		if (asynchronous) {
			mailer.async().sendMail(email()).getCompletion().get(5, SECONDS);
		} else {
			mailer.sync().sendMail(email());
		}
	}

	private static Session session(final Properties properties, final State state) throws Exception {
		properties.setProperty("mail.transport.protocol", "smtp");
		properties.setProperty("mail.smtp.host", "localhost");
		properties.setProperty("mail.smtp.port", "25");
		properties.put(State.class.getName(), state);
		final Session session = Session.getInstance(properties);
		session.setProvider(new Provider(Provider.Type.TRANSPORT, "smtp", RecordingTransport.class.getName(), "test", "1"));
		return session;
	}

	private static void await(final CountDownLatch latch) {
		try {
			assertThat(latch.await(10, SECONDS)).isTrue();
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new AssertionError(interrupted);
		}
	}

	private static final class BuildProperties extends Properties {
		private Runnable afterBuilderProperties = () -> { };

		@Override
		public void putAll(final Map<?, ?> values) {
			super.putAll(values);
			// Pause after an actual builder property is applied, outside the Properties monitor.
			if (values.containsKey("test.build")) {
				afterBuilderProperties.run();
			}
		}
	}

	private static final class State {
		private final List<String> senders = new CopyOnWriteArrayList<>();
		private final List<String> headers = new CopyOnWriteArrayList<>();
		private Runnable beforeSend = () -> { };
	}

	public static final class RecordingTransport extends Transport {
		private final State state;

		public RecordingTransport(final Session session, final URLName name) {
			super(session, name);
			state = (State) session.getProperties().get(State.class.getName());
		}

		@Override
		protected boolean protocolConnect(final String host, final int port, final String user, final String password) {
			return true;
		}

		@Override
		public void sendMessage(final Message message, final Address[] recipients) throws MessagingException {
			state.beforeSend.run();
			state.senders.add(message.getFrom()[0].toString());
			state.headers.add(message.getHeader("X-Owner")[0]);
		}
	}
}
