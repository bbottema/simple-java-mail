package org.simplejavamail.internal.clisupport.valueinterpreters;

import org.simplejavamail.converter.EmailConverter;

import javax.annotation.Nonnull;
import javax.mail.internet.MimeMessage;
import java.io.File;

public class StringToMimeMessageFunction extends FileBasedFunction<MimeMessage> {
	
	@Override
	public Class<String> getFromType() {
		return String.class;
	}
	
	@Override
	public Class<MimeMessage> getTargetType() {
		return MimeMessage.class;
	}
	
	@Nonnull
	@Override
	protected MimeMessage convertFile(File emlFile) {
		return EmailConverter.emlToMimeMessage(emlFile);
	}
}
