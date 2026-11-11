package org.simplejavamail.internal.protection;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtectionInputReadBoundaryTest {
    @Test
    void acquiredPrefixAndSuppressedCloseFailureKeepTheirActualInputStages() {
        final byte[] prefix = "captured prefix".getBytes(StandardCharsets.US_ASCII);
        final IOException reading = new IOException("selected read failed");
        final IOException closing = new IOException("selected close failed");
        final InputStream input = new InputStream() {
            private int index;
            @Override public int read() throws IOException {
                if (index == prefix.length) throw reading;
                return prefix[index++] & 0xff;
            }
            @Override public void close() throws IOException { throw closing; }
        };

        assertThatThrownBy(() -> ProtectionInputReadException.acquire(() -> input))
                .isInstanceOfSatisfying(ProtectionInputReadException.class, failure -> {
                    assertThat(failure.getReadFailure()).isSameAs(reading);
                    assertThat(failure.getCloseFailure()).isSameAs(closing);
                    assertThat(failure.getCause()).isSameAs(reading);
                    assertThat(reading.getSuppressed()).containsExactly(closing);
                    assertThat(failure.isFullyAcquired()).isFalse();
                    assertThat(failure.getCapturedBytes()).containsExactly(prefix);
                });
    }

    @Test
    void closeOnlyFailureIsNotRelabeledAsAReadFailureAndItsCompleteDiagnosticBytesAreDetached() {
        final byte[] content = {1, 2, 3};
        final IOException closing = new IOException("selected close failed");
        assertThatThrownBy(() -> ProtectionInputReadException.acquire(() -> new ByteArrayInputStream(content) {
            @Override public void close() throws IOException { throw closing; }
        })).isInstanceOfSatisfying(ProtectionInputReadException.class, failure -> {
            assertThat(failure.getReadFailure()).isNull();
            assertThat(failure.getCloseFailure()).isSameAs(closing);
            assertThat(failure.isFullyAcquired()).isTrue();
            failure.getCapturedBytes()[0] = 99;
            assertThat(failure.getCapturedBytes()).containsExactly(content);
        });
    }

    @Test
    void unrelatedProviderDefectsPropagateAndOwnedStreamsStillClose() {
        final IllegalStateException defect = new IllegalStateException("selected provider defect");
        final AtomicBoolean closed = new AtomicBoolean();
        assertThatThrownBy(() -> ProtectionInputReadException.acquire(() -> new InputStream() {
            @Override public int read() { throw defect; }
            @Override public void close() { closed.set(true); }
        })).isSameAs(defect);
        assertThat(closed).isTrue();
    }
}
