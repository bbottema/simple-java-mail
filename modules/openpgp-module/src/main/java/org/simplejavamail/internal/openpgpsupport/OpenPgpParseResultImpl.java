package org.simplejavamail.internal.openpgpsupport;

import jakarta.mail.internet.MimeMessage;
import lombok.Builder;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;
import org.simplejavamail.api.internal.openpgpsupport.model.OpenPgpParseResult;

import static java.util.Objects.requireNonNull;

/** Result of pre-parse OpenPGP/MIME recognition, verification, and decryption. */
@ApiStatus.Internal
public final class OpenPgpParseResultImpl implements OpenPgpParseResult {
    private final boolean recognized;
    private final MimeMessage effectiveMimeMessage;
    private final OriginalOpenPgpDetails details;

    @Builder
	private OpenPgpParseResultImpl(final boolean recognized,
                              @NotNull final MimeMessage effectiveMimeMessage,
                              @NotNull final OriginalOpenPgpDetails details) {
        this.recognized = recognized;
        this.effectiveMimeMessage = requireNonNull(effectiveMimeMessage, "effectiveMimeMessage");
        this.details = requireNonNull(details, "details");
    }

	/** @see OpenPgpParseResult#isRecognized() */
	@Override
	public boolean isRecognized() { return recognized; }
	/** @see OpenPgpParseResult#getEffectiveMimeMessage() */
	@Override
	@NotNull
	public MimeMessage getEffectiveMimeMessage() { return effectiveMimeMessage; }
	/** @see OpenPgpParseResult#getDetails() */
	@Override
	@NotNull
	public OriginalOpenPgpDetails getDetails() { return details; }
}
