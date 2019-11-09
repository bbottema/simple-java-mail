package org.simplejavamail.internal.clisupport.valueinterpreters;

import org.simplejavamail.converter.EmailConverter;

import org.jetbrains.annotations.NotNull;
import javax.mail.internet.MimeMessage;
import java.io.File;

public class EmlFilePathToMimeMessageFunction extends FileBasedFunction<MimeMessage> {
	
	@Override
	public Class<String> getFromType() {
		return String.class;
	}
	
	@Override
	public Class<MimeMessage> getTargetType() {
		return MimeMessage.class;
	}
	
	@NotNull
	@Override
	protected MimeMessage convertFile(File emlFile) {
		return EmailConverter.emlToMimeMessage(emlFile);
	}
}
