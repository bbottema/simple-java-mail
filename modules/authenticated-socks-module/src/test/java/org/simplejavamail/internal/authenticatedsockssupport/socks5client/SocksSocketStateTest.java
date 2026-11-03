package org.simplejavamail.internal.authenticatedsockssupport.socks5client;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketOption;
import java.net.StandardSocketOptions;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SocksSocketStateTest {
    @Test
    void anUnconnectedFacadeHasNoInventedDestinationAddress() throws Exception {
        try (Socket socket = facade(new PhysicalSocket())) {
            assertThat(socket.getInetAddress()).isNull();
            assertThat(socket.getRemoteSocketAddress()).isNull();
            assertThat(socket.getPort()).isZero();
            assertThat(socket.isConnected()).isFalse();
        }
    }

    @Test
    void closingRetainsUsableStateInspectionAndClosesTheSamePhysicalSocketOnce() throws Exception {
        final PhysicalSocket physical = new PhysicalSocket();
        final Socket socket = facade(physical);
        socket.close();
        socket.close();
        assertThat(socket.isClosed()).isTrue();
        assertThat(socket.isConnected()).isFalse();
        assertThat(socket.getRemoteSocketAddress()).isNull();
        assertThat(physical.closes).isEqualTo(1);
        assertThatThrownBy(socket::getInputStream).isInstanceOf(SocketException.class);
    }

    @Test
    void genericSocketOptionsBelongToTheSuppliedPhysicalSocket() throws Exception {
        final PhysicalSocket physical = new PhysicalSocket();
        try (Socket socket = facade(physical)) {
            assertThat(socket.getOption(StandardSocketOptions.SO_KEEPALIVE)).isTrue();
            assertThat(socket.setOption(StandardSocketOptions.SO_KEEPALIVE, false)).isSameAs(socket);
            assertThat(physical.keepAlive).isFalse();
            assertThat(socket.supportedOptions()).containsExactly(StandardSocketOptions.SO_KEEPALIVE);
        }
    }

    private static Socket facade(final PhysicalSocket physical) throws IOException {
        return new SocksSocket(new Socks5(new InetSocketAddress("127.0.0.1", 1080)), physical);
    }

    private static final class PhysicalSocket extends Socket {
        private boolean closed;
        private boolean keepAlive = true;
        private int closes;

        @Override public boolean isClosed() { return closed; }
        @Override public void close() { closes++; closed = true; }
        @Override public <T> Socket setOption(final SocketOption<T> option, final T value) {
            if (option == StandardSocketOptions.SO_KEEPALIVE) { keepAlive = (Boolean) value; }
            return this;
        }
        @SuppressWarnings("unchecked")
        @Override public <T> T getOption(final SocketOption<T> option) { return (T) Boolean.valueOf(keepAlive); }
        @Override public Set<SocketOption<?>> supportedOptions() { return Set.of(StandardSocketOptions.SO_KEEPALIVE); }
    }
}
