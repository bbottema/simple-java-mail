package org.simplejavamail.api.internal.smimesupport.model;

import jakarta.mail.internet.MimeMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.internal.protectionsupport.model.InboundProtectionResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Result of pre-parse S/MIME recognition, verification, and decryption. */
public final class SmimePreprocessingResult implements InboundProtectionResult<OriginalSmimeDetails> {
    private final boolean recognized;
    private final MimeMessage effectiveMimeMessage;
    private final OriginalSmimeDetails details;
    private final OriginalSmimeDetails nestedSignedDetails;
    private final List<AttachmentResource> protectedAttachments;
    private final List<AttachmentResource> decryptedArtifacts;

    public SmimePreprocessingResult(final boolean recognized,
                                    @NotNull final MimeMessage effectiveMimeMessage,
                                    @NotNull final OriginalSmimeDetails details,
                                    @NotNull final List<AttachmentResource> protectedAttachments,
                                    @NotNull final List<AttachmentResource> decryptedArtifacts) {
        this(recognized, effectiveMimeMessage, details, protectedAttachments, decryptedArtifacts, null);
    }

    public SmimePreprocessingResult(final boolean recognized,
                                    @NotNull final MimeMessage effectiveMimeMessage,
                                    @NotNull final OriginalSmimeDetails details,
                                    @NotNull final List<AttachmentResource> protectedAttachments,
                                    @NotNull final List<AttachmentResource> decryptedArtifacts,
                                    @Nullable final OriginalSmimeDetails nestedSignedDetails) {
        this.recognized = recognized;
        this.effectiveMimeMessage = requireNonNull(effectiveMimeMessage, "effectiveMimeMessage");
        this.details = requireNonNull(details, "details");
        this.protectedAttachments = immutableCopy(protectedAttachments);
        this.decryptedArtifacts = immutableCopy(decryptedArtifacts);
        this.nestedSignedDetails = nestedSignedDetails;
    }

    @Override
    public boolean isRecognized() { return recognized; }
    @Override
    @NotNull public MimeMessage getEffectiveMimeMessage() { return effectiveMimeMessage; }
    @Override
    @NotNull public OriginalSmimeDetails getDetails() { return details; }
    @Nullable public OriginalSmimeDetails getNestedSignedDetails() { return nestedSignedDetails; }
    @NotNull public List<AttachmentResource> getProtectedAttachments() { return protectedAttachments; }
    @NotNull public List<AttachmentResource> getDecryptedArtifacts() { return decryptedArtifacts; }

    private static List<AttachmentResource> immutableCopy(final List<AttachmentResource> source) {
        return Collections.unmodifiableList(new ArrayList<>(requireNonNull(source, "source")));
    }
}
