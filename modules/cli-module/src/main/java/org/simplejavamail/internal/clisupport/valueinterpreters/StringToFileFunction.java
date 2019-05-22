package org.simplejavamail.internal.clisupport.valueinterpreters;

import javax.annotation.Nonnull;
import java.io.File;

public class StringToFileFunction extends FileBasedFunction<File> {
	
	@Override
	public Class<String> getFromType() {
		return String.class;
	}
	
	@Override
	public Class<File> getTargetType() {
		return File.class;
	}
	
	@Nonnull
	@Override
	protected File convertFile(File textFile) {
		return textFile;
	}
}
