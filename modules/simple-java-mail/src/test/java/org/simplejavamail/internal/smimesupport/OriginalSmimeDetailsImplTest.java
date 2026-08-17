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
