package org.simplejavamail.internal.clisupport.valueinterpreters;

import org.bbottema.javareflection.valueconverter.IncompatibleTypeException;
import org.bbottema.javareflection.valueconverter.ValueFunction;
import org.simplejavamail.MailException;
import org.simplejavamail.converter.EmailConverter;

import javax.mail.internet.MimeMessage;

public class StringToMimeMessageFunction implements ValueFunction<String, MimeMessage> {
	@Override
	public Class<String> getFromType() {
		return String.class;
	}
	
	@Override
	public Class<MimeMessage> getTargetType() {
		return MimeMessage.class;
	}
	
	@Override
	public MimeMessage convertValue(String value) {
		try {
			return EmailConverter.emlToMimeMessage(value);
		} catch (MailException e) {
			throw new IncompatibleTypeException(value, String.class, MimeMessage.class, e);
		}
	}
}
