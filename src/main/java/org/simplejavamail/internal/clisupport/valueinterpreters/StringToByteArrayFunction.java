package org.simplejavamail.internal.clisupport.valueinterpreters;

import javax.annotation.Nonnull;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

public class StringToByteArrayFunction extends FileBasedFunction<byte[]> {

	@Override
	public Class<String> getFromType() {
		return String.class;
	}
	
	@Override
	public Class<byte[]> getTargetType() {
		return byte[].class;
	}
	
	@Nonnull
	@Override
	protected byte[] convertFile(File emlFile) {
		try {
			return Files.readAllBytes(emlFile.toPath());
		} catch (IOException e) {
			throw new RuntimeException("File found, but was unable to read its content", e);
		}
	}
}
