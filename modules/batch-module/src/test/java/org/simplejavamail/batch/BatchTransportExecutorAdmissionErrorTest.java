package org.simplejavamail.batch;

import jakarta.mail.Session;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class BatchTransportExecutorAdmissionErrorTest {

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void workerCreationErrorDoesNotLeaveAnAcceptedOperationOrOwnTheSuppliedExecutor(final boolean stickySession) throws Exception {
        final AssertionError failure = new AssertionError("worker setup failed");
        final ExecutorService workers = Executors.newSingleThreadExecutor(task -> { throw failure; });
        final BatchTransportExecutor<String> pool = BatchTransportExecutor.<String>builder()
                .withExecutorService(workers).build();
        final AtomicBoolean invoked = new AtomicBoolean();
        try {
            final BatchTransportOperation<Void, RuntimeException> operation = (session, transport) -> {
                invoked.set(true);
                return null;
            };
            final Throwable thrown = catchThrowable(() -> {
                if (stickySession) {
                    pool.submit("cluster", Session.getInstance(new Properties()), operation);
                } else {
                    pool.submit("cluster", operation);
                }
            });

            assertThat(thrown).isSameAs(failure);
            assertThat(invoked).isFalse();
            assertThat(pool.isShutdown()).isFalse();
            pool.shutdown().get(1, SECONDS);
            pool.close();
            assertThat(workers.isShutdown()).isFalse();
        } finally {
            // Forced shutdown also retires the phantom admission on the unfixed implementation.
            pool.shutdownNow().get(5, SECONDS);
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, SECONDS)).isTrue();
        }
    }
}
