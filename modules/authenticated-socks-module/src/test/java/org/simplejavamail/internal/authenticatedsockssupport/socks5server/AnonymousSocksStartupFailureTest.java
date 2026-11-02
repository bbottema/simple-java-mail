package org.simplejavamail.internal.authenticatedsockssupport.socks5server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockedConstruction;
import org.simplejavamail.internal.authenticatedsockssupport.common.SocksException;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.SocketAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;

class AnonymousSocksStartupFailureTest {
    @ParameterizedTest
    @ValueSource(ints = {-1, 65536})
    void invalidInternalPortClosesItsPartialSocket(final int port) {
        try (MockedConstruction<ServerSocket> sockets = mockConstruction(ServerSocket.class)) {
            final AnonymousSocks5ServerImpl server = server(port);
            assertThat(catchThrowable(server::start)).isInstanceOf(IllegalArgumentException.class);
            assertThat(sockets.constructed()).hasSize(1);
            verify(sockets.constructed().get(0)).close();
            assertThat(server.isRunning()).isFalse();
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    @Test
    void uncheckedBindFailureRetainsIdentityAndSuppressedCloseFailure() throws Exception {
        final SecurityException original = new SecurityException("bind denied");
        final IOException cleanup = new IOException("close failed");
        try (MockedConstruction<ServerSocket> sockets = mockConstruction(ServerSocket.class, (socket, context) -> {
            doThrow(original).when(socket).bind(any(SocketAddress.class));
            doThrow(cleanup).when(socket).close();
        })) {
            final AnonymousSocks5ServerImpl server = server(0);
            assertThat(catchThrowable(server::start)).isSameAs(original).hasSuppressedException(cleanup);
            verify(sockets.constructed().get(0)).close();
            assertThat(server.isRunning()).isFalse();
        }
    }

    @Test
    void checkedBindFailureKeepsItsCauseAndSuppressedCloseFailure() throws Exception {
        final IOException original = new IOException("bind failed");
        final IOException cleanup = new IOException("close failed");
        try (MockedConstruction<ServerSocket> sockets = mockConstruction(ServerSocket.class, (socket, context) -> {
            doThrow(original).when(socket).bind(any(SocketAddress.class));
            doThrow(cleanup).when(socket).close();
        })) {
            assertThat(catchThrowable(server(0)::start)).isInstanceOf(SocksException.class).hasCause(original);
            assertThat(original).hasSuppressedException(cleanup);
            verify(sockets.constructed().get(0)).close();
        }
    }

    private static AnonymousSocks5ServerImpl server(final int port) {
        return new AnonymousSocks5ServerImpl((id, destination, owner) -> {
            throw new AssertionError("Startup must not connect to a remote proxy");
        }, port);
    }
}
