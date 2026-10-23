package org.simplejavamail.examples.handoff;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpExchange;
import org.simplejavamail.examples.support.ArchiveCipher;
import org.simplejavamail.examples.support.ExampleHttp;
import org.simplejavamail.examples.support.ProtectedContent;
import org.simplejavamail.examples.support.ResourceCleanup;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Receive a prepared document, verify its protection and retain it before confirming the handoff.
 * Follow {@link #accept(HandoffPackage)} for the common path used by HTTPS and RabbitMQ. Neither path sends SMTP mail.
 */
final class HandoffReceiver implements AutoCloseable {
    private static final int MAX_PACKAGE_BYTES = 12 * 1024 * 1024;
    private final Path archive;
    private final boolean brokerMode;
    private final ArchiveCipher cipher = new ArchiveCipher();
    private final AtomicBoolean closed = new AtomicBoolean();
    private HttpsServer https;
    private ExecutorService httpsWorkers;
    private ExampleHttp evidence;
    private com.rabbitmq.client.Connection broker;
    private com.rabbitmq.client.Channel consumer;

    HandoffReceiver(Path archive, boolean brokerMode) throws Exception {
        this.archive = archive;
        this.brokerMode = brokerMode;
        Files.createDirectories(archive);
    }

    static void main(String[] args) throws Exception {
        boolean brokerMode = ExampleHttp.environment("HANDOFF_MODE", "https").equals("rabbitmq");
        HandoffReceiver receiver = new HandoffReceiver(Path.of("/data/packages"), brokerMode);
        receiver.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                receiver.close();
            } catch (Exception failed) {
                throw new IllegalStateException("Handoff receiver resources did not close cleanly", failed);
            }
        }));
    }

    void start() throws Exception {
        try {
            // A health endpoint must mean this receiver can do its job, not merely that its HTTP thread survived failed startup.
            if (brokerMode) {
                consumeBroker();
            }
            startHttps();
            startEvidence();
        } catch (Exception startupFailure) {
            ResourceCleanup.afterFailure(startupFailure, this);
            throw startupFailure;
        }
    }

    private void startHttps() throws Exception {
        https = HttpsServer.create(new InetSocketAddress("0.0.0.0", 8443), 16);
        https.setHttpsConfigurator(new HttpsConfigurator(developmentTls()));
        httpsWorkers = ExampleHttp.applicationWorkers(4);
        https.setExecutor(httpsWorkers);
        https.createContext("/packages", this::receiveHttpsPackage);
        https.start();
    }

    private SSLContext developmentTls() throws Exception {
        KeyStore keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(Path.of("/opt/demo/server.p12"))) {
            keys.load(input, "local-demo-only".toCharArray());
        }
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys, "local-demo-only".toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(managers.getKeyManagers(), null, null);
        return tls;
    }

    private void receiveHttpsPackage(HttpExchange exchange) throws java.io.IOException {
        try (exchange) {
            final HandoffPackage prepared;
            try {
                prepared = readHttpsPackage(exchange);
                if (prepared.id().startsWith("reject_")) {
                    exchange.sendResponseHeaders(422, -1);
                    return;
                }
                accept(prepared);
            } catch (Exception failed) {
                exchange.sendResponseHeaders(409, -1);
                return;
            }
            if (prepared.id().startsWith("unknown_")) {
                return; // Storage succeeded; deliberately lose confirmation so the caller must reconcile instead of blindly retrying.
            }
            exchange.sendResponseHeaders(200, -1);
        }
    }

    private HandoffPackage readHttpsPackage(HttpExchange exchange) throws Exception {
        byte[] input = exchange.getRequestBody().readNBytes(MAX_PACKAGE_BYTES + 1);
        if (input.length > MAX_PACKAGE_BYTES) {
            throw new IllegalArgumentException("Package too large");
        }
        return ExampleHttp.JSON.readValue(input, HandoffPackage.class);
    }

    private void startEvidence() throws Exception {
        evidence = new ExampleHttp();
        evidence.route("/packages", request -> {
            try (var paths = Files.list(archive)) {
                return paths.filter(path -> path.toString().endsWith(".json")).map(this::packageEvidence).toList();
            }
        });
        if (brokerMode) {
            evidence.route("/poison-package", request -> {
                if (!request.method().equals("POST")) {
                    throw new ExampleHttp.Rejection(405, "POST injects a synthetic invalid package");
                }
                try (var connection = HandoffApplication.brokerConnection(); var channel = connection.createChannel()) {
                    HandoffApplication.declarePreparedQueue(channel);
                    channel.confirmSelect();
                    channel.basicPublish("", "prepared-mail", com.rabbitmq.client.MessageProperties.PERSISTENT_BASIC,
                            "not a prepared package".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    channel.waitForConfirmsOrDie(5000);
                }
                return Map.of("injected", true);
            });
            evidence.route("/dead-letters", request -> {
                try (var connection = HandoffApplication.brokerConnection(); var channel = connection.createChannel()) {
                    return Map.of("count", channel.queueDeclarePassive("prepared-mail.dead").getMessageCount());
                }
            });
        }
        evidence.start();
    }

    private Map<String, Object> packageEvidence(Path path) {
        try {
            Map<?, ?> stored = ExampleHttp.JSON.readValue(Files.readAllBytes(path), Map.class);
            return Map.of("id", stored.get("id"), "sha256", stored.get("sha256"), "recipients", stored.get("recipients"),
                    "signature", stored.get("signature"), "decrypted", stored.get("decrypted"), "transport", "NON_SMTP");
        } catch (Exception incomplete) {
            return Map.of("id", path.getFileName().toString(), "state", "NEEDS_REVIEW");
        }
    }

    // HTTPS and broker callbacks share this receiver. Keep checking a key and creating its file indivisible within this one process.
    private synchronized void accept(HandoffPackage prepared) throws Exception {
        requireValidPackage(prepared);
        Path destination = archive.resolve(prepared.id() + ".json");
        if (recognizeRetainedPackage(destination, prepared)) {
            return;
        }
        var verified = verifyProtectedContent(prepared);
        storeEncryptedPackage(destination, prepared, verified);
    }

    static void requireValidPackage(HandoffPackage prepared) {
        if (prepared.id() == null || !prepared.id().matches("[A-Za-z0-9_-]{1,100}") || prepared.mime() == null
                || prepared.sender() == null || prepared.sender().isBlank()
                || prepared.recipients() == null || prepared.recipients().isEmpty() || prepared.recipients().stream().anyMatch(Objects::isNull)
                || !ProtectedContent.sha256(prepared.mime()).equals(prepared.sha256())) {
            throw new IllegalArgumentException("Supply a short package key, sender, recipients and MIME bytes with their matching checksum");
        }
    }

    private boolean recognizeRetainedPackage(Path destination, HandoffPackage prepared) throws Exception {
        if (Files.exists(destination)) {
            Map<?, ?> previous = ExampleHttp.JSON.readValue(Files.readAllBytes(destination), Map.class);
            if (!matchesRetainedPackage(prepared, previous)) {
                throw new IllegalArgumentException("This key already identifies different content or envelope");
            }
            return true;
        }
        return false;
    }

    static boolean matchesRetainedPackage(HandoffPackage prepared, Map<?, ?> previous) {
        // Equal MIME alone is not an equal handoff: hidden recipients and the envelope sender can change without changing those bytes.
        return prepared.sha256().equals(previous.get("sha256"))
                && prepared.recipients().equals(previous.get("recipients"))
                && Objects.equals(prepared.sender(), previous.get("sender"));
    }

    private String verifyProtectedContent(HandoffPackage prepared) throws Exception {
        var outerMessage = new jakarta.mail.internet.MimeMessage(jakarta.mail.Session.getInstance(new java.util.Properties()),
                new java.io.ByteArrayInputStream(prepared.mime()));
        if (outerMessage.getHeader("Bcc") != null) {
            throw new IllegalArgumentException("Hidden recipients belong only in the package envelope");
        }
        var parsed = ProtectedContent.readProtected(prepared.mime());
        String signature = parsed.getOriginalSmimeDetails().getVerificationStatus().name();
        if (!signature.equals("VALID") || parsed.getPlainText() == null) {
            throw new IllegalArgumentException("Synthetic partner protection did not verify");
        }
        return signature;
    }

    private void storeEncryptedPackage(Path destination, HandoffPackage prepared, String signature) throws Exception {
        byte[] stored = ExampleHttp.JSON.writeValueAsBytes(Map.of("id", prepared.id(), "sha256", prepared.sha256(),
                "recipients", prepared.recipients(), "sender", prepared.sender(),
                "signature", signature, "decrypted", true, "encryptedMime", cipher.encrypt(prepared.mime())));
        try (var file = java.nio.channels.FileChannel.open(destination, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.wrap(stored);
            while (buffer.hasRemaining()) {
                file.write(buffer);
            }
            // Confirm only after the retained representation has been handed to durable storage. This is not a disk-failure recovery protocol.
            file.force(true);
        }
    }

    private void consumeBroker() throws Exception {
        broker = HandoffApplication.brokerConnection();
        consumer = broker.createChannel();
        HandoffApplication.declarePreparedQueue(consumer);
        consumer.basicQos(1);
        consumer.basicConsume("prepared-mail", false, (tag, delivery) -> {
            try {
                accept(ExampleHttp.JSON.readValue(delivery.getBody(), HandoffPackage.class));
                consumer.basicAck(delivery.getEnvelope().getDeliveryTag(), false);
            } catch (Exception failed) {
                // Preserve poison/ambiguous packages in the dead-letter queue; never hide an SMTP replay in the consumer.
                consumer.basicReject(delivery.getEnvelope().getDeliveryTag(), false);
                System.err.println("Prepared package needs operator review");
            }
        }, tag -> { });
    }

    @Override
    public void close() throws Exception {
        if (closed.compareAndSet(false, true)) {
            ResourceCleanup.closeAll(evidence, this::stopHttps, this::stopHttpsWorkers, consumer, broker);
        }
    }

    private void stopHttps() {
        if (https != null) {
            https.stop(1);
        }
    }

    private void stopHttpsWorkers() {
        if (httpsWorkers != null) {
            httpsWorkers.shutdown();
        }
    }
}
