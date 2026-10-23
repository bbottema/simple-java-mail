package org.simplejavamail.examples.patterns;

import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/** Lets Camel coordinate a signed submission, then verifies and archives the exact bytes captured by the local mail fixture. */
@Configuration
public class PartnerDocuments {
    @Bean
    PatternPorts.WorkTrigger partnerTrigger(ProducerTemplate routes) {
        return id -> routes.sendBody("seda:partner", id);
    }

    @Bean
    RouteBuilder partnerRoute(MailWork work, JobStore jobs, SimpleJavaMail mail) {
        SmimeSigningConfig signing = SmimeSigningConfig.builder()
                .pkcs12Config(Pkcs12Config.builder()
                        .pkcs12Store(Path.of("/opt/demo/partner.p12"))
                        .storePassword("local-demo-only")
                        .keyAlias("partner")
                        .keyPassword("local-demo-only")
                        .build())
                .build();
        return new RouteBuilder() {
            @Override
            public void configure() {
                from("seda:partner?concurrentConsumers=1").routeId("partner-documents")
                        .process(exchange -> work.deliver(exchange.getIn().getBody(String.class), signing))
                        .process(exchange -> archiveVerifiedCopy(exchange.getIn().getBody(String.class), jobs, mail));
            }
        };
    }

    private void archiveVerifiedCopy(String id, JobStore jobs, SimpleJavaMail mail) throws Exception {
        if (!"SUBMITTED".equals(jobs.get(id).get("state"))) {
            return;
        }
        String attempt = jobs.get(id).get("attempt_id").toString();
        byte[] received = readCapturedMessage(attempt);
        verifySignature(received, mail);
        archiveCapturedBytes(id, received);
        jobs.event(id, "PROTECTED_ARCHIVED", "Signature valid; archived the captured bytes without rebuilding MIME");
    }

    private byte[] readCapturedMessage(String attempt) throws Exception {
        // This HTTP API belongs to the synthetic fixture. SMTP itself has no 'download the sent message' operation.
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://smtp:8025/messages/" + attempt))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("The local fixture did not return the captured message; nothing was archived");
            }
            return response.body();
        }
    }

    private void verifySignature(byte[] received, SimpleJavaMail mail) {
        Email parsed = mail.converter()
                .fromEml(new ByteArrayInputStream(received))
                .toEmail();
        if (parsed.getOriginalSmimeDetails().getVerificationStatus() != OriginalSmimeDetails.VerificationStatus.VALID) {
            throw new IllegalStateException("The captured partner document did not verify; it was not archived as valid");
        }
    }

    private void archiveCapturedBytes(String id, byte[] received) throws Exception {
        Path archive = Path.of("/data/archive");
        Files.createDirectories(archive);
        Files.write(archive.resolve(id + ".eml"), received);
    }
}
