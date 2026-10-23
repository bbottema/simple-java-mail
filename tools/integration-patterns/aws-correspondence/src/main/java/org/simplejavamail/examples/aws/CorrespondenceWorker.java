package org.simplejavamail.examples.aws;

import org.simplejavamail.api.mailer.config.ConnectionConfig;
import org.simplejavamail.api.mailer.config.ConnectionPoolConfig;
import org.simplejavamail.api.mailer.config.TlsConfig;
import org.simplejavamail.api.mailer.MailSendHandlers;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.mailer.MailSendOutcome;
import org.simplejavamail.api.mailer.MailSubmissionReceipt;
import org.simplejavamail.api.mailer.MailSubmissionStatus;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.examples.support.ExampleHttp;
import org.simplejavamail.examples.support.ProtectedContent;
import org.simplejavamail.examples.support.ResourceCleanup;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.Message;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Turn a queued document into one protected email and keep later delivery feedback separate from the send result.
 * Start at {@link #deliver(Message)}: claim the job, archive its prepared bytes, then submit them once.
 * The application owns claims and reconciliation; SJM owns message preparation and SMTP. AWS dependencies stay in this example.
 */
public final class CorrespondenceWorker implements AutoCloseable {
    private static final int MAX_DOCUMENT_BYTES = 128 * 1024;
    private final boolean local = Boolean.parseBoolean(ExampleHttp.environment("LOCAL_DEMO", "false"));
    private final Region region = Region.of(ExampleHttp.environment("AWS_REGION", "eu-central-1"));
    private final String bucket = ExampleHttp.environment("DOCUMENT_BUCKET", "sjm-correspondence-local");
    private final String table = ExampleHttp.environment("ATTEMPT_TABLE", "correspondence");
    private final SqsClient queues;
    private final DynamoDbClient database;
    private final S3Client objects;
    private final SimpleJavaMail mail = SimpleJavaMail.fromDefaults();
    private final Mailer mailer;
    private final String workQueue;
    private final String feedbackQueue;
    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ExampleHttp http;

    public CorrespondenceWorker() throws Exception {
        var queueBuilder = SqsClient.builder().region(region);
        var databaseBuilder = DynamoDbClient.builder().region(region);
        var objectBuilder = S3Client.builder().region(region);
        if (local) {
            queueBuilder.endpointOverride(URI.create(System.getenv("SQS_ENDPOINT")));
            databaseBuilder.endpointOverride(URI.create(System.getenv("DYNAMODB_ENDPOINT")));
            objectBuilder.endpointOverride(URI.create(System.getenv("S3_ENDPOINT")))
                    .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
        }
        queues = queueBuilder.build();
        database = databaseBuilder.build();
        objects = objectBuilder.build();
        if (local) {
            initializeLocalFixtures();
        }
        workQueue = local ? queues.getQueueUrl(request -> request.queueName("work")).queueUrl() : System.getenv("WORK_QUEUE_URL");
        feedbackQueue = local ? queues.getQueueUrl(request -> request.queueName("feedback")).queueUrl() : System.getenv("FEEDBACK_QUEUE_URL");
        mailer = buildMailer();
        http = local ? localEndpoints() : null;
    }

    public static void main(String[] args) throws Exception {
        CorrespondenceWorker application = new CorrespondenceWorker();
        Runtime.getRuntime().addShutdownHook(new Thread(application::close));
        application.workers.execute(application::consumeWork);
        application.workers.execute(application::consumeFeedback);
        if (application.http != null) {
            application.http.start();
        }
        System.out.println("Correspondence worker ready; SMTP acceptance and later SES feedback are separate facts");
    }

    private Mailer buildMailer() throws Exception {
        String username = "platform";
        String password = "local-demo-only";
        if (!local) {
            try (var secrets = SecretsManagerClient.builder().region(region).build()) {
                String secret = secrets.getSecretValue(request -> request.secretId(System.getenv("SMTP_SECRET_ARN"))).secretString();
                var credentials = ExampleHttp.JSON.readTree(secret);
                username = credentials.required("username").asText();
                password = credentials.required("password").asText();
            }
        }
        return mail.mailerBuilder()
                .withSMTPServer(ExampleHttp.environment("SMTP_HOST", "smtp-a"), 587, username, password)
                // A container's autogenerated hostname is not an appropriate SMTP client identity for every relay.
                .withConnectionConfig(ConnectionConfig.builder().withSmtpClientHostname("correspondence.example.test").build())
                .withTransportStrategy(TransportStrategy.SMTP_TLS)
                .withTlsConfig(TlsConfig.builder().verifyingServerIdentity(true).trustingAllHosts(false).build())
                .withConnectionPoolConfig(ConnectionPoolConfig.builder().withCoreSize(0).withMaxSize(1)
                        .withClaimTimeout(Duration.ofMillis(3000)).build())
                .withMailSendTimeout(Duration.ofSeconds(15))
                .withSendHandlers(MailSendHandlers.builder().withResultObserver(this::recordOutcome).build())
                .buildMailer();
    }

    private void recordOutcome(MailSendOutcome outcome) {
        MailSubmissionStatus submission = outcome.getSubmissionReceipt().map(MailSubmissionReceipt::getStatus).orElse(null);
        System.out.println("Logical send completed: successful=" + outcome.isSuccessful() + ", submission=" + submission);
        String messageId = outcome.getInitialMessageId();
        if (messageId == null || !messageId.matches("<[A-Za-z0-9_-]{1,100}@correspondence\\.example\\.test>")) {
            throw new IllegalArgumentException("This worker only records outcomes for its fixed correspondence Message-IDs");
        }
        String id = messageId.substring(1, messageId.indexOf('@'));
        Map<String, AttributeValue> facts = terminalFacts(outcome.isSuccessful(), submission);
        outcome.getFailure().ifPresent(failure -> facts.put("failureTypes", text(failureTypes(failure))));
        update(id, facts);
    }

    static Map<String, AttributeValue> terminalFacts(boolean successful, MailSubmissionStatus submission) {
        // Cleanup can fail after acceptance. Keep the operation result and SMTP evidence as separate facts.
        Map<String, AttributeValue> facts = new LinkedHashMap<>();
        facts.put("status", text(successful ? "SUBMITTED" : "HELD"));
        facts.put("smtpStatus", text(submission == null ? "NO_RELIABLE_RECEIPT" : submission.name()));
        return facts;
    }

    private void initializeLocalFixtures() {
        createLocalQueue("work");
        createLocalQueue("feedback");
        try {
            database.createTable(request -> request.tableName(table).billingMode(BillingMode.PAY_PER_REQUEST)
                    .attributeDefinitions(AttributeDefinition.builder().attributeName("jobId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("kind").attributeType(ScalarAttributeType.S).build())
                    .keySchema(KeySchemaElement.builder().attributeName("jobId").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("kind").keyType(KeyType.RANGE).build()));
        } catch (ResourceInUseException existing) {
            // Only local fixture provisioning; cloud resources belong to infrastructure code.
        }
        try {
            objects.createBucket(request -> request.bucket(bucket));
        } catch (software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException existing) {
            // Retain the fixture's existing synthetic objects on a local restart.
        }
    }

    private void createLocalQueue(String name) {
        String dead = queues.createQueue(request -> request.queueName(name + "-dead")).queueUrl();
        String arn = queues.getQueueAttributes(request -> request.queueUrl(dead)
                .attributeNames(software.amazon.awssdk.services.sqs.model.QueueAttributeName.QUEUE_ARN))
                .attributes().get(software.amazon.awssdk.services.sqs.model.QueueAttributeName.QUEUE_ARN);
        queues.createQueue(request -> request.queueName(name).attributes(Map.of(
                software.amazon.awssdk.services.sqs.model.QueueAttributeName.VISIBILITY_TIMEOUT, "5",
                software.amazon.awssdk.services.sqs.model.QueueAttributeName.REDRIVE_POLICY,
                "{\"deadLetterTargetArn\":\"" + arn + "\",\"maxReceiveCount\":3}")));
    }

    private void consumeWork() {
        while (running.get()) {
            try {
                for (Message message : queues.receiveMessage(request -> request.queueUrl(workQueue).waitTimeSeconds(2)
                        .visibilityTimeout(60).maxNumberOfMessages(1)).messages()) {
                    deliver(message);
                }
            } catch (RuntimeException failed) {
                pollingFailure("Work polling", failed);
            }
        }
    }

    private void deliver(Message message) {
        Job job;
        try {
            job = ExampleHttp.JSON.readValue(message.body(), Job.class);
            validateJob(job);
        } catch (Exception malformed) {
            System.err.println("Invalid work request; queue redrive owns poison-message handling");
            return;
        }
        if (!claim(job)) {
            // A claim records that this application took responsibility. It never expires into permission to send again:
            // SQS making an unacknowledged item visible again does not prove the previous SMTP attempt was unsent.
            queues.deleteMessage(request -> request.queueUrl(workQueue).receiptHandle(message.receiptHandle()));
            return;
        }
        try {
            prepareArchiveAndSubmit(job);
            queues.deleteMessage(request -> request.queueUrl(workQueue).receiptHandle(message.receiptHandle()));
        } catch (RuntimeException failed) {
            // Preserve CLAIMED/SENDING and the durable guard if evidence persistence itself failed.
            System.err.println("Claimed work needs reconciliation: " + failed.getClass().getSimpleName());
        }
    }

    private void validateJob(Job job) {
        String suffix = local ? "@example.test" : "@simulator.amazonses.com";
        if (job.id() == null || !job.id().matches("[A-Za-z0-9_-]{1,100}") || job.recipient() == null
                || !job.recipient().endsWith(suffix) || !("documents/" + job.id() + ".txt").equals(job.documentKey())) {
            throw new IllegalArgumentException("Use this demo's synthetic recipient and document namespace");
        }
    }

    private boolean claim(Job job) {
        try {
            database.putItem(request -> request.tableName(table).conditionExpression("attribute_not_exists(jobId)")
                    .item(Map.of("jobId", text(job.id()), "kind", text("JOB"), "recipient", text(job.recipient()),
                            "status", text("CLAIMED"), "documentKey", text(job.documentKey()))));
            return true;
        } catch (ConditionalCheckFailedException duplicate) {
            return false;
        }
    }

    private void prepareArchiveAndSubmit(Job job) {
        final Email exact;
        try {
            byte[] document = readSmallDocument(job);
            Email composed = composeProtectedCorrespondence(job, document);
            byte[] prepared = prepareSmtpBytes(composed);
            archivePreparedBytes(job, prepared);
            exact = createExactSubmission(job, prepared);
            markSubmissionStarted(job, prepared);
        } catch (RuntimeException failedBeforeSubmission) {
            update(job.id(), Map.of("status", text("HELD_PREPARATION"), "failureTypes", text(failureTypes(failedBeforeSubmission))));
            return;
        }

        try {
            // The inline result observer persists the authoritative receipt, including acceptance followed by cleanup failure.
            mailer.sync().sendMail(exact);
        } catch (RuntimeException failed) {
            System.err.println("SMTP attempt held: " + failureTypes(failed));
        }
        // Failed observer persistence leaves the sticky SENDING guard for reconciliation, never permission to replay.
    }

    private Email composeProtectedCorrespondence(Job job, byte[] document) {
        var builder = mail.emailBuilder().startingBlank()
                .from(ExampleHttp.environment("MAIL_FROM", "sender@example.test"))
                .withBounceTo(ExampleHttp.environment("MAIL_FROM", "sender@example.test"))
                .withRecipients(mail.recipientBuilder().withAddress(job.recipient())
                        .withType(jakarta.mail.Message.RecipientType.TO).build())
                .fixingMessageId("<" + job.id() + "@correspondence.example.test>")
                .withSubject("Synthetic protected correspondence")
                .withPlainText("Synthetic partner document attached")
                .withAttachment("document.txt", document, "text/plain")
                .signWithSmime(ProtectedContent.signing())
                .encryptWithSmime(ProtectedContent.encryption());
        if (!local) {
            // SES returns this application tag in later feedback. It does not make SMTP/database work transactional.
            builder.withHeader("X-SES-CONFIGURATION-SET", System.getenv("SES_CONFIGURATION_SET"))
                    .withHeader("X-SES-MESSAGE-TAGS", "sjm_attempt=" + job.id());
        }
        return builder.buildEmail();
    }

    private byte[] prepareSmtpBytes(Email composed) {
        // Rehearse once, then archive and submit the same representation. Do not recompose or resign an exact archived message.
        return terminateSmtpLine(mailer.rehearse(composed).getEmlBytes());
    }

    private void archivePreparedBytes(Job job, byte[] prepared) {
        // Retain the evidence before SMTP can accept anything. Failure to archive must stop this attempt before provider invocation.
        objects.putObject(request -> request.bucket(bucket).key("archive/" + job.id() + ".eml")
                .serverSideEncryption(ServerSideEncryption.AES256)
                .contentType("message/rfc822"), RequestBody.fromBytes(prepared));
    }

    private Email createExactSubmission(Job job, byte[] prepared) {
        return mail.emailBuilder().startingFromExactEml(prepared)
                .withEnvelopeSender(ExampleHttp.environment("MAIL_FROM", "sender@example.test"))
                .withEnvelopeRecipients(mail.recipientBuilder().withAddress(job.recipient())
                        .withType(jakarta.mail.Message.RecipientType.TO).build())
                .buildEmail();
    }

    private void markSubmissionStarted(Job job, byte[] prepared) {
        update(job.id(), Map.of("status", text("SENDING"), "archiveSha256", text(ProtectedContent.sha256(prepared))));
    }

    private byte[] readSmallDocument(Job job) {
        try (var input = objects.getObject(request -> request.bucket(bucket).key(job.documentKey()))) {
            byte[] document = input.readNBytes(MAX_DOCUMENT_BYTES + 1);
            if (document.length > MAX_DOCUMENT_BYTES) {
                throw new IllegalArgumentException("This fixture uses small synthetic documents");
            }
            return document;
        } catch (java.io.IOException failed) {
            throw new IllegalStateException("The synthetic document could not be read", failed);
        }
    }

    private static String failureTypes(Throwable failure) {
        // Class names are enough to distinguish setup/configuration failures without recording credentials or provider replies.
        List<String> types = new ArrayList<>();
        for (Throwable current = failure; current != null && types.size() < 8; current = nestedFailure(current)) {
            StackTraceElement[] frames = current.getStackTrace();
            String origin = Arrays.stream(frames).limit(3)
                    .map(frame -> frame.getClassName() + "." + frame.getMethodName() + ":" + frame.getLineNumber())
                    .collect(java.util.stream.Collectors.joining(" <- ", " at ", ""));
            types.add(current.getClass().getSimpleName() + origin);
        }
        return String.join(" -> ", types);
    }

    private static Throwable nestedFailure(Throwable failure) {
        // Jakarta Mail's older exception chain can store setup evidence in nextException rather than Throwable.cause.
        if (failure.getCause() != null) {
            return failure.getCause();
        }
        if (failure instanceof jakarta.mail.MessagingException mailFailure) {
            return mailFailure.getNextException();
        }
        return null;
    }

    private static byte[] terminateSmtpLine(byte[] bytes) {
        if (bytes.length >= 2 && bytes[bytes.length - 2] == '\r' && bytes[bytes.length - 1] == '\n') {
            return bytes;
        }
        // Complete only the newly composed representation's final SMTP line before it becomes the authoritative archived copy.
        byte[] terminated = Arrays.copyOf(bytes, bytes.length + 2);
        terminated[bytes.length] = '\r';
        terminated[bytes.length + 1] = '\n';
        return terminated;
    }

    private void update(String id, Map<String, AttributeValue> changes) {
        Map<String, String> names = new LinkedHashMap<>();
        Map<String, AttributeValue> values = new LinkedHashMap<>();
        List<String> assignments = new ArrayList<>();
        changes.forEach((name, value) -> {
            names.put("#" + name, name);
            values.put(":" + name, value);
            assignments.add("#" + name + "=:" + name);
        });
        database.updateItem(request -> request.tableName(table).key(key(id, "JOB")).conditionExpression("attribute_exists(jobId)")
                .updateExpression("SET " + String.join(",", assignments)).expressionAttributeNames(names).expressionAttributeValues(values));
    }

    private void consumeFeedback() {
        while (running.get()) {
            try {
                for (Message message : queues.receiveMessage(request -> request.queueUrl(feedbackQueue).waitTimeSeconds(2)).messages()) {
                    recordFeedback(message.body());
                    queues.deleteMessage(request -> request.queueUrl(feedbackQueue).receiptHandle(message.receiptHandle()));
                }
            } catch (Exception failed) {
                pollingFailure("Feedback", failed);
            }
        }
    }

    private void pollingFailure(String boundary, Exception failure) {
        if (!running.get()) {
            return;
        }
        System.err.println(boundary + " needs review: " + failureTypes(failure));
        try {
            TimeUnit.SECONDS.sleep(1);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            running.set(false);
        }
    }

    private void recordFeedback(String json) throws Exception {
        SesFeedback feedback = readFeedback(json);
        recordCorrelatedFeedback(feedback);
    }

    private SesFeedback readFeedback(String json) throws Exception {
        var event = ExampleHttp.JSON.readTree(json);
        String type = event.path("eventType").asText();
        String id = event.path("mail").path("tags").path("sjm_attempt").path(0).asText();
        String destination = event.path("mail").path("destination").path(0).asText();
        if (!List.of("Send", "Delivery", "Bounce", "Complaint", "DeliveryDelay").contains(type)
                || !id.matches("[A-Za-z0-9_-]{1,100}") || event.path("mail").path("destination").size() != 1) {
            System.err.println("Uncorrelated or unsupported SES event retained by queue redrive");
            throw new IllegalArgumentException("No trusted application correlation");
        }
        return new SesFeedback(id, type, destination, event.path("mail").path("messageId").asText(),
                "EVENT#" + ProtectedContent.sha256(json.getBytes(StandardCharsets.UTF_8)));
    }

    private void recordCorrelatedFeedback(SesFeedback feedback) {
        // The transaction both verifies the intended recipient and creates the event once. A guessed tag cannot update another job.
        var intendedRecipient = ConditionCheck.builder().tableName(table).key(key(feedback.jobId(), "JOB"))
                .conditionExpression("recipient=:recipient")
                .expressionAttributeValues(Map.of(":recipient", text(feedback.recipient())))
                .build();
        var newEvent = Put.builder().tableName(table)
                .conditionExpression("attribute_not_exists(jobId)")
                .item(Map.of("jobId", text(feedback.jobId()), "kind", text(feedback.eventKey()), "eventType", text(feedback.type()),
                        "sesMessageId", text(feedback.sesMessageId())))
                .build();
        try {
            database.transactWriteItems(request -> request.transactItems(
                    TransactWriteItem.builder().conditionCheck(intendedRecipient).build(),
                    TransactWriteItem.builder().put(newEvent).build()));
        } catch (TransactionCanceledException collision) {
            if (database.getItem(request -> request.tableName(table).key(key(feedback.jobId(), feedback.eventKey()))
                    .consistentRead(true)).hasItem()) {
                return; // The same trusted event was already recorded; acknowledging redelivery does not apply it twice.
            }
            throw collision;
        }
    }

    private List<Map<String, Object>> evidence() {
        return database.scan(request -> request.tableName(table).consistentRead(true).filterExpression("kind=:job")
                .expressionAttributeValues(Map.of(":job", text("JOB")))).items().stream().map(row -> {
            String id = row.get("jobId").s();
            List<String> events = database.query(request -> request.tableName(table).consistentRead(true)
                    .keyConditionExpression("jobId=:id AND begins_with(kind,:event)")
                    .expressionAttributeValues(Map.of(":id", text(id), ":event", text("EVENT#")))).items().stream()
                    .map(item -> item.get("eventType").s()).toList();
            Map<String, Object> result = new LinkedHashMap<>();
            row.forEach((name, value) -> result.put(name, value.s()));
            result.put("events", events);
            result.put("delivery", deliveryProjection(events));
            result.put("complaint", events.contains("Complaint"));
            return result;
        }).toList();
    }

    private static String deliveryProjection(List<String> events) {
        if (events.contains("Bounce") && events.contains("Delivery")) {
            return "REVIEW";
        }
        if (events.contains("Bounce")) {
            return "FAILED";
        }
        if (events.contains("Delivery")) {
            return "DELIVERED";
        }
        if (events.contains("DeliveryDelay")) {
            return "DELAYED";
        }
        return "UNREPORTED";
    }

    private ExampleHttp localEndpoints() throws Exception {
        ExampleHttp server = new ExampleHttp();
        server.route("/jobs", request -> {
            if (request.method().equals("POST")) {
                String id = request.parameters().getOrDefault("id", UUID.randomUUID().toString());
                Job job = new Job(id, request.parameters().getOrDefault("recipient", "reader@example.test"), "documents/" + id + ".txt");
                validateJob(job);
                objects.putObject(put -> put.bucket(bucket).key(job.documentKey()), RequestBody.fromString("Synthetic confidential document"));
                queues.sendMessage(send -> send.queueUrl(workQueue).messageBody(encode(job)));
            }
            return evidence();
        });
        server.route("/reports", request -> {
            queues.sendMessage(send -> send.queueUrl(feedbackQueue).messageBody(new String(request.body(), StandardCharsets.UTF_8)));
            return Map.of("queued", true);
        });
        server.route("/archive-proof", request -> {
            String id = request.parameters().get("id");
            byte[] bytes = objects.getObjectAsBytes(get -> get.bucket(bucket).key("archive/" + id + ".eml")).asByteArray();
            var parsed = ProtectedContent.readProtected(bytes);
            return Map.of("sha256", ProtectedContent.sha256(bytes), "verification", parsed.getOriginalSmimeDetails().getVerificationStatus().name(),
                    "readable", parsed.getPlainText() != null);
        });
        server.route("/queue-evidence", request -> {
            String dead = queues.getQueueUrl(get -> get.queueName("feedback-dead")).queueUrl();
            String count = queues.getQueueAttributes(get -> get.queueUrl(dead)
                    .attributeNames(software.amazon.awssdk.services.sqs.model.QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES))
                    .attributes().get(software.amazon.awssdk.services.sqs.model.QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES);
            return Map.of("feedbackDeadLetters", Integer.parseInt(count));
        });
        return server;
    }

    private String encode(Job job) {
        try {
            return ExampleHttp.JSON.writeValueAsString(job);
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static AttributeValue text(String value) {
        return AttributeValue.builder().s(value).build();
    }

    private static Map<String, AttributeValue> key(String id, String kind) {
        return Map.of("jobId", text(id), "kind", text(kind));
    }

    @Override
    public void close() {
        running.set(false);
        workers.shutdown();
        try {
            ResourceCleanup.closeAll(http, this::awaitWorkers, mailer, queues, database, objects);
        } catch (Exception failed) {
            throw new IllegalStateException("Cloud worker resources did not close cleanly", failed);
        }
    }

    private void awaitWorkers() throws InterruptedException {
        if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Cloud worker has not drained");
        }
    }

    private record SesFeedback(String jobId, String type, String recipient, String sesMessageId, String eventKey) {
    }

    public record Job(String id, String recipient, String documentKey) {
    }
}
