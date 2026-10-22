package org.simplejavamail.internal.email;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.OriginalOpenPgpDetails;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenPgpDetailsBoundaryTest {

	@Test
	void protectedMailResultsAreAssembledBehindTheCoreReadContract() throws Exception {
		final Class<?> implementation = Class.forName("org.simplejavamail.internal.email.OpenPgpDetailsImpl");
		assertThat(OriginalOpenPgpDetails.class.isAssignableFrom(implementation)).isTrue();
		assertThatThrownBy(() -> Class.forName("org.simplejavamail.api.email.OpenPgpDetails"))
				.isInstanceOf(ClassNotFoundException.class);
	}

	@Test
	void plainAndProtectedResultsRetainValueAndSnapshotSemantics() throws Exception {
		final OpenPgpDetailsImpl plain = OpenPgpDetailsImpl.plain();
		assertThat(plain.getOpenPgpMode()).isEqualTo(OriginalOpenPgpDetails.OpenPgpMode.PLAIN);
		assertThat(plain.getSignatureStatus()).isEqualTo(OriginalOpenPgpDetails.SignatureStatus.NOT_PRESENT);
		assertThat(plain.getDecryptionStatus()).isEqualTo(OriginalOpenPgpDetails.DecryptionStatus.NOT_ENCRYPTED);
		final List<String> recipients = new ArrayList<>(List.of("recipient-key"));
		final byte[] original = {1, 2, 3};
		final OpenPgpDetailsImpl protectedResult = OpenPgpDetailsImpl.builder()
				.openPgpMode(OriginalOpenPgpDetails.OpenPgpMode.ENCRYPTED)
				.decryptionStatus(OriginalOpenPgpDetails.DecryptionStatus.KEY_MISSING)
				.recipientKeyIds(recipients)
				.originalProtectedMessage(original)
				.build();
		recipients.clear();
		original[0] = 9;
		protectedResult.getOriginalProtectedMessage()[1] = 9;

		final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
			output.writeObject(protectedResult);
		}
		try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
			final OriginalOpenPgpDetails restored = (OriginalOpenPgpDetails) input.readObject();
			assertThat(restored).isEqualTo(protectedResult).hasSameHashCodeAs(protectedResult);
			assertThat(restored.getOriginalProtectedMessage()).containsExactly(1, 2, 3);
			assertThat(restored.getRecipientKeyIds()).containsExactly("recipient-key");
			assertThatThrownBy(() -> restored.getRecipientKeyIds().clear()).isInstanceOf(UnsupportedOperationException.class);
		}
	}

	@Test
	void unavailableAndFailedInspectionRemainDistinctAcrossSerialization() throws Exception {
		final byte[] original = {4, 5, 6};
		final OpenPgpDetailsImpl unavailable = OpenPgpDetailsImpl.builder()
				.openPgpMode(OriginalOpenPgpDetails.OpenPgpMode.valueOf("UNKNOWN"))
				.signatureStatus(OriginalOpenPgpDetails.SignatureStatus.valueOf("UNKNOWN"))
				.decryptionStatus(OriginalOpenPgpDetails.DecryptionStatus.valueOf("UNKNOWN"))
				.originalProtectedMessage(original)
				.build();
		final OpenPgpDetailsImpl failed = OpenPgpDetailsImpl.builder()
				.openPgpMode(OriginalOpenPgpDetails.OpenPgpMode.SIGNED_ENCRYPTED)
				.signatureStatus(OriginalOpenPgpDetails.SignatureStatus.ERROR)
				.decryptionStatus(OriginalOpenPgpDetails.DecryptionStatus.FAILED)
				.failureReason("Actual inspection failure")
				.originalProtectedMessage(original)
				.build();
		original[0] = 9;
		for (OriginalOpenPgpDetails details : List.of(unavailable, failed)) {
			final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
			try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
				output.writeObject(details);
			}
			try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
				final OriginalOpenPgpDetails restored = (OriginalOpenPgpDetails) input.readObject();
				assertThat(restored).isEqualTo(details).hasSameHashCodeAs(details);
				assertThat(restored.getOpenPgpMode()).isEqualTo(details.getOpenPgpMode());
				assertThat(restored.getSignatureStatus()).isEqualTo(details.getSignatureStatus());
				assertThat(restored.getDecryptionStatus()).isEqualTo(details.getDecryptionStatus());
				assertThat(restored.getFailureReason()).isEqualTo(details.getFailureReason());
				restored.getOriginalProtectedMessage()[1] = 9;
				assertThat(restored.getOriginalProtectedMessage()).containsExactly(4, 5, 6);
			}
		}
	}
}
