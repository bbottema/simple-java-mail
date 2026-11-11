package org.simplejavamail.examples.patterns;

import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.ApprovedSigners;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.ProtectionCheck;
import org.simplejavamail.api.email.ProtectionRequirements;
import org.simplejavamail.api.email.ValueInspection;
import org.simplejavamail.api.mailer.config.Pkcs12Config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** The application owns partner approval, the required-document contract, storage and its process-or-hold decision. */
public final class PartnerDocumentPolicy {
    private final SimpleJavaMail mail;
    private final Pkcs12Config recipientKeys;
    private final ProtectionRequirements baseRequirements;
    private final ProtectionRequirements processingRequirements;

    public PartnerDocumentPolicy(SimpleJavaMail mail, Pkcs12Config recipientKeys, X509Certificate approvedPartner) {
        this.mail = mail;
        this.recipientKeys = recipientKeys;
        ApprovedSigners partner = ApprovedSigners.builder().addSmimeCertificate(approvedPartner).build();
        baseRequirements = ProtectionRequirements.builder()
                .requireVerifiedSignatureFrom(partner).requireDecryptedContent().build();
        // This application's stricter choice holds any relevant invalid/unresolved signature. MIXED is not a universal SJM rejection rule.
        processingRequirements = baseRequirements.toBuilder().requireAllSignaturesVerified().build();
    }

    public Decision receive(byte[] input, Path storage, String id, boolean supplyDecryptionKey) throws Exception {
        if (!id.matches("[A-Za-z0-9_-]{1,100}")) {
            throw new IllegalArgumentException("Use a bounded application document ID");
        }
        var conversion = mail.converter().fromEml(input);
        if (supplyDecryptionKey) {
            conversion.withSmimeDecryption(recipientKeys);
        }
        Email received = conversion.toEmail();
        List<String> processingProblems = received.getProblems().stream().map(problem -> problem.getCode().name()).toList();
        boolean originalPreserved = retainOriginal(received, input, storage, id);
        if (!originalPreserved) {
            // Retaining the actual input is this application's prerequisite for processing, separate from library protection requirements.
            return new Decision("HELD", null, null, List.of("ORIGINAL_INPUT_UNAVAILABLE"),
                    "UNKNOWN", "UNKNOWN", false, 0, processingProblems);
        }
        int index = findRequiredDocument(received.getAttachments());
        if (index < 0) {
            return new Decision("HELD", null, null, List.of("REQUIRED_DOCUMENT_UNAVAILABLE"),
                    "UNKNOWN", "UNKNOWN", originalPreserved, 0, processingProblems);
        }

        ValueInspection document = received.getInspection().getAttachment(index);
        ProtectionCheck base = baseRequirements.check(document);
        ProtectionCheck processing = processingRequirements.check(document);
        long processedBytes = 0;
        if (processing.isSatisfied()) {
            byte[] content = received.getAttachments().get(index).readAllBytes();
            Files.createDirectories(storage.resolve("processed"));
            Files.write(storage.resolve("processed").resolve(id + ".txt"), content);
            processedBytes = content.length;
        }
        List<String> reasons = processing.getReasons().stream().map(reason -> reason.getCode().name()).toList();
        return new Decision(processing.isSatisfied() ? "PROCESSED" : "HELD", processing.getOutcome().name(), base.getOutcome().name(),
                reasons, document.getSignatureStatus().name(), document.getEncryptionStatus().name(), originalPreserved, processedBytes, processingProblems);
    }

    private boolean retainOriginal(Email received, byte[] input, Path storage, String id) throws Exception {
        AttachmentResource original = received.getInspection().getOriginalMessage();
        if (original == null) {
            return false;
        }
        byte[] captured = original.readAllBytes();
        if (!Arrays.equals(input, captured)) {
            throw new IllegalStateException("The retained input differs from the supplied original; no archive was written");
        }
        Files.createDirectories(storage.resolve("archive"));
        Files.write(storage.resolve("archive").resolve(id + ".eml"), captured);
        return true;
    }

    private int findRequiredDocument(List<AttachmentResource> attachments) {
        List<Integer> candidates = new ArrayList<>();
        for (int index = 0; index < attachments.size(); index++) {
            if ("partner-order.txt".equals(attachments.get(index).getName())) {
                candidates.add(index);
            }
        }
        // An ambiguous duplicate name is held under this example's document contract, never matched by resource equality or payload reads.
        return candidates.size() == 1 ? candidates.getFirst() : -1;
    }

    /** A small application projection; no Email, crypto provider object or key is serialized into the HTTP response. */
    public record Decision(String action, String outcome, String baseOutcome, List<String> reasons, String signature,
            String encryption, boolean originalPreserved, long processedBytes, List<String> processingProblems) { }
}
