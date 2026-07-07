package org.simplejavamail.internal.clisupport.valueinterpreters;

import jakarta.mail.Message;
import org.bbottema.javareflection.valueconverter.IncompatibleTypeException;
import org.bbottema.javareflection.valueconverter.ValueFunction;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

public class StringToRecipientTypeFunction implements ValueFunction<String, Message.RecipientType> {

	@Override
	public Message.RecipientType convertValue(@Nullable final String value) throws IncompatibleTypeException {
		if (value == null) {
			return null;
		}
		switch (value.trim().toUpperCase(Locale.ENGLISH)) {
			case "TO":
				return Message.RecipientType.TO;
			case "CC":
				return Message.RecipientType.CC;
			case "BCC":
				return Message.RecipientType.BCC;
			default:
				throw new IncompatibleTypeException(value, String.class, Message.RecipientType.class);
		}
	}

	@Override
	public Class<String> getFromType() {
		return String.class;
	}

	@Override
	public Class<Message.RecipientType> getTargetType() {
		return Message.RecipientType.class;
	}
}
