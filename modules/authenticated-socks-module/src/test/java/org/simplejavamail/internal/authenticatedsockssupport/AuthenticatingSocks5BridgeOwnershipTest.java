package org.simplejavamail.internal.authenticatedsockssupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.config.ProxyConfig;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuthenticatingSocks5BridgeOwnershipTest {

    @Test
    void closesTheAllocatedRawSocketWhenTheOwnershipCallbackThrows() throws Exception {
        try (ServerSocket remoteProxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final ProxyConfig proxy = new ProxyConfig() {
                @Override public boolean requiresProxy() { return true; }
                @Override public boolean requiresAuthentication() { return true; }
                @Override public Integer getProxyBridgePort() { return 0; }
                @Override public String getRemoteProxyHost() { return InetAddress.getLoopbackAddress().getHostAddress(); }
                @Override public Integer getRemoteProxyPort() { return remoteProxy.getLocalPort(); }
                @Override public String getUsername() { return "proxy-user"; }
                @Override public String getPassword() { return "proxy-secret"; }
                @Override public String toString() { return "owned loopback proxy"; }
            };
            final AtomicReference<Socket> allocated = new AtomicReference<>();
            final IOException original = new IOException("ownership callback failed");
            try {
                assertThatThrownBy(() -> new AuthenticatingSocks5Bridge(proxy).connect(
                        "failed-owner", new InetSocketAddress(InetAddress.getLoopbackAddress(), 25), raw -> {
                            allocated.set(raw);
                            assertThat(raw.isConnected()).isFalse();
                            throw original;
                        })).isSameAs(original);

                assertThat(allocated.get()).isNotNull();
                assertThat(allocated.get().isClosed()).as("producer retains ownership until callback returns").isTrue();
            } finally {
                final Socket socket = allocated.get();
                if (socket != null && !socket.isClosed()) { socket.close(); }
            }
        }
    }

    @Test
    void publishesTheRawSocketBeforeAnyRemoteConnectionCanBlock() throws Exception {
        try (ServerSocket remoteProxy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            final ProxyConfig proxy = new ProxyConfig() {
                @Override public boolean requiresProxy() { return true; }
                @Override public boolean requiresAuthentication() { return true; }
                @Override public Integer getProxyBridgePort() { return 0; }
                @Override public String getRemoteProxyHost() { return InetAddress.getLoopbackAddress().getHostAddress(); }
                @Override public Integer getRemoteProxyPort() { return remoteProxy.getLocalPort(); }
                @Override public String getUsername() { return "proxy-user"; }
                @Override public String getPassword() { return "proxy-secret"; }
                @Override public String toString() { return "owned loopback proxy"; }
            };
            final AtomicReference<Socket> published = new AtomicReference<>();
            final IOException cancelled = new IOException("local client already closed");

            assertThatThrownBy(() -> new AuthenticatingSocks5Bridge(proxy).connect(
                    "early-owner", new InetSocketAddress(InetAddress.getLoopbackAddress(), 25), raw -> {
                        published.set(raw);
                        try {
                            assertThat(raw.isConnected()).as("raw handle is owned before TCP setup").isFalse();
                        } finally {
                            raw.close();
                        }
                        throw cancelled;
                    })).isSameAs(cancelled);

            assertThat(published.get()).isNotNull();
            assertThat(published.get().isClosed()).isTrue();
            assertThat(published.get().isConnected()).isFalse();
        }
    }
}
