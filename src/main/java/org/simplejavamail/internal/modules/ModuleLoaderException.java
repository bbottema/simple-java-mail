package org.simplejavamail.internal.modules;

import org.simplejavamail.MailException;

public class ModuleLoaderException extends MailException {
	static final String ERROR_MODULE_MISSING = "%s module not found, make sure it is on the classpath (%s)";
	static final String ERROR_LOADING_MODULE = "Error loading %s module...";
	
	public ModuleLoaderException(String message) {
		super(message);
	}
	
	public ModuleLoaderException(String message, Throwable cause) {
		super(message, cause);
	}
}