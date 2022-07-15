package org.simplejavamail.internal.clisupport.valueinterpreters;

import lombok.val;
import org.bbottema.javareflection.valueconverter.IncompatibleTypeException;
import org.bbottema.javareflection.valueconverter.ValueFunction;
import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.util.Arrays;

public class StringToContentTransferEncodingFunction implements ValueFunction<String, ContentTransferEncoding> {

	@Override
	@NotNull
	public Class<String> getFromType() {
		return String.class;
	}

	@Override
	@NotNull
	public Class<ContentTransferEncoding> getTargetType() {
		return ContentTransferEncoding.class;
	}

	@Override
	@NotNull
	public final ContentTransferEncoding convertValue(@NotNull final String value) {
		try {
			return ContentTransferEncoding.byEncoder(value);
		} catch (IllegalArgumentException eByEncoder) {
			try {
				return ContentTransferEncoding.valueOf(value);
			} catch (IllegalArgumentException eByEnumName) {
				val causes = Arrays.asList(
						new IncompatibleTypeException(value, String.class, ContentTransferEncoding.class, eByEncoder),
						new IncompatibleTypeException(value, String.class, ContentTransferEncoding.class, eByEnumName));
				throw new IncompatibleTypeException(value, String.class, TransportStrategy.class, causes);
			}
		}
	}
}