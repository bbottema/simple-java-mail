package org.simplejavamail.internal.smimesupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.OriginalSmimeDetails.DecryptionStatus;
import org.simplejavamail.api.email.OriginalSmimeDetails.VerificationStatus;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class OriginalSmimeDetailsImplTest {

	@Test
	void signatureValidityRemainsUnknownUntilAResultIsRecorded() {
		final OriginalSmimeDetailsImpl details = OriginalSmimeDetailsImpl.builder().build();

		assertThat(details.getSmimeSignatureValid()).isNull();
	}

	@Test
	void additionalSignatureResultsAreCombinedFailClosed() {
		final OriginalSmimeDetailsImpl validThenInvalid = OriginalSmimeDetailsImpl.builder().build();
		validThenInvalid.completeWithSmimeSignatureValid(true);
		validThenInvalid.completeWithSmimeSignatureValid(false);

		final OriginalSmimeDetailsImpl invalidThenValid = OriginalSmimeDetailsImpl.builder().build();
		invalidThenValid.completeWithSmimeSignatureValid(false);
		invalidThenValid.completeWithSmimeSignatureValid(true);

		assertThat(validThenInvalid.getSmimeSignatureValid()).isFalse();
		assertThat(invalidThenValid.getSmimeSignatureValid()).isFalse();
		assertThat(validThenInvalid.getVerificationStatus()).isEqualTo(VerificationStatus.INVALID);
		assertThat(invalidThenValid.getVerificationStatus()).isEqualTo(VerificationStatus.INVALID);
	}

	@Test
	void combinedMetadataPreservesAnySignatureFailure() {
		final OriginalSmimeDetailsImpl details = OriginalSmimeDetailsImpl.builder()
				.smimeSignatureValid(true)
				.build();

		details.completeWith(OriginalSmimeDetailsImpl.builder()
				.smimeSignatureValid(false)
				.build());

		assertThat(details.getSmimeSignatureValid()).isFalse();
		assertThat(details.getVerificationStatus()).isEqualTo(VerificationStatus.INVALID);
	}

	@Test
	void combinedMetadataKeepsTheMostSevereVerificationAndDecryptionResults() {
		final OriginalSmimeDetailsImpl details = OriginalSmimeDetailsImpl.builder()
				.verificationStatus(VerificationStatus.VALID)
				.decryptionStatus(DecryptionStatus.DECRYPTED)
				.build();

		details.completeWith(OriginalSmimeDetailsImpl.builder()
				.verificationStatus(VerificationStatus.ERROR)
				.decryptionStatus(DecryptionStatus.KEY_MISSING)
				.failureReason("nested protection could not be processed")
				.build());
		details.completeWith(OriginalSmimeDetailsImpl.builder()
				.verificationStatus(VerificationStatus.INVALID)
				.decryptionStatus(DecryptionStatus.FAILED)
				.build());

		assertThat(details.getVerificationStatus()).isEqualTo(VerificationStatus.ERROR);
		assertThat(details.getSmimeSignatureValid()).isFalse();
		assertThat(details.getDecryptionStatus()).isEqualTo(DecryptionStatus.FAILED);
		assertThat(details.getFailureReason()).isEqualTo("nested protection could not be processed");
	}

	@Test
	void explicitStatusAndLegacyBooleanCannotContradictEachOther() {
		final OriginalSmimeDetailsImpl details = OriginalSmimeDetailsImpl.builder()
				.smimeSignatureValid(true)
				.verificationStatus(VerificationStatus.INVALID)
				.build();

		assertThat(details.getVerificationStatus()).isEqualTo(VerificationStatus.INVALID);
		assertThat(details.getSmimeSignatureValid()).isFalse();
	}

	@Test
	void newResultFieldsParticipateInValueSemanticsWithoutDumpingProtectedBytes() {
		final OriginalSmimeDetailsImpl details = resultDetails(VerificationStatus.INVALID,
				DecryptionStatus.KEY_MISSING, "missing key", new byte[] {1, 2, 3});
		final OriginalSmimeDetailsImpl copy = resultDetails(VerificationStatus.INVALID,
				DecryptionStatus.KEY_MISSING, "missing key", new byte[] {1, 2, 3});

		assertThat(details).isEqualTo(copy).hasSameHashCodeAs(copy);
		assertThat(details).isNotEqualTo(resultDetails(VerificationStatus.ERROR,
				DecryptionStatus.KEY_MISSING, "missing key", new byte[] {1, 2, 3}));
		assertThat(details).isNotEqualTo(resultDetails(VerificationStatus.INVALID,
				DecryptionStatus.FAILED, "missing key", new byte[] {1, 2, 3}));
		assertThat(details).isNotEqualTo(resultDetails(VerificationStatus.INVALID,
				DecryptionStatus.KEY_MISSING, "different", new byte[] {1, 2, 3}));
		assertThat(details).isNotEqualTo(resultDetails(VerificationStatus.INVALID,
				DecryptionStatus.KEY_MISSING, "missing key", new byte[] {3, 2, 1}));
		assertThat(details.toString())
				.contains("verificationStatus=INVALID", "decryptionStatus=KEY_MISSING", "failureReason='missing key'",
						"originalProtectedMessage=preserved")
				.doesNotContain("[1, 2, 3]");
	}

	@Test
	void retainsLargeProtectedMessagesInMemoryWithoutExposingItsInternalArray() {
		final byte[] protectedMessage = new byte[2 * 1024 * 1024];
		Arrays.fill(protectedMessage, (byte) 42);
		final OriginalSmimeDetailsImpl details = OriginalSmimeDetailsImpl.builder()
				.originalProtectedMessage(protectedMessage)
				.build();

		protectedMessage[0] = 1;
		final byte[] firstRead = details.getOriginalProtectedMessage();
		firstRead[1] = 2;

		assertThat(firstRead).hasSize(2 * 1024 * 1024);
		assertThat(details.getOriginalProtectedMessage()[0]).isEqualTo((byte) 42);
		assertThat(details.getOriginalProtectedMessage()[1]).isEqualTo((byte) 42);
	}

	@Test
	void deserializationDefaultsStatusesMissingFromOlderSerializedForms() throws Exception {
		final OriginalSmimeDetailsImpl details = OriginalSmimeDetailsImpl.builder()
				.smimeSignatureValid(true)
				.build();
		setField(details, "verificationStatus", null);
		setField(details, "decryptionStatus", null);

		final OriginalSmimeDetailsImpl restored = roundTrip(details);

		assertThat(restored.getVerificationStatus()).isEqualTo(VerificationStatus.VALID);
		assertThat(restored.getDecryptionStatus()).isEqualTo(DecryptionStatus.NOT_ENCRYPTED);
	}

	private static void setField(final OriginalSmimeDetailsImpl details, final String fieldName, final Object value) throws Exception {
		final Field field = OriginalSmimeDetailsImpl.class.getDeclaredField(fieldName);
		field.setAccessible(true);
		field.set(details, value);
	}

	private static OriginalSmimeDetailsImpl resultDetails(final VerificationStatus verificationStatus,
			final DecryptionStatus decryptionStatus, final String failureReason, final byte[] originalProtectedMessage) {
		return OriginalSmimeDetailsImpl.builder()
				.verificationStatus(verificationStatus)
				.decryptionStatus(decryptionStatus)
				.failureReason(failureReason)
				.originalProtectedMessage(originalProtectedMessage)
				.build();
	}

	private static OriginalSmimeDetailsImpl roundTrip(final OriginalSmimeDetailsImpl details) throws Exception {
		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
			output.writeObject(details);
		}
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			return (OriginalSmimeDetailsImpl) input.readObject();
		}
	}
}
