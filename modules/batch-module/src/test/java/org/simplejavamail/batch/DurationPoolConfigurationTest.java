package org.simplejavamail.batch;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DurationPoolConfigurationTest {
    @Test
    void standalonePoolConfigurationRetainsDurationsAndZeroMeanings() {
        final BatchTransportPoolConfiguration config = BatchTransportPoolConfiguration.builder()
                .withClaimTimeout(Duration.ZERO).withExpireAfter(Duration.ZERO)
                .withExpireAfterCreation(Duration.ofSeconds(3)).build();
        assertThat(config.getClaimTimeout()).isEqualTo(Duration.ZERO);
        assertThat(config.getExpireAfter()).isEqualTo(Duration.ZERO);
        assertThat(config.getExpireAfterCreation()).isEqualTo(Duration.ofSeconds(3));
        assertThat(BatchTransportPoolConfiguration.builder(config).clearExpireAfterCreation().build().getExpireAfterCreation()).isNull();
        assertThat(BatchTransportPoolConfiguration.builder(config).build()).isEqualTo(config);
    }

    @Test
    void standaloneExecutorDelegatesDurationSettingsAndChecksWorkerPrecision() throws Exception {
        try (BatchTransportExecutor<String> ignored = BatchTransportExecutor.<String>builder()
                .withClaimTimeout(Duration.ZERO).withExpireAfter(Duration.ZERO)
                .withExpireAfterCreation(Duration.ofSeconds(1)).clearExpireAfterCreation()
                .withThreadPoolKeepAliveTime(Duration.ZERO).build()) {
            assertThat(ignored).isNotNull();
        }
        assertThatThrownBy(() -> BatchTransportExecutor.builder().withThreadPoolKeepAliveTime(Duration.ofNanos(1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whole number of milliseconds");
        assertThatThrownBy(() -> BatchTransportExecutor.builder().withThreadPoolKeepAliveTime(Duration.ofMillis((long) Integer.MAX_VALUE + 1)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("integer-millisecond range");
    }

    @Test
    void standalonePoolRejectsFractionalMillisecondsAndOverflowBeforeResourcesExist() {
        assertThatThrownBy(() -> BatchTransportPoolConfiguration.builder().withClaimTimeout(Duration.ofNanos(1)).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("whole number of milliseconds");
        assertThatThrownBy(() -> BatchTransportPoolConfiguration.builder().withExpireAfter(Duration.ofMillis((long) Integer.MAX_VALUE + 1)).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("integer-millisecond range");
    }
}
