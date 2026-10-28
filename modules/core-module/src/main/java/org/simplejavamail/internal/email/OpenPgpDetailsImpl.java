package org.simplejavamail.internal.email;

import lombok.AccessLevel;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;
import org.simplejavamail.api.email.OpenPgpSignatureResult;

import java.io.InvalidObjectException;
import java.io.ObjectStreamException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Shared result assembly for core snapshot restoration, the main converter and the optional OpenPGP implementation. */
@ApiStatus.Internal
@Getter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Builder(toBuilder = true)
public final class OpenPgpDetailsImpl implements OriginalOpenPgpDetails {

	private static final long serialVersionUID = 1L;

	@EqualsAndHashCode.Include
	@Builder.Default private final OpenPgpMode openPgpMode = OpenPgpMode.PLAIN;
	@Builder.Default private final SignatureStatus signatureStatus = SignatureStatus.NOT_PRESENT;
	@EqualsAndHashCode.Include
	@Builder.Default private final DecryptionStatus decryptionStatus = DecryptionStatus.NOT_ENCRYPTED;
	@Nullable private final String signerKeyId;
	@Nullable private final String signerFingerprint;
	@Nullable private final String signatureAlgorithm;
	@Nullable private final String hashAlgorithm;
	@EqualsAndHashCode.Include @Nullable private final String encryptionAlgorithm;
	@EqualsAndHashCode.Include @Builder.Default private final List<String> recipientKeyIds = List.of();
	@Nullable private final String failureReason;
	@Getter(AccessLevel.NONE)
	@EqualsAndHashCode.Include
	private final byte @Nullable [] originalProtectedMessage;
	@EqualsAndHashCode.Include @Builder.Default private final List<OpenPgpSignatureResult> signatures = List.of();
	private final boolean signatureInspectionComplete;

	private OpenPgpDetailsImpl(final OpenPgpMode openPgpMode, final SignatureStatus signatureStatus,
			final DecryptionStatus decryptionStatus, @Nullable final String signerKeyId, @Nullable final String signerFingerprint,
			@Nullable final String signatureAlgorithm, @Nullable final String hashAlgorithm, @Nullable final String encryptionAlgorithm,
			final List<String> recipientKeyIds, @Nullable final String failureReason, final byte @Nullable [] originalProtectedMessage,
			final List<OpenPgpSignatureResult> signatures, final boolean signatureInspectionComplete) {
		this.openPgpMode = Objects.requireNonNull(openPgpMode, "openPgpMode");
		this.signatureStatus = Objects.requireNonNull(signatureStatus, "signatureStatus");
		this.decryptionStatus = Objects.requireNonNull(decryptionStatus, "decryptionStatus");
		this.signerKeyId = signerKeyId;
		this.signerFingerprint = signerFingerprint;
		this.signatureAlgorithm = signatureAlgorithm;
		this.hashAlgorithm = hashAlgorithm;
		this.encryptionAlgorithm = encryptionAlgorithm;
		this.recipientKeyIds = List.copyOf(recipientKeyIds);
		this.failureReason = failureReason;
		this.originalProtectedMessage = originalProtectedMessage == null ? null : originalProtectedMessage.clone();
		final List<OpenPgpSignatureResult> supplied = Objects.requireNonNull(signatures, "signatures");
		this.signatures = signatureSnapshot(supplied.isEmpty()
				? selectedHistoricalSignature(signatureStatus, signerKeyId, signerFingerprint, signatureAlgorithm, hashAlgorithm, failureReason) : supplied);
		this.signatureInspectionComplete = signatureInspectionComplete;
	}

	@NotNull
	public static OpenPgpDetailsImpl plain() {
		return builder().signatureInspectionComplete(true).build();
	}

	/** Detaches current reading-contract facts; older selected-only serial forms are restored separately without completeness. */
	public static OpenPgpDetailsImpl copyOf(final OriginalOpenPgpDetails source) {
		Objects.requireNonNull(source, "source");
		if (source instanceof OpenPgpDetailsImpl) return (OpenPgpDetailsImpl) source;
		final List<OpenPgpSignatureResult> results = source.getSignatures();
		final SignatureStatus summary = source.getSignatureStatus();
		final boolean complete = summary == SignatureStatus.NOT_PRESENT && results.isEmpty()
				|| summary == SignatureStatus.VALID && !results.isEmpty() && results.stream().allMatch(result -> result.getStatus() == SignatureStatus.VALID);
		return builder().openPgpMode(source.getOpenPgpMode()).signatureStatus(summary)
				.decryptionStatus(source.getDecryptionStatus()).signatures(results).signatureInspectionComplete(complete)
				.recipientKeyIds(source.getRecipientKeyIds()).encryptionAlgorithm(source.getEncryptionAlgorithm())
				.failureReason(source.getFailureReason()).originalProtectedMessage(source.getOriginalProtectedMessage()).build();
	}

	@EqualsAndHashCode.Include
	@Override public SignatureStatus getSignatureStatus() {
		SignatureStatus problem = signatureStatus;
		for (OpenPgpSignatureResult result : signatures) {
			if (problemRank(result.getStatus()) < problemRank(problem)) problem = result.getStatus();
		}
		if (problemRank(problem) <= problemRank(SignatureStatus.UNKNOWN)) return problem;
		if (!signatureInspectionComplete) return SignatureStatus.UNKNOWN;
		return signatures.isEmpty() ? SignatureStatus.NOT_PRESENT : SignatureStatus.VALID;
	}

