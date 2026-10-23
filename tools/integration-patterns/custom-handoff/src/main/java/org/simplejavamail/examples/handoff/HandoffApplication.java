package org.simplejavamail.examples.handoff;

import org.simplejavamail.api.mailer.MailSendHandlers;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.config.OperationalConfig;
import org.simplejavamail.examples.support.ExampleHttp;
import org.simplejavamail.examples.support.ProtectedContent;
import org.simplejavamail.examples.support.ResourceCleanup;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Prepare a protected document with ordinary SJM calls, then hand it to another application instead of an SMTP server.
 * Follow {@link #submit(String)} from job admission through composition and the custom handoff. The gateway owns what happens afterward.
 */
public final class HandoffApplication implements AutoCloseable {
    private final ExampleHttp http;
    private final SimpleJavaMail mail = SimpleJavaMail.fromDefaults();
    private final HandoffTransport transport;
    private final Map<String, Map<String, Object>> outcomes = new ConcurrentHashMap<>();
    private final Mailer mailer;

    private HandoffApplication() throws Exception {
        http = new ExampleHttp();
        HandoffTransport acquiredTransport = null;
        Mailer acquiredMailer = null;
        try {
            acquiredTransport = new HandoffTransport();
            transport = acquiredTransport;
            acquiredMailer = mail.mailerBuilder()
                    .withCustomMailer(transport)
                    .withSendHandlers(MailSendHandlers.builder().withResultObserver(this::recordOutcome).build())
                    .buildMailer();
            mailer = acquiredMailer;
            registerEndpoints();
        } catch (Exception startupFailure) {
            ResourceCleanup.afterFailure(startupFailure, acquiredMailer, acquiredTransport, http);
            throw startupFailure;
        }
    }

    private void registerEndpoints() {
        http.route("/jobs", request -> {
            String id = request.parameters().get("id");
            if (request.method().equals("POST")) {
                submit(id);
            }
            return id == null ? outcomes.values() : outcomes.getOrDefault(id, Map.of("id", id, "state", "NOT_REACHED"));
        });
    }

    public static void main(String[] args) throws Exception {
        if (ExampleHttp.environment("HANDOFF_ROLE", "producer").equals("gateway")) {
            HandoffReceiver.main(args);
            return;
        }
        HandoffApplication application = new HandoffApplication();
        Runtime.getRuntime().addShutdownHook(new Thread(application::close));
        application.http.start();
    }

    private void submit(String id) {
        requireJobId(id);
        if (outcomes.putIfAbsent(id, Map.of("id", id, "state", "PREPARING")) != null) {
            return;
        }
        final Email email;
        try {
            email = composeProtectedEmail(id);
        } catch (RuntimeException preparationFailed) {
            outcomes.put(id, Map.of("id", id, "completed", false, "smtpAcceptance", "NO_RECEIPT", "state", "HELD_PREPARATION"));
            return;
        }
        submitPreparedMail(mailer, email);
    }

    private void requireJobId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new ExampleHttp.Rejection(400, "Use a short application job ID");
        }
    }

    private Email composeProtectedEmail(String id) {
        return mail.emailBuilder().startingBlank()
                .from("sender@example.test")
                .withBounceTo("bounce@example.test")
                .withRecipients(mail.recipientBuilder().withAddress("partner@example.test")
                        .withType(jakarta.mail.Message.RecipientType.TO).build())
                .withRecipients(mail.recipientBuilder().withAddress("archive@example.test")
                        .withType(jakarta.mail.Message.RecipientType.BCC).build())
                .fixingMessageId("<" + id + "@handoff.example.test>")
                .withSubject("Synthetic partner document")
                .withPlainText("Confidential demonstration purchase order")
                .signWithSmime(ProtectedContent.signing())
                .encryptWithSmime(ProtectedContent.encryption())
                .buildEmail();
    }

    static void submitPreparedMail(Mailer mailer, Email email) {
        try {
            mailer.sync().sendMail(email);
        } catch (RuntimeException failed) {
            // The inline result observer owns terminal evidence. Do not erase its receipt, or replay a possibly accepted package.
            System.err.println("Custom handoff needs review: " + failed.getClass().getSimpleName());
        }
    }

    private void recordOutcome(MailSendOutcome outcome) {
        String id = outcome.getInitialMessageId().replace("<", "").replace("@handoff.example.test>", "");
        outcomes.put(id, terminalFacts(id, outcome));
    }

    static Map<String, Object> terminalFacts(String id, MailSendOutcome outcome) {
        return Map.of("id", id, "completed", outcome.isSuccessful(), "state", outcome.isSuccessful() ? "HANDED_OFF" : "HELD",
                "smtpAcceptance", outcome.getSubmissionReceipt().map(receipt -> receipt.getStatus().name()).orElse("NO_RECEIPT"));
    }

    @Override
    public void close() {
        try {
            ResourceCleanup.closeAll(http, mailer, transport);
        } catch (Exception failed) {
            throw new IllegalStateException("Handoff resources did not close cleanly", failed);
        }
    }

    static Connection brokerConnection() throws Exception {
        ConnectionFactory factory = new ConnectionFactory();
        factory.setHost("broker");
        factory.setUsername("patterns");
        factory.setPassword("local-demo-only");
        return factory.newConnection();
    }

    static void declarePreparedQueue(com.rabbitmq.client.Channel channel) throws Exception {
        channel.queueDeclare("prepared-mail.dead", true, false, false, null);
        channel.queueDeclare("prepared-mail", true, false, false,
                Map.of("x-dead-letter-exchange", "", "x-dead-letter-routing-key", "prepared-mail.dead"));
    }

    private static final class HandoffTransport implements CustomMailer, AutoCloseable {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        private final boolean brokerMode = ExampleHttp.environment("HANDOFF_MODE", "https").equals("rabbitmq");
        private Connection broker;

        HandoffTransport() throws Exception {
            try {
                Files.createDirectories(Path.of("/data/outgoing"));
                if (brokerMode) {
                    broker = brokerConnection();
                }
            } catch (Exception startupFailure) {
                ResourceCleanup.afterFailure(startupFailure, broker, client);
                throw startupFailure;
            }
        }

        @Override
        public void testConnection(OperationalConfig configuration, Session session) {
            if (brokerMode && !broker.isOpen()) {
                throw new IllegalStateException("The application broker connection is unavailable");
            }
        }

        @Override
        public void sendMessage(OperationalConfig configuration, Session session, Email email, MimeMessage message) {
            requireMappableOptions(email);
            try {
                HandoffPackage prepared = retainPreparedPackage(email, message);
                byte[] encoded = ExampleHttp.JSON.writeValueAsBytes(prepared);
                if (brokerMode) {
                    publishConfirmed(encoded);
                } else {
                    postToGateway(encoded);
                }
            } catch (Exception failed) {
                throw new IllegalStateException("The custom handoff did not confirm completion; inspect gateway/broker evidence before retrying", failed);
            }
        }

        private void requireMappableOptions(Email email) {
            if (email.getDeliveryStatusNotification() != null || email.isTlsRequiredForOnwardDelivery()
                    || email.getSendingToAcceptedRecipients() != null
                    || java.util.stream.Stream.concat(email.getRecipients().stream(), email.getOverrideReceivers().stream())
                        .anyMatch(recipient -> !recipient.getDeliveryStatusNotificationNotifyOptions().isEmpty())) {
                throw new IllegalArgumentException("This gateway has no SMTP envelope-option contract. Remove those options or use a gateway that honors them");
            }
        }

        private HandoffPackage retainPreparedPackage(Email email, MimeMessage message) throws Exception {
            String id = email.getId().replace("<", "").replace("@handoff.example.test>", "");
            Path path = Path.of("/data/outgoing", id + ".json");
            if (Files.exists(path)) {
                // The same application key reuses the same representation; it must not sign or encrypt a fresh replacement on redelivery.
                return ExampleHttp.JSON.readValue(Files.readAllBytes(path), HandoffPackage.class);
            }
            HandoffPackage prepared = preparePackage(id, email, message);
            retainPackage(path, prepared);
            return prepared;
        }

        private HandoffPackage preparePackage(String id, Email email, MimeMessage message) throws Exception {
            ByteArrayOutputStream serialized = new ByteArrayOutputStream();
            // Like SMTP, this handoff omits the outer Bcc header. The protected MIME body is not reconstructed or rewritten.
            message.writeTo(serialized, new String[]{"Bcc", "Content-Length"});
            List<String> recipients = (email.getOverrideReceivers().isEmpty() ? email.getRecipients() : email.getOverrideReceivers())
                    .stream().map(recipient -> recipient.getAddress()).toList();
            byte[] bytes = serialized.toByteArray();
            return new HandoffPackage(id, email.getBounceToRecipient().getAddress(), recipients, bytes, ProtectedContent.sha256(bytes));
        }

        private void retainPackage(Path path, HandoffPackage prepared) throws Exception {
            byte[] encoded = ExampleHttp.JSON.writeValueAsBytes(prepared);
            try (var file = java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.CREATE_NEW, java.nio.file.StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) {
                    file.write(buffer);
                }
                // Ask the filesystem to persist this representation before attempting a remote handoff. Disk failure still needs reconciliation.
                file.force(true);
            }
        }

        private void postToGateway(byte[] encoded) throws Exception {
            HttpRequest request = HttpRequest.newBuilder(URI.create("https://gateway:8443/packages"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(encoded))
                    .build();
            if (client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() != 200) {
                throw new IllegalStateException("The gateway rejected this prepared package");
            }
        }

        private void publishConfirmed(byte[] encoded) throws Exception {
            try (var channel = broker.createChannel()) {
                declarePreparedQueue(channel);
                channel.confirmSelect();
                channel.basicPublish("", "prepared-mail", com.rabbitmq.client.MessageProperties.PERSISTENT_BASIC, encoded);
                channel.waitForConfirmsOrDie(5000);
            }
        }

        @Override
        public void close() throws Exception {
            ResourceCleanup.closeAll(broker, client);
        }
    }
}
