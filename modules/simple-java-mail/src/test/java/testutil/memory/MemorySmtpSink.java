package testutil.memory;

import org.subethamail.smtp.MessageHandler;
import org.subethamail.smtp.RejectException;
import org.subethamail.smtp.server.SMTPServer;
import org.subethamail.smtp.server.Session;
import org.subethamail.smtp.server.SessionHandler;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Uses Wiser's SubEthaSMTP engine while streaming DATA to a discard sink instead of retaining messages or command history. */
final class MemorySmtpSink implements AutoCloseable {
    private static final int MAXIMUM_DATA_BYTES = 100 * 1024 * 1024;
    private final SMTPServer server;
    private final ExecutorService workers;
    private final Set<Session> connections = ConcurrentHashMap.newKeySet();
    private final Set<String> refusedRecipients = ConcurrentHashMap.newKeySet();
    private final AtomicLong accepted = new AtomicLong();
    private final AtomicLong refused = new AtomicLong();
    private final AtomicLong opened = new AtomicLong();
    private final AtomicLong contentBytes = new AtomicLong();
    private final String mode;
    private final int maximumRetryKeys;
    private volatile boolean available = true;

    private MemorySmtpSink(final String mode, final int maximumConnections, final int maximumPendingRetryKeys) throws IOException {
        this.mode = mode;
        maximumRetryKeys = maximumPendingRetryKeys;
        // Reuse idle SMTP workers before creating more; sequential churn must not gradually create every permitted connection thread.
        workers = new ThreadPoolExecutor(0, maximumConnections, 60, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            final Thread thread = new Thread(task, "memory-smtp-connection");
            thread.setDaemon(true);
            return thread;
        });
        server = SMTPServer.port(0).bindAddress(InetAddress.getByName("127.0.0.1")).hostName("memory.example.test")
                .serverThreadName("memory-smtp-listener").executorService(workers).maxConnections(maximumConnections)
                .connectionTimeoutMs(60000).maxMessageSize(MAXIMUM_DATA_BYTES).enableTLS(false).requireAuth(false).insertReceivedHeaders(false)
                .sessionHandler(new SessionHandler() {
                    @Override
                    public SessionAcceptance accept(final Session session) {
                        opened.incrementAndGet();
                        if (!available) {
                            return SessionAcceptance.failure(421, "memory fixture temporarily unavailable");
                        }
                        connections.add(session);
                        return SessionAcceptance.success();
                    }

                    @Override
                    public void onSessionEnd(final Session session) {
                        connections.remove(session);
                    }
                })
                .messageHandlerFactory(context -> new DiscardingHandler()).build();
        server.start();
    }

    static void serve(final String mode, final int maximumConnections, final int maximumPendingRetryKeys) throws Exception {
        try (MemorySmtpSink sink = new MemorySmtpSink(mode, maximumConnections, maximumPendingRetryKeys);
             BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            System.out.println("PORT=" + sink.server.getPortAllocated());
            System.out.flush();
            String command;
            while ((command = commands.readLine()) != null && !command.equals("STOP")) {
                System.out.println(sink.control(command));
                System.out.flush();
            }
        }
    }

    private String control(final String command) {
        if (command.equals("STATS")) {
            return "STATS=" + accepted.get() + ',' + refused.get() + ',' + opened.get() + ',' + contentBytes.get() + ','
                    + connections.size() + ',' + refusedRecipients.size();
        }
        if (command.equals("AVAILABLE true") || command.equals("AVAILABLE false")) {
            available = command.endsWith("true");
            // The controller changes availability only after draining sends, so this cannot manufacture a mid-submission failure.
            if (!available) {
                closeConnections();
            }
            return "AVAILABLE=" + available;
        }
        throw new IllegalArgumentException("Unknown memory fixture command: " + command);
    }

    private boolean shouldRefuse(final String recipient) throws IOException {
        if (mode.equals("refuse")) {
            return true;
        }
        if (!mode.equals("retry")) {
            return false;
        }
        if (refusedRecipients.remove(recipient)) {
            return false;
        }
        if (refusedRecipients.size() >= maximumRetryKeys) {
            throw new IOException("Retry fixture history exceeded the bounded outstanding-message limit");
        }
        refusedRecipients.add(recipient);
        return true;
    }

    private void closeConnections() {
        connections.forEach(connection -> {
            try {
                connection.closeSocket();
            } catch (IOException alreadyClosed) {
                // Client cancellation and fixture teardown can close the same connection.
            }
        });
    }

    @Override
    public void close() throws Exception {
        server.stop();
        closeConnections();
        workers.shutdownNow();
        if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("The discard-only SMTP fixture did not terminate");
        }
        refusedRecipients.clear();
    }

    private final class DiscardingHandler implements MessageHandler {
        private String recipient;

        @Override public void from(final String sender) { }
        @Override public void recipient(final String address) { recipient = address; }

        @Override
        public String data(final InputStream content) throws IOException, RejectException {
            contentBytes.addAndGet(content.transferTo(OutputStream.nullOutputStream()));
            if (shouldRefuse(recipient)) {
                refused.incrementAndGet();
                throw new RejectException(451, "4.3.0 synthetic temporary refusal");
            }
            accepted.incrementAndGet();
            return "accepted by discard-only memory fixture";
        }

        @Override public void done() { }
    }
}
