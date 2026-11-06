package org.simplejavamail.internal.authenticatedsockssupport.socks5client;

import jakarta.mail.AuthenticationFailedException;
import org.junit.jupiter.api.Test;
import org.simplejavamail.internal.authenticatedsockssupport.common.SocksException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocksAuthenticationReplyTest {

    @Test
    void consumesBothFragmentedSuccessBytesWithoutReadingTheNextExchange() throws Exception {
        final ReplySocket socket = new ReplySocket(new byte[] {1, 0, 5}, true);

        SocksAuthenticationHelper.performUserPasswordAuthentication(socket, new ProxyCredentials("proxy-user", "proxy-secret"));

        assertThat(socket.reply.read()).isEqualTo(5);
        assertThat(socket.closed).isFalse();
    }

    @Test
    void consumesAnOrdinarySuccessReplyWithoutReadingTheNextExchange() throws Exception {
        final ReplySocket socket = new ReplySocket(new byte[] {1, 0, 5}, false);

        SocksAuthenticationHelper.performUserPasswordAuthentication(socket, new ProxyCredentials("proxy-user", "proxy-secret"));

        assertThat(socket.reply.read()).isEqualTo(5);
        assertThat(socket.closed).isFalse();
    }

    @Test
    void rejectsFragmentedAuthenticationFailureAndClosesItsSocket() {
        final ReplySocket socket = new ReplySocket(new byte[] {1, 1}, true);

        assertThatThrownBy(() -> SocksAuthenticationHelper.performUserPasswordAuthentication(socket, new ProxyCredentials("proxy-user", "proxy-secret")))
                .isInstanceOf(SocksException.class).hasCauseInstanceOf(AuthenticationFailedException.class);
        assertThat(socket.closed).isTrue();
    }

    @Test
    void retainsOrdinaryAuthenticationRejection() {
        final ReplySocket socket = new ReplySocket(new byte[] {1, 1}, false);

        assertThatThrownBy(() -> SocksAuthenticationHelper.performUserPasswordAuthentication(socket, new ProxyCredentials("proxy-user", "proxy-secret")))
                .isInstanceOf(SocksException.class).hasCauseInstanceOf(AuthenticationFailedException.class);
        assertThat(socket.closed).isTrue();
    }

    @Test
    void rejectsAReplyUsingTheSocksVersionInsteadOfTheAuthenticationVersion() {
        final ReplySocket socket = new ReplySocket(new byte[] {5, 0}, false);

        assertThatThrownBy(() -> SocksAuthenticationHelper.performUserPasswordAuthentication(socket, new ProxyCredentials("proxy-user", "proxy-secret")))
                .isInstanceOf(SocksException.class).hasNoCause();
        assertThat(socket.closed).isTrue();
    }

    @Test
    void failsWhenTheReplyEndsBeforeItsFirstByte() {
        final ReplySocket socket = new ReplySocket(new byte[0], false);

        assertThatThrownBy(() -> SocksAuthenticationHelper.performUserPasswordAuthentication(socket, new ProxyCredentials("proxy-user", "proxy-secret")))
                .isInstanceOf(IOException.class);
        assertThat(socket.closed).isTrue();
    }

    @Test
    void failsWhenTheReplyEndsBeforeItsStatusByte() {
        final ReplySocket socket = new ReplySocket(new byte[] {1}, false);

        assertThatThrownBy(() -> SocksAuthenticationHelper.performUserPasswordAuthentication(socket, new ProxyCredentials("proxy-user", "proxy-secret")))
                .isInstanceOf(IOException.class);
        assertThat(socket.closed).isTrue();
    }

    @Test
    void retainsTheAuthenticationRejectionWhenSocketCleanupAlsoFails() {
        final IOException cleanupFailure = new IOException("socket close failed");
        final ReplySocket socket = new ReplySocket(new byte[] {1, 1}, false, cleanupFailure);

        assertThatThrownBy(() -> SocksAuthenticationHelper.performUserPasswordAuthentication(socket, new ProxyCredentials("proxy-user", "proxy-secret")))
                .isInstanceOf(SocksException.class)
                .hasMessage("Username or password error")
                .hasCauseInstanceOf(AuthenticationFailedException.class)
                .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(cleanupFailure));
        assertThat(socket.closed).isTrue();
    }

    @Test
    void retainsTheTruncatedReplyWhenSocketCleanupAlsoFails() {
        final IOException cleanupFailure = new IOException("socket close failed");
        final ReplySocket socket = new ReplySocket(new byte[] {1}, false, cleanupFailure);

        assertThatThrownBy(() -> SocksAuthenticationHelper.performUserPasswordAuthentication(socket, new ProxyCredentials("proxy-user", "proxy-secret")))
                .isInstanceOf(IOException.class)
                .hasMessage("End of stream")
                .satisfies(failure -> assertThat(failure.getSuppressed()).containsExactly(cleanupFailure));
        assertThat(socket.closed).isTrue();
    }

    private static final class ReplySocket extends Socket {
        private final InputStream reply;
        private final ByteArrayOutputStream request = new ByteArrayOutputStream();
        private final IOException cleanupFailure;
        private boolean closed;

        private ReplySocket(final byte[] bytes, final boolean fragmented) {
            this(bytes, fragmented, null);
        }

        private ReplySocket(final byte[] bytes, final boolean fragmented, final IOException cleanupFailure) {
            this.cleanupFailure = cleanupFailure;
            reply = new ByteArrayInputStream(bytes) {
                @Override public synchronized int read(final byte[] buffer, final int offset, final int length) {
                    return super.read(buffer, offset, fragmented ? Math.min(length, 1) : length);
                }
            };
        }

        @Override public InputStream getInputStream() { return reply; }
        @Override public OutputStream getOutputStream() { return request; }
        @Override public void close() throws IOException {
            closed = true;
            if (cleanupFailure != null) { throw cleanupFailure; }
        }
    }
}
