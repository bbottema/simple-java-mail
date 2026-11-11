package org.simplejavamail.internal.openpgpsupport;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CancellationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SignaturePacketCancellationTest {
    @Test
    void packetReadFailureOnAnInterruptedThreadPropagatesCancellationInsteadOfBecomingIncompleteEvidence() {
        assertThat(Thread.currentThread().isInterrupted()).isFalse();
        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> SignaturePacketReader.read(new byte[]{0}))
                    .isInstanceOf(CancellationException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
