package org.simplejavamail.examples.patterns;

import org.apache.camel.ProducerTemplate;
import org.apache.camel.builder.RouteBuilder;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.config.SmimeEncryptionConfig;
import org.simplejavamail.api.email.config.SmimeSigningConfig;
import org.simplejavamail.api.mailer.config.Pkcs12Config;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Duration;

/** Lets Camel coordinate protected submission, then lets the application check one exact received document and retain original input. */
@Configuration
public class PartnerDocuments {
    @Bean
    PatternPorts.WorkTrigger partnerTrigger(ProducerTemplate routes) {
        return id -> routes.sendBody("seda:partner", id);
    }

    @Bean
    RouteBuilder partnerRoute(MailWork work, JobStore jobs, PartnerDocumentPolicy documents) {
        SmimeSigningConfig signing = SmimeSigningConfig.builder()
                .pkcs12Config(demoKeys("partner.p12"))
                .build();
        SmimeEncryptionConfig encryption = SmimeEncryptionConfig.builder()
                .x509Certificate(Path.of("/opt/demo/partner.crt")).build();
        return new RouteBuilder() {
            @Override
            public void configure() {
                from("seda:partner?concurrentConsumers=1").routeId("partner-documents")
                        .process(exchange -> work.deliverPartnerDocument(exchange.getIn().getBody(String.class), signing, encryption))
                        .process(exchange -> archiveVerifiedCopy(exchange.getIn().getBody(String.class), jobs, documents));
            }
        };
    }

    @Bean
    PartnerDocumentPolicy partnerDocumentPolicy(SimpleJavaMail mail) throws Exception {
        // Explicit local application approval. A real application supplies its partner's independently approved signing certificate.
        final X509Certificate certificate;
        try (var input = Files.newInputStream(Path.of("/opt/demo/partner.crt"))) {
            certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        return new PartnerDocumentPolicy(mail, demoKeys("partner.p12"), certificate);
    }

    @Bean
    PartnerDocumentFixtures partnerDocumentFixtures(SimpleJavaMail mail) throws Exception {
        return new PartnerDocumentFixtures(mail, demoKeys("partner.p12"), demoKeys("unapproved-partner.p12"));
    }

    private static Pkcs12Config demoKeys(String filename) {
        return Pkcs12Config.builder().pkcs12Store(Path.of("/opt/demo").resolve(filename))
                .storePassword("local-demo-only").keyAlias("partner").keyPassword("local-demo-only").build();
    }

    private void archiveVerifiedCopy(String id, JobStore jobs, PartnerDocumentPolicy documents) throws Exception {
        if (!"SUBMITTED".equals(jobs.get(id).get("state"))) {
            return;
        }
        String attempt = jobs.get(id).get("attempt_id").toString();
        byte[] received = readCapturedMessage(attempt);
        PartnerDocumentPolicy.Decision decision = documents.receive(received, Path.of("/data"), id, true);
        if (!"PROCESSED".equals(decision.action())) {
            jobs.event(id, "PARTNER_DOCUMENT_HELD", "Document acceptance needs review: " + decision.reasons());
            return;
        }
        jobs.event(id, "PARTNER_DOCUMENT_PROCESSED", "The required document meets the application's approved-key and protection policy");
        jobs.event(id, "PROTECTED_ARCHIVED", "Retained original received bytes alongside the document decision; SMTP evidence remains separate");
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

}
