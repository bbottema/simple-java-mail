package testutil.smtp;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

/** Owns a bounded number of scripted connections, including sockets whose conversation has not started yet. */
public final class ScriptedSmtpServer implements AutoCloseable {

	@FunctionalInterface
	public interface PeerScript {
		void run(SmtpConversation peer) throws Exception;
	}

	private final ServerSocket server;
	private final ExecutorService workers;
	private final CompletableFuture<Void> serving;
	private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();

	public ScriptedSmtpServer(final int connections, final PeerScript script) throws Exception {
		this(connections, US_ASCII, script);
	}

	public ScriptedSmtpServer(final int connections, final Charset charset, final PeerScript script) throws Exception {
		server = new ServerSocket(0, connections, InetAddress.getByName("localhost"));
		server.setSoTimeout(15000);
		workers = Executors.newFixedThreadPool(connections + 1);
		serving = CompletableFuture.runAsync(() -> serveConnections(connections, charset, script), workers);
	}

	private void serveConnections(final int connections, final Charset charset, final PeerScript script) {
		final List<CompletableFuture<Void>> conversations = new ArrayList<>();
		try {
			for (int index = 0; index < connections; index++) {
				final Socket socket = server.accept();
				sockets.add(socket);
				conversations.add(CompletableFuture.runAsync(() -> conductConversation(socket, charset, script), workers));
			}
			CompletableFuture.allOf(conversations.toArray(new CompletableFuture<?>[0])).get(20, SECONDS);
		} catch (Exception failure) {
			throw new AssertionError("Scripted SMTP server did not complete its expected conversations", failure);
		}
	}

	private void conductConversation(final Socket socket, final Charset charset, final PeerScript script) {
		try (SmtpConversation conversation = new SmtpConversation(socket, charset)) {
			script.run(conversation);
		} catch (Exception failure) {
			throw new AssertionError("Scripted SMTP conversation failed", failure);
		} finally {
			sockets.remove(socket);
		}
	}

	public int port() {
		return server.getLocalPort();
	}

	@Override
	public void close() throws Exception {
		try {
			serving.get(25, SECONDS);
		} finally {
			server.close();
			try {
				for (final Socket socket : sockets) {
					socket.close();
				}
			} finally {
				workers.shutdownNow();
				assertThat(workers.awaitTermination(5, SECONDS)).as("scripted SMTP workers stopped").isTrue();
			}
		}
	}
}
