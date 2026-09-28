package testutil.performance;

import org.subethamail.smtp.MessageHandler;
import org.subethamail.smtp.server.SMTPServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/** Uses the existing SMTP test server without Wiser's retained message copies or per-message logging. */
final class AuditSmtpServer implements AutoCloseable {
    static final int MAXIMUM_MESSAGE_BYTES = 64 * 1024 * 1024;

    final Set<Long> threadIds = ConcurrentHashMap.newKeySet();
    final AtomicLong messages = new AtomicLong();
    final AtomicLong bytes = new AtomicLong();
    private final ExecutorService workers;
    private final SMTPServer server;

    AuditSmtpServer(final boolean advertiseSize) throws IOException {
        // Eight warm pooled connections can remain open while the sequential batch uses its own connection.
        // Keep fixture threads alive across samples so their CPU counters remain comparable.
        workers = Executors.newFixedThreadPool(16, task -> {
            final Thread thread = new Thread(task, "audit-smtp-sink");
            threadIds.add(thread.getId());
            return thread;
        });
        server = SMTPServer.port(0).bindAddress(InetAddress.getByName("127.0.0.1")).hostName("audit.example.test")
                .serverThreadName("audit-smtp-listener").executorService(workers)
                .maxConnections(32).connectionTimeoutMs(60000).maxMessageSize(advertiseSize ? MAXIMUM_MESSAGE_BYTES : 0)
                .enableTLS(false).requireAuth(false).insertReceivedHeaders(false)
                .messageHandlerFactory(context -> new DiscardingHandler()).build();
        server.start();
    }

    int port() {
        return server.getPortAllocated();
    }

    @Override
    public void close() throws InterruptedException {
        server.stop();
        workers.shutdown();
        if (!workers.awaitTermination(10, TimeUnit.SECONDS)) {
            workers.shutdownNow();
            throw new IllegalStateException("The loopback SMTP fixture did not stop");
        }
    }

    private final class DiscardingHandler implements MessageHandler {
        @Override public void from(final String sender) { }
        @Override public void recipient(final String recipient) { }

        @Override
        public String data(final InputStream content) throws IOException {
            bytes.addAndGet(content.transferTo(OutputStream.nullOutputStream()));
            messages.incrementAndGet();
            return "audit-accepted";
        }

        @Override public void done() { }
    }
}
