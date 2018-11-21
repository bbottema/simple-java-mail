package org.simplejavamail.internal.clisupport.valueinterpreters;

import org.bbottema.javareflection.ReflectionUtils;
import org.bbottema.javareflection.valueconverter.IncompatibleTypeException;
import org.bbottema.javareflection.valueconverter.ValueFunction;
import org.simplejavamail.MailException;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import java.io.File;

import static org.slf4j.LoggerFactory.getLogger;

abstract class FileBasedFunction<T> implements ValueFunction<String, T> {
	
	private static final Logger LOGGER = getLogger(FileBasedFunction.class);
	
	@Override
	public final T convertValue(String value) {
		try {
			return convertFile(getAsFile(value));
		} catch (MailException e) {
			Class<T> toType = ReflectionUtils.findParameterType(this.getClass(), FileBasedFunction.class, 0);
			throw new IncompatibleTypeException(value, String.class, toType, e);
		}
	}
	
	private File getAsFile(String value) {
		File file = new File(value);
		if (!file.exists()) {
			LOGGER.debug("file not found for [" + value + "]");
		}
		return file;
	}
	
	@Nonnull
	protected abstract T convertFile(File asFile);
}