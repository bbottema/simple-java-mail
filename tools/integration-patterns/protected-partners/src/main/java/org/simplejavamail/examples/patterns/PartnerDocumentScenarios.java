package org.simplejavamail.examples.patterns;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.util.UUID;

/** Local synthetic receiving exercises. These trusted-input endpoints are not a production mail-receiving service. */
@RestController
public class PartnerDocumentScenarios {
    private final PartnerDocumentPolicy documents;
    private final PartnerDocumentFixtures fixtures;

    public PartnerDocumentScenarios(PartnerDocumentPolicy documents, PartnerDocumentFixtures fixtures) {
        this.documents = documents;
        this.fixtures = fixtures;
    }

    @PostMapping("/partner-documents/scenarios/{scenario}")
    public ScenarioResult inspect(@PathVariable("scenario") String scenario) throws Exception {
        byte[] original = fixtures.message(scenario);
        String id = "received_" + UUID.randomUUID().toString().replace("-", "");
        PartnerDocumentPolicy.Decision decision = documents.receive(original, Path.of("/data"), id,
                !"unavailable-decryption".equals(scenario));
        return new ScenarioResult(scenario, id, decision);
    }

    public record ScenarioResult(String scenario, String documentId, PartnerDocumentPolicy.Decision document) { }
}
