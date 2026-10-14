package org.simplejavamail.internal.smimesupport;

import jakarta.mail.internet.MimeMessage;
import lombok.Builder;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.AttachmentResource;
import org.simplejavamail.api.email.OriginalSmimeDetails;
import org.simplejavamail.api.internal.smimesupport.model.SmimePreprocessingResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Result of pre-parse S/MIME recognition, verification, and decryption. */
@ApiStatus.Internal
public final class SmimePreprocessingResultImpl implements SmimePreprocessingResult {
    private final boolean recognized;
    private final MimeMessage effectiveMimeMessage;
    private final OriginalSmimeDetails details;
    private final OriginalSmimeDetails nestedSignedDetails;
    private final List<AttachmentResource> protectedAttachments;
    private final List<AttachmentResource> decryptedArtifacts;

    @Builder
	private SmimePreprocessingResultImpl(final boolean recognized,
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

	/** @see SmimePreprocessingResult#isRecognized() */
	@Override
	public boolean isRecognized() { return recognized; }
	/** @see SmimePreprocessingResult#getEffectiveMimeMessage() */
	@Override
	@NotNull
	public MimeMessage getEffectiveMimeMessage() { return effectiveMimeMessage; }
	/** @see SmimePreprocessingResult#getDetails() */
	@Override
	@NotNull
	public OriginalSmimeDetails getDetails() { return details; }
	/** @see SmimePreprocessingResult#getNestedSignedDetails() */
	@Override
	@Nullable
	public OriginalSmimeDetails getNestedSignedDetails() { return nestedSignedDetails; }
	/** @see SmimePreprocessingResult#getProtectedAttachments() */
	@Override
	@NotNull
	public List<AttachmentResource> getProtectedAttachments() { return protectedAttachments; }
	/** @see SmimePreprocessingResult#getDecryptedArtifacts() */
	@Override
	@NotNull
	public List<AttachmentResource> getDecryptedArtifacts() { return decryptedArtifacts; }

    private static List<AttachmentResource> immutableCopy(final List<AttachmentResource> source) {
        return Collections.unmodifiableList(new ArrayList<>(requireNonNull(source, "source")));
    }
}
