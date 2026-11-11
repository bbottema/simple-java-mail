package org.simplejavamail.internal.protection;

import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import lombok.Builder;
import lombok.Getter;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.DecryptionInspection;
import org.simplejavamail.api.email.SignatureObservation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.IOException;

import static java.util.Objects.requireNonNull;

/** One transient provider operation. The importer owns roles, traversal and binding these facts to captured sources. */
@ApiStatus.Internal
@Getter
public final class ProtectionLayerResult {
    private final boolean recognized;
    private final boolean opened;
    private final MimeMessage effectiveMimeMessage;
    @Nullable private final MimeBodyPart openedEntity;
    /** Recognizes an authored message entity. Its headers become available when the layer opens. */
    private final boolean authoredHeaders;
    /** This layer has a recognized signature envelope. Verification can remain incomplete. */
    private final boolean signatureEnvelope;
    private final boolean inspectionComplete;
    private final List<SignatureObservation> signatures;
    private final List<DecryptionInspection> decryptions;
    @Nullable private final String failureReason;
    @Nullable private final Throwable processingFailure;
    @Nullable private final IOException inputReadFailure;
    @Nullable private final IOException inputCloseFailure;

    @Builder
    private ProtectionLayerResult(final boolean recognized, final boolean opened,
            final MimeMessage effectiveMimeMessage, @Nullable final MimeBodyPart openedEntity,
            final boolean authoredHeaders, final boolean signatureEnvelope, final boolean inspectionComplete,
            @Nullable final List<SignatureObservation> signatures,
            @Nullable final List<DecryptionInspection> decryptions, @Nullable final String failureReason,
            @Nullable final Throwable processingFailure, @Nullable final IOException inputReadFailure,
            @Nullable final IOException inputCloseFailure) {
        this.recognized = recognized;
        this.opened = opened;
        this.effectiveMimeMessage = requireNonNull(effectiveMimeMessage, "effectiveMimeMessage");
        this.openedEntity = openedEntity;
        this.authoredHeaders = authoredHeaders;
        this.signatureEnvelope = signatureEnvelope;
        this.inspectionComplete = inspectionComplete;
        this.signatures = immutable(signatures);
        this.decryptions = immutable(decryptions);
        this.failureReason = failureReason;
        this.processingFailure = processingFailure;
        this.inputReadFailure = inputReadFailure;
        this.inputCloseFailure = inputCloseFailure;
    }

    private static <T> List<T> immutable(@Nullable final List<T> values) {
        return values == null ? Collections.emptyList() : Collections.unmodifiableList(new ArrayList<>(values));
    }
}
