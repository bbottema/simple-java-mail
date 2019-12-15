/*
 * Copyright (C) 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.internal.clisupport.valueinterpreters;

import org.bbottema.javareflection.ReflectionUtils;
import org.bbottema.javareflection.valueconverter.IncompatibleTypeException;
import org.bbottema.javareflection.valueconverter.ValueFunction;
import org.simplejavamail.MailException;
import org.slf4j.Logger;

import org.jetbrains.annotations.NotNull;
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
	
	@NotNull
	protected abstract T convertFile(File asFile);
}