package org.simplejavamail.internal.clisupport.daemon;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Opens local-only daemon channels without exposing transport choice to CLI execution.
 * Unix-domain sockets are preferred; fallback is restricted to recognized platform/path capability failures and binds
 * only literal {@code 127.0.0.1}. Socket cleanup accepts only the exact derived socket and refuses links or ordinary
 * files, so stale-state recovery cannot delete an arbitrary path.
 */
final class LocalDaemonTransport {
	private LocalDaemonTransport() {
	}

	static Server openServer(final DaemonPaths paths) throws IOException {
		if (Boolean.getBoolean(DaemonPaths.FORCE_TCP_PROPERTY)) {
			return openTcpServer("tcp-forced");
		}
		try {
			return openUnixServer(paths);
		} catch (UnsupportedOperationException e) {
			return openTcpServer("tcp-unix-unsupported");
		} catch (IOException e) {
			if (!mayFallbackFromUnix(e)) {
				throw e;
			}
			return openTcpServer(unixFallbackReason(e));
		}
	}

	private static Server openUnixServer(final DaemonPaths paths) throws IOException {
		final Path socketPath = paths.socketPath();
		if (!paths.isDerivedSocket(socketPath)) {
			throw new IOException("Refusing an underived daemon socket path");
		}
		deleteSocketIfPresent(socketPath);
		final ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
		try {
			channel.bind(UnixDomainSocketAddress.of(socketPath));
			return new Server(channel,
					new DaemonEndpoint(DaemonEndpoint.Kind.UNIX, socketPath.toString(), "unix-preferred"), socketPath);
		} catch (IOException | RuntimeException e) {
			channel.close();
			throw e;
		}
	}

	private static void deleteSocketIfPresent(final Path socketPath) throws IOException {
		if (!Files.exists(socketPath, LinkOption.NOFOLLOW_LINKS)) {
			return;
		}
		final BasicFileAttributes attributes = Files.readAttributes(socketPath, BasicFileAttributes.class,
				LinkOption.NOFOLLOW_LINKS);
		if (Files.isSymbolicLink(socketPath) || !attributes.isOther()) {
			throw new DaemonSecurityException("Refusing to replace a non-socket daemon endpoint");
		}
		Files.delete(socketPath);
	}

	private static Server openTcpServer(final String selectionReason) throws IOException {
		final InetAddress loopback = InetAddress.getByName("127.0.0.1");
		final ServerSocketChannel channel = ServerSocketChannel.open();
		channel.bind(new InetSocketAddress(loopback, 0));
		final InetSocketAddress bound = (InetSocketAddress) channel.getLocalAddress();
		if (!bound.getAddress().isLoopbackAddress()) {
			channel.close();
			throw new IOException("Refusing a non-loopback daemon listener");
		}
		return new Server(channel, new DaemonEndpoint(DaemonEndpoint.Kind.TCP,
				bound.getAddress().getHostAddress() + ":" + bound.getPort(), selectionReason), null);
	}

	static SocketChannel connect(final DaemonEndpoint endpoint) throws IOException {
		return switch (endpoint.kind()) {
			case UNIX -> SocketChannel.open(UnixDomainSocketAddress.of(endpoint.address()));
			case TCP -> connectTcp(endpoint.address());
		};
	}

	private static SocketChannel connectTcp(final String address) throws IOException {
		final int separator = address.lastIndexOf(':');
		if (separator < 1) {
			throw new IOException("Malformed daemon TCP endpoint");
		}
		if (!"127.0.0.1".equals(address.substring(0, separator))) {
			throw new IOException("Refusing a non-loopback daemon endpoint");
		}
		final int port;
		try {
			port = Integer.parseInt(address.substring(separator + 1));
		} catch (NumberFormatException e) {
			throw new IOException("Malformed daemon TCP endpoint", e);
		}
		if (port < 1 || port > 65535) {
			throw new IOException("Malformed daemon TCP endpoint");
		}
		return SocketChannel.open(new InetSocketAddress(InetAddress.getByAddress(new byte[] { 127, 0, 0, 1 }), port));
	}

	private static boolean mayFallbackFromUnix(final IOException exception) {
		if (exception instanceof AccessDeniedException) {
			return false;
		}
		final String message = String.valueOf(exception.getMessage()).toLowerCase();
		return message.contains("not supported") || message.contains("protocol family") || message.contains("too long")
				|| message.contains("invalid argument") || message.contains("operation not supported");
	}

	private static String unixFallbackReason(final IOException exception) {
		final String message = String.valueOf(exception.getMessage()).toLowerCase();
		return message.contains("too long") ? "tcp-unix-path-limit" : "tcp-unix-unavailable";
	}

	/** Owns the selected listener and removes its Unix socket, when applicable, as part of close. */
	static final class Server implements AutoCloseable {
		private final ServerSocketChannel channel;
		private final DaemonEndpoint endpoint;
		private final Path socketPath;

		private Server(final ServerSocketChannel channel, final DaemonEndpoint endpoint, final Path socketPath) {
			this.channel = channel;
			this.endpoint = endpoint;
			this.socketPath = socketPath;
		}

		SocketChannel accept() throws IOException {
			return channel.accept();
		}

		DaemonEndpoint endpoint() {
			return endpoint;
		}

		@Override
		public void close() throws IOException {
			try {
				channel.close();
			} finally {
				if (socketPath != null) {
					deleteSocketIfPresent(socketPath);
				}
			}
		}
	}
}
