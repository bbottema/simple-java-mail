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
package org.simplejavamail.internal.modules;

import org.simplejavamail.MailException;

@SuppressWarnings("serial")
class ModuleLoaderException extends MailException {
	static final String ERROR_MODULE_MISSING = "%s module not found, make sure it is on the classpath (%s)";
	static final String ERROR_LOADING_MODULE = "Error loading %s module...";
	
	ModuleLoaderException(String message) {
		super(message);
	}
	
	ModuleLoaderException(String message, Throwable cause) {
		super(message, cause);
	}
}