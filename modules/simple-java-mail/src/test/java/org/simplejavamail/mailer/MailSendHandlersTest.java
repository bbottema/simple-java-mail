package org.simplejavamail.mailer;

import org.simplejavamail.api.mailer.config.AsyncExecutionConfig;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.api.mailer.config.MailSendRetryPolicy;
import testutil.ConfigLoaderTestHelper;
import testutil.retry.RetryProviderTransport;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.simplejavamail.api.mailer.MailSendRejectedException;
import java.util.concurrent.atomic.AtomicInteger;

import static testutil.MailerTestConfig.updateMailer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class MailSendHandlersTest {

	@Test
	void repeatedEmailGetsOneIdentityAndFactoryCallPerSendInFixedThenGeneratedOrder() throws Exception {
		final List<UUID> generatedIds = new ArrayList<>();
		final List<String> calls = new CopyOnWriteArrayList<>();
		final AtomicInteger factoryCalls = new AtomicInteger();
		final Thread initiatingThread = Thread.currentThread();
		try (Mailer mailer = builder().withSendIdGenerator(() -> {
			assertThat(Thread.currentThread()).isSameAs(initiatingThread);
			final UUID id = UUID.randomUUID();
			generatedIds.add(id);
			return id;
		}).withSendHandlers(MailSendHandlers.builder().withResultObserver(outcome -> {
			calls.add("fixed:" + outcome.getObservationId());
			throw new IllegalStateException("isolated fixed observer");
		}).build()).withSendHandlersFactory(id -> {
			assertThat(Thread.currentThread()).isSameAs(initiatingThread);
			factoryCalls.incrementAndGet();
			return MailSendHandlers.builder().withResultObserver(outcome -> {
				assertThat(outcome.getObservationId()).isEqualTo(id);
				calls.add("generated:" + id);
			}).build();
		}).buildMailer()) {
			final Email email = email();
			mailer.sync().sendMail(email);
			mailer.async().sendMail(email).getCompletion().join();
		}
		assertThat(factoryCalls).hasValue(2);
		assertThat(generatedIds).doesNotHaveDuplicates().hasSize(2);
		assertThat(calls).containsExactly("fixed:" + generatedIds.get(0), "generated:" + generatedIds.get(0),
				"fixed:" + generatedIds.get(1), "generated:" + generatedIds.get(1));
	}

	@Test
	void factoryFailureIsTheOriginalFailureAndNotifiesFixedHandlersBeforeAsyncCompletion() throws Exception {
		final RuntimeException injected = new IllegalArgumentException("factory failed");
		final List<MailSendOutcome> outcomes = new ArrayList<>();
		final UUID id = UUID.randomUUID();
		try (Mailer mailer = builder().withSendIdGenerator(() -> id)
				.withSendHandlers(MailSendHandlers.builder().withResultObserver(outcomes::add).build())
				.withSendHandlersFactory(ignored -> { throw injected; }).buildMailer()) {
			final Throwable failure = mailer.async().sendMail(email()).getCompletion().handle((receipt, cause) -> cause).join();
			assertThat(failure).isSameAs(injected);
			assertThat(outcomes).hasSize(1);
			assertThat(outcomes.get(0).getObservationId()).isEqualTo(id);
			assertThat(outcomes.get(0).getFailure()).containsSame(injected);
			assertThat(outcomes.get(0).getSubmissionReceipt()).isEmpty();
		}
	}

	@Test
	void generatorFailureHasNoOutcomeAndAsyncReturnsAFailedHandle() throws Exception {
		final RuntimeException injected = new IllegalArgumentException("identity failed");
		final AtomicInteger factories = new AtomicInteger();
		final AtomicInteger results = new AtomicInteger();
		try (Mailer mailer = builder().withSendIdGenerator(() -> { throw injected; })
				.withSendHandlers(MailSendHandlers.builder().withResultObserver(outcome -> results.incrementAndGet()).build())
				.withSendHandlersFactory(id -> { factories.incrementAndGet(); return MailSendHandlers.builder().build(); }).buildMailer()) {
			assertThat(catchThrowable(() -> mailer.sync().sendMail(email()))).isSameAs(injected);
			assertThat(mailer.async().sendMail(email()).getCompletion().handle((receipt, failure) -> failure).join()).isSameAs(injected);
		}
		assertThat(factories).hasValue(0);
		assertThat(results).hasValue(0);
	}

	@Test
	void lazyBatchStopsAtFactoryFailureWithoutInitializingUntouchedEmails() throws Exception {
		final AtomicInteger factories = new AtomicInteger();
		final List<MailSendOutcome> results = new ArrayList<>();
		final RuntimeException injected = new IllegalStateException("second email setup");
		try (Mailer mailer = builder().withSendHandlers(MailSendHandlers.builder().withResultObserver(results::add).build())
				.withSendHandlersFactory(id -> {
					if (factories.incrementAndGet() == 2) { throw injected; }
					return MailSendHandlers.builder().build();
				}).buildMailer()) {
			assertThat(catchThrowable(() -> mailer.sync().sendMailsInSimpleBatch(Arrays.asList(email(), email(), email())))).isSameAs(injected);
		}
		assertThat(factories).hasValue(2);
		assertThat(results).hasSize(2);
		assertThat(results.get(0).isSuccessful()).isTrue();
		assertThat(results.get(1).getFailure()).containsSame(injected);
	}

	private static MailerRegularBuilder<?> builder() {
		return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder().withTransportModeLoggingOnly(true);
	}

	@Test
	void nullHookResultsAbortAndRehearsalDoesNotInitializeHandlers() throws Exception {
		final AtomicInteger factories = new AtomicInteger();
		final List<MailSendOutcome> results = new ArrayList<>();
		try (Mailer mailer = builder().withSendHandlers(MailSendHandlers.builder().withResultObserver(results::add).build())
				.withSendHandlersFactory(id -> { factories.incrementAndGet(); return null; }).buildMailer()) {
			mailer.rehearse(email());
			assertThat(factories).hasValue(0);
			assertThat(catchThrowable(() -> mailer.sync().sendMail(email()))).isInstanceOf(NullPointerException.class);
			assertThat(results).hasSize(1);
			assertThat(results.get(0).getObservationId()).isNotNull();
			assertThat(results.get(0).getSubmissionReceipt()).isEmpty();
		}
		try (Mailer mailer = builder().withSendIdGenerator(() -> null).withSendHandlersFactory(id -> {
			throw new AssertionError("factory must not run without ID");
		}).buildMailer()) {
			assertThat(mailer.async().sendMail(email()).getCompletion().handle((receipt, failure) -> failure).join())
					.isInstanceOf(NullPointerException.class);
		}
	}

	@Test
	void automaticRetriesRetainTheOriginalIdentityAndGeneratedHandlers() throws Exception {
		final RetryProviderTransport.State state = new RetryProviderTransport.State();
		final AtomicInteger factories = new AtomicInteger();
		final AtomicInteger identities = new AtomicInteger();
		final List<MailSendOutcome> results = new CopyOnWriteArrayList<>();
		final UUID id = UUID.randomUUID();
		try (Mailer mailer = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).mailerBuilder(RetryProviderTransport.session(state))
				.withSendIdGenerator(() -> { identities.incrementAndGet(); return id; })
				.withSendHandlersFactory(observation -> {
					assertThat(observation).isEqualTo(id); factories.incrementAndGet();
					return MailSendHandlers.builder().withResultObserver(results::add).build();
				}).withMailSendRetryPolicy(MailSendRetryPolicy.builder().withMaximumAttempts(3).withRetryDelay(Duration.ofMillis(1)).build())
				.buildMailer()) {
			mailer.async().sendMail(email()).getCompletion().join();
		}
		assertThat(state.invocations).hasValue(2);
		assertThat(factories).hasValue(1);
		assertThat(identities).hasValue(1);
		assertThat(results).hasSize(1);
		assertThat(results.get(0).getObservationId()).isEqualTo(id);
	}

	@Test
	void admissionRejectionRetainsTheSingleInitializedIdentityAndHandlers() throws Exception {
		final ExecutorService rejectedExecutor = Executors.newSingleThreadExecutor();
		rejectedExecutor.shutdown();
		final AtomicInteger factories = new AtomicInteger();
		final List<MailSendOutcome> results = new ArrayList<>();
		try (Mailer mailer = updateMailer(builder(), fixture -> fixture.withAsyncExecutionConfig(fixture.getAsyncExecutionConfig().toBuilder().withExecutorService(rejectedExecutor).build())).withSendHandlersFactory(id -> {
			factories.incrementAndGet();
			return MailSendHandlers.builder().withResultObserver(results::add).build();
		}).buildMailer()) {
			final Throwable failure = mailer.async().sendMail(email()).getCompletion().handle((receipt, cause) -> cause).join();
			assertThat(failure).isInstanceOf(MailSendRejectedException.class);
			assertThat(results).hasSize(1);
			assertThat(results.get(0).getFailure()).containsSame(failure);
			assertThat(results.get(0).getStartedAt()).isNull();
			assertThat(factories).hasValue(1);
		}
	}

	@Test
	void concurrentSendsHaveIndependentGeneratedCallbacksForTheSameEmail() throws Exception {
		final ExecutorService callers = Executors.newFixedThreadPool(4);
		final List<UUID> results = new CopyOnWriteArrayList<>();
		final AtomicInteger factories = new AtomicInteger();
		try (Mailer mailer = builder().withSendHandlersFactory(id -> {
			factories.incrementAndGet();
			return MailSendHandlers.builder().withResultObserver(outcome -> {
				assertThat(outcome.getObservationId()).isEqualTo(id);
				results.add(id);
			}).build();
		}).buildMailer()) {
			final Email shared = email();
			final List<CompletableFuture<?>> sends = new ArrayList<>();
			for (int i = 0; i < 12; i++) { sends.add(CompletableFuture.runAsync(() -> mailer.sync().sendMail(shared), callers)); }
			CompletableFuture.allOf(sends.toArray(new CompletableFuture<?>[0])).join();
		} finally { callers.shutdownNow(); }
		assertThat(factories).hasValue(12);
		assertThat(results).hasSize(12).doesNotHaveDuplicates();
	}

	private static Email email() {
		return SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig()).emailBuilder().startingBlank()
				.from("from@example.org").to("to@example.org").withPlainText("handler scope").buildEmail();
	}
}