	@Override public String getSignerKeyId() { return signatures.size() == 1 ? signatures.get(0).getSignerKeyId() : null; }
	@Override public String getSignerFingerprint() { return signatures.size() == 1 ? signatures.get(0).getSignerFingerprint() : null; }
	@Override public String getSignatureAlgorithm() { return signatures.size() == 1 ? signatures.get(0).getSignatureAlgorithm() : null; }
	@Override public String getHashAlgorithm() { return signatures.size() == 1 ? signatures.get(0).getHashAlgorithm() : null; }

	@EqualsAndHashCode.Include
	@Override public String getFailureReason() {
		if (failureReason != null) return failureReason;
		if (signatures.size() == 1 && signatures.get(0).getFailureReason() != null) return signatures.get(0).getFailureReason();
		final SignatureStatus summary = getSignatureStatus();
		switch (summary) {
			case INVALID: return "One or more OpenPGP signatures are invalid";
			case ERROR: return "One or more OpenPGP signatures could not be processed";
			case KEY_MISSING: return "Verification keys are missing for one or more OpenPGP signatures";
			case UNKNOWN: return "OpenPGP signature inspection is incomplete";
			default: return null;
		}
	}

	private static int problemRank(final SignatureStatus status) {
		switch (status) {
			case INVALID: return 0;
			case ERROR: return 1;
			case KEY_MISSING: return 2;
			case UNKNOWN: return 3;
			default: return 4;
		}
	}

	/** Combines known inspection problems without turning a positive scalar into proof of completeness. */
	public static SignatureStatus strongestProblem(final SignatureStatus... statuses) {
		SignatureStatus result = SignatureStatus.NOT_PRESENT;
		for (SignatureStatus status : statuses) if (problemRank(status) < problemRank(result)) result = status;
		return result;
	}

	private static List<OpenPgpSignatureResult> signatureSnapshot(final List<OpenPgpSignatureResult> source) {
		final List<OpenPgpSignatureResult> copy = new ArrayList<>(source.size());
		for (OpenPgpSignatureResult result : source) copy.add(OpenPgpSignatureResultImpl.copyOf(result));
		final Comparator<String> text = Comparator.nullsLast(Comparator.naturalOrder());
		copy.sort(Comparator.comparing(OpenPgpSignatureResult::getSignerKeyId, text)
				.thenComparingInt(result -> problemRank(result.getStatus()))
				.thenComparing(OpenPgpSignatureResult::getSignerFingerprint, text)
				.thenComparing(OpenPgpSignatureResult::getSignatureAlgorithm, text)
				.thenComparing(OpenPgpSignatureResult::getHashAlgorithm, text)
				.thenComparing(OpenPgpSignatureResult::getFailureReason, text));
		return List.copyOf(copy);
	}

	private static List<OpenPgpSignatureResult> selectedHistoricalSignature(final SignatureStatus status, final String keyId,
			final String fingerprint, final String algorithm, final String hash, final String reason) {
		if (status == SignatureStatus.VALID || status == SignatureStatus.INVALID || status == SignatureStatus.KEY_MISSING
				|| keyId != null || fingerprint != null || algorithm != null || hash != null) {
			return List.of(new OpenPgpSignatureResultImpl(status == SignatureStatus.NOT_PRESENT ? SignatureStatus.UNKNOWN : status,
					keyId, fingerprint, algorithm, hash, reason));
		}
		return List.of();
	}

	private Object readResolve() throws ObjectStreamException {
		try {
			return new OpenPgpDetailsImpl(openPgpMode, signatureStatus, decryptionStatus, signerKeyId, signerFingerprint,
					signatureAlgorithm, hashAlgorithm, encryptionAlgorithm, recipientKeyIds, failureReason, originalProtectedMessage,
					signatures == null ? selectedHistoricalSignature(signatureStatus, signerKeyId, signerFingerprint, signatureAlgorithm,
							hashAlgorithm, failureReason) : signatures, signatures != null && signatureInspectionComplete);
		} catch (RuntimeException cause) {
			final InvalidObjectException failure = new InvalidObjectException("Invalid OpenPGP inspection result");
			failure.initCause(cause);
			throw failure;
		}
	}

	@Override
	public byte @Nullable [] getOriginalProtectedMessage() {
		return originalProtectedMessage == null ? null : originalProtectedMessage.clone();
	}

	@Override
	public String toString() {
		return "OpenPgpDetails{openPgpMode=" + openPgpMode
				+ ", signatureStatus=" + getSignatureStatus()
				+ ", decryptionStatus=" + decryptionStatus
				+ ", signatures=" + signatures
				+ ", encryptionAlgorithm='" + encryptionAlgorithm + '\''
				+ ", recipientKeyIds=" + recipientKeyIds
				+ ", failureReason='" + getFailureReason() + '\''
				+ ", originalProtectedMessage=" + (originalProtectedMessage == null ? "none" : "preserved") + '}';
	}
}
