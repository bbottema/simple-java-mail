package org.simplejavamail.internal.openpgpsupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.internal.openpgpsupport.PgpainlessOpenPgpAdapter.PassphraseProviderFailure;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PassphraseProviderFailureTest {

    @Test
    void nativeWrappersRetainOriginalFailureAndSuppressedCleanupWithoutExposingTheMarker() {
        final IOException cause = new IOException("vault connection failed");
        final IllegalStateException original = new IllegalStateException("vault unavailable", cause);
        final PassphraseProviderFailure marker = new PassphraseProviderFailure(original);
        final IOException streamClose = new IOException("native stream close failed");
        marker.addSuppressed(streamClose);
        final IOException nativeWrapper = new IOException("native failure", marker);
        final IOException wrapperClose = new IOException("wrapper close failed");
        nativeWrapper.addSuppressed(wrapperClose);

        assertThatThrownBy(() -> PassphraseProviderFailure.rethrowIfPresent(nativeWrapper)).isSameAs(marker);
        assertThat(marker.originalFailure()).isSameAs(original).hasCause(cause);
        assertThat(original.getSuppressed()).containsExactlyInAnyOrder(streamClose, wrapperClose);
        assertThat(marker.originalFailure().getSuppressed()).containsExactlyInAnyOrder(streamClose, wrapperClose);
    }

    @Test
    void suppressedNativeWrapperCanCarryTheCallbackFailureThroughACyclicFailureGraph() {
        final IllegalArgumentException original = new IllegalArgumentException("callback failed");
        final PassphraseProviderFailure marker = new PassphraseProviderFailure(original);
        final IOException first = new IOException("native first");
        final IOException second = new IOException("native second", first);
        first.initCause(second);
        second.addSuppressed(marker);
        final IOException cleanup = new IOException("cleanup failed");
        first.addSuppressed(cleanup);

        assertThatThrownBy(() -> PassphraseProviderFailure.rethrowIfPresent(first)).isSameAs(marker);
        assertThat(marker.originalFailure()).isSameAs(original);
        assertThat(original.getSuppressed()).containsExactly(cleanup);
    }

    @Test
    void unrelatedFailuresAreNotClassifiedByTheirMessagesOrRuntimeType() {
        final IOException nativeFailure = new IOException("Application OpenPGP passphrase provider failed",
                new IllegalStateException("vault unavailable"));

        assertThatCode(() -> PassphraseProviderFailure.rethrowIfPresent(nativeFailure)).doesNotThrowAnyException();
    }
}
