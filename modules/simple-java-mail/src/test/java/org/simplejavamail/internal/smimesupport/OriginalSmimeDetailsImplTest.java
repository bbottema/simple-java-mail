package org.simplejavamail.internal.smimesupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.internal.smimesupport.model.OriginalSmimeDetailsImpl;

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
}
