package org.simplejavamail.api.email;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;

/**
 * Possible encoders for email content (text/html/iCalendar). Default is {@link #QUOTED_PRINTABLE}. This list reflects Jakarta Mail's supported encoders as found at
 * {@code StreamProvider.EncoderTypes}
 *
 * @see "StreamProvider.EncoderTypes"
 */
@RequiredArgsConstructor
@Getter
public enum ContentTransferEncoding {

	BASE_64("base64"),
	B("b"),
	Q("q"),
	BINARY("binary"),
	BIT7("7bit"),
	BIT8("8bit"),
	QUOTED_PRINTABLE("quoted-printable"),
	UU("uuencode"),
	X_UU("x-uuencode"),
	X_UUE("x-uue");

	private final String encoder;

	public static ContentTransferEncoding byEncoder(@NotNull final String encoder) {
		return Arrays.stream(values())
				.filter(c -> c.encoder.equalsIgnoreCase(encoder))
				.findFirst()
				.orElseThrow(() -> new IllegalArgumentException("unknown content transfer encoder: " + encoder));
	}

	@Override
	public String toString() {
		return encoder;
	}
}
