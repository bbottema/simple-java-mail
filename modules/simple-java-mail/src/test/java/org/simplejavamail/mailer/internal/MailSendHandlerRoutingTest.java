package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.mailer.MailSendProgress;
import org.simplejavamail.api.mailer.MailSendProgressStage;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.internal.telemetry.MailSendTelemetryBridge;

import java.lang.reflect.Field;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.simplejavamail.mailer.internal.MailSendProgressDispatcherTest.take;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MailSendHandlerRoutingTest {
	@Test
	void factoryFailureHasTelemetryWithoutCallbacksWhileGeneratorFailureHasNoIdentity() {
		final UUID id = UUID.randomUUID();
		final IllegalStateException failure = new IllegalStateException("initialization failed");
		final MailSendTelemetryBridge.Runtime runtime = mock(MailSendTelemetryBridge.Runtime.class);
		final MailSendTelemetryBridge.CapturedContext context = mock(MailSendTelemetryBridge.CapturedContext.class);
		final MailSendTelemetryBridge.Attempt telemetry = mock(MailSendTelemetryBridge.Attempt.class);
		when(runtime.captureContext()).thenReturn(context);
		when(context.beginSend(id)).thenReturn(telemetry);
		final MailSendObserverNotifier factoryFails = MailSendObserverNotifier.builder().telemetry(runtime).idGenerator(() -> id)
				.handlersFactory(observation -> { throw failure; }).build();
		final MailSendObserverNotifier generatorFails = MailSendObserverNotifier.builder().telemetry(runtime)
				.idGenerator(() -> { throw failure; }).build();
		try {
			assertThat(catchThrowable(() -> factoryFails.beginAttempt(mock(Email.class)))).isSameAs(failure);
			verify(context).beginSend(id);
			verify(telemetry).complete(argThat(report -> !report.isSuccessful() && report.getFailure() == failure && report.getSubmissionStatus() == null));
			assertThat(catchThrowable(() -> generatorFails.beginAttempt(mock(Email.class)))).isSameAs(failure);
			verify(runtime, times(1)).captureContext();
		} finally {
			factoryFails.closeProgress();
			generatorFails.closeProgress();
		}
	}

	@Test
	void completedProgressOnlyAttemptReleasesGeneratedCallbacksWhilePendingDeliveryRemainsBounded() throws Exception {
		final BlockingQueue<Runnable> executor = new LinkedBlockingQueue<>();
		final MailSendObserverNotifier notifier = MailSendObserverNotifier.builder().handlersFactory(id ->
				MailSendHandlers.builder().withProgressObserver(progress -> { }, executor::add).build()).build();
		try {
			final MailSendAttempt attempt = notifier.beginAttempt(mock(Email.class));
			attempt.readyForAdmission();
			attempt.preparationStarted();
			final Runnable pendingCallback = take(executor);
			attempt.completeSuccessfully(mock(MailSubmissionReceipt.class));
			assertThat(field(attempt, "generatedHandlers")).isNull();
			assertThat(field(field(attempt, "progress"), "targets")).isNull();
			assertThat(field(pendingCallback, "progress")).isNotNull();
			notifier.closeProgress();
			assertThat(field(pendingCallback, "progress")).isNull();
		} finally { notifier.closeProgress(); }
	}

	private static Object field(final Object owner, final String name) throws Exception {
		final Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(owner);
	}

	@Test
	void fixedThenGeneratedProgressUsesEachExecutorAndIsolatesCallbackFailure() throws Exception {
		final BlockingQueue<Runnable> fixedExecutor = new LinkedBlockingQueue<>();
		final BlockingQueue<Runnable> generatedExecutor = new LinkedBlockingQueue<>();
		final List<String> calls = new ArrayList<>();
		final MailSendHandlers fixed = MailSendHandlers.builder().withProgressObserver(progress -> {
			calls.add("fixed"); throw new IllegalStateException("fixed observer failed");
		}, fixedExecutor::add).build();
		final MailSendHandlers generated = MailSendHandlers.builder().withProgressObserver(progress -> calls.add("generated"), generatedExecutor::add).build();
		try (MailSendProgressDispatcher dispatcher = new MailSendProgressDispatcher()) {
			dispatcher.offer(snapshot(UUID.randomUUID()), MailSendProgressDispatcher.targets(fixed, generated));
			final Runnable first = take(fixedExecutor);
			assertThat(generatedExecutor).isEmpty();
			first.run();
			final Runnable second = take(generatedExecutor);
			first.run();
			assertThat(calls).containsExactly("fixed");
			second.run();
			assertThat(calls).containsExactly("fixed", "generated");
			for (final Runnable callback : new Runnable[] {first, second}) {
				for (final String name : new String[] {"owner", "progress"}) {
					final Field field = callback.getClass().getDeclaredField(name); field.setAccessible(true);
					assertThat(field.get(callback)).isNull();
				}
			}
		}
	}

	@Test
	void rejectedFixedProgressStillHandsOffGeneratedProgress() throws Exception {
		final BlockingQueue<Runnable> generatedExecutor = new LinkedBlockingQueue<>();
		final List<UUID> delivered = new ArrayList<>();
		final MailSendHandlers fixed = MailSendHandlers.builder().withProgressObserver(progress -> {
			throw new AssertionError("rejected callback must not run");
		}, task -> { throw new RejectedExecutionException("fixed full"); }).build();
		final MailSendHandlers generated = MailSendHandlers.builder()
				.withProgressObserver(progress -> delivered.add(progress.getObservationId()), generatedExecutor::add).build();
		final UUID id = UUID.randomUUID();
		try (MailSendProgressDispatcher dispatcher = new MailSendProgressDispatcher()) {
			dispatcher.offer(snapshot(id), MailSendProgressDispatcher.targets(fixed, generated));
			take(generatedExecutor).run();
			assertThat(delivered).containsExactly(id);
		}
	}

	@Test
	void generatedProgressRetentionRemainsBoundedAndCloseDetachesQueuedHandlers() throws Exception {
		final BlockingQueue<Runnable> executor = new LinkedBlockingQueue<>();
		final MailSendHandlers handlers = MailSendHandlers.builder().withProgressObserver(progress -> { }, executor::add).build();
		final MailSendProgressDispatcher dispatcher = new MailSendProgressDispatcher();
		dispatcher.offer(snapshot(UUID.randomUUID()), MailSendProgressDispatcher.targets(null, handlers));
		final Runnable retainedTask = take(executor);
		for (int i = 0; i < MailSendProgressDispatcher.MAX_PENDING_IDS + 20; i++) {
			dispatcher.offer(snapshot(UUID.randomUUID()), MailSendProgressDispatcher.targets(null, handlers));
		}
		final Field pending = MailSendProgressDispatcher.class.getDeclaredField("pending"); pending.setAccessible(true);
		assertThat((Map<?, ?>) pending.get(dispatcher)).hasSize(MailSendProgressDispatcher.MAX_PENDING_IDS);
		dispatcher.close();
		assertThat((Map<?, ?>) pending.get(dispatcher)).isEmpty();
		final Field payload = retainedTask.getClass().getDeclaredField("progress"); payload.setAccessible(true);
		assertThat(payload.get(retainedTask)).isNull();
		retainedTask.run();
	}

	private static MailSendProgress snapshot(final UUID id) {
		return MailSendProgressImpl.builder().observationId(id).sequence(1).observedAt(Instant.EPOCH)
				.elapsed(Duration.ZERO).stage(MailSendProgressStage.PREPARING).build();
	}
}
