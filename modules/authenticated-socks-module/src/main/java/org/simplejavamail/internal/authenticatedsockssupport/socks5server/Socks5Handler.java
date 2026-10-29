package org.simplejavamail.internal.authenticatedsockssupport.socks5server;

import org.simplejavamail.api.internal.authenticatedsockssupport.common.Socks5Bridge;
import org.simplejavamail.internal.authenticatedsockssupport.common.SocksException;
import org.simplejavamail.internal.authenticatedsockssupport.socks5server.msg.CommandMessage;
import org.simplejavamail.internal.authenticatedsockssupport.socks5server.msg.CommandResponseMessage;
import org.simplejavamail.internal.authenticatedsockssupport.socks5server.msg.MethodSelectionMessage;
import org.simplejavamail.internal.authenticatedsockssupport.socks5server.msg.ServerReply;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Reads the local SOCKS handshake and remote setup, then hands established forwarding to its own lifetime. */
public class Socks5Handler implements Runnable {
    private static final Logger LOGGER = LoggerFactory.getLogger(Socks5Handler.class);
    private static final byte[] METHOD_SELECTION_RESPONSE = {(byte) 5, (byte) 0};
    private static final int CONNECT_COMMAND = 1;
    public static final int VERSION = 5;

    private final SocksSession session;
    private final Socks5Bridge bridge;
    private final SocksTunnel tunnel;

    public Socks5Handler(final SocksSession session, final Socks5Bridge bridge) {
        this(session, bridge, new SocksTunnel(session.getSocket(), () -> { }));
    }

    Socks5Handler(final SocksSession session, final Socks5Bridge bridge, final SocksTunnel tunnel) {
        this.session = session;
        this.bridge = bridge;
        this.tunnel = tunnel;
    }

    @Override
    public void run() {
        Throwable failure = null;
        try {
            handle();
        } catch (Exception problem) {
            failure = problem;
            if (tunnel.state() == SocksTunnel.State.CLOSED) {
                LOGGER.debug("SOCKS session {} ended during setup or forwarding", session.getId());
            } else {
                LOGGER.error(problem.getMessage(), problem);
            }
        } catch (Error problem) {
            failure = problem;
            throw problem;
        } finally {
            if (!tunnel.forwardingTransferred()) {
                tunnel.close(failure);
                try {
                    tunnel.awaitReservedForwarding();
                    tunnel.awaitClientReader();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    final Throwable primary = tunnel.failure();
                    if (primary != null) { primary.addSuppressed(interrupted); }
                }
            }
        }
    }

    private void handle() throws IOException {
        if (MethodSelectionMessage.readVersion(session.getInputStream()) != VERSION) {
            throw new SocksException("Protocol error");
        }
        session.write(METHOD_SELECTION_RESPONSE);
        final CommandMessage command = new CommandMessage();
        command.read(session.getInputStream());
        if (command.hasSocksException()) {
            session.write(CommandResponseMessage.getBytes(command.getSocksServerReplyException().getServerReply()));
            return;
        }
        if (command.getCommand() != CONNECT_COMMAND) {
            throw new SocksException("Only CONNECT command is supported");
        }

        // The local command is complete. A single reader now observes peer closure even while remote setup is blocked.
        session.getSocket().setSoTimeout(0);
        tunnel.startClientReader(session.getInputStream());
        final Socket remote;
        try {
            remote = bridge.connect(String.valueOf(session.getId()), command.getSocketAddress(), tunnel);
            tunnel.own(remote);
        } catch (IOException failure) {
            try {
                session.write(CommandResponseMessage.getBytes(replyFor(failure)));
            } catch (IOException | RuntimeException | Error secondary) {
                if (secondary != failure) { failure.addSuppressed(secondary); }
            }
            throw failure;
        }
        final InetAddress bindAddress = remote.getLocalAddress();
        final SocksTunnel.Forwarding forwarding = tunnel.reserveForwarding();
        session.write(CommandResponseMessage.getBytes(ServerReply.SUCCEEDED,
                bindAddress == null ? new InetSocketAddress(0).getAddress() : bindAddress, remote.getLocalPort()));
        forwarding.handoff();
    }

    private static ServerReply replyFor(final IOException failure) {
        final String message = failure.getMessage();
        if ("Connection refused".equals(message)) { return ServerReply.CONNECTION_REFUSED; }
        if ("Operation timed out".equals(message) || "Connection timed out".equals(message)) { return ServerReply.TTL_EXPIRED; }
        if ("Network is unreachable".equals(message)) { return ServerReply.NETWORK_UNREACHABLE; }
        return ServerReply.GENERAL_SOCKS_SERVER_FAILURE;
    }
}
