package org.simplejavamail.internal.moduleloader;

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