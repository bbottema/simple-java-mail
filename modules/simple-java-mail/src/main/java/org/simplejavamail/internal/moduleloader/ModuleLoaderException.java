package org.simplejavamail.internal.moduleloader;

import org.simplejavamail.MailException;
import org.simplejavamail.api.mailer.MailFailureKind;

@SuppressWarnings("serial")
class ModuleLoaderException extends MailException {
	static final String ERROR_MODULE_MISSING = "%s module not found, make sure it is on the classpath (%s)";
	static final String ERROR_LOADING_MODULE = "Error loading %s module...";
	
	ModuleLoaderException(String message) {
		super(MailFailureKind.CONFIGURATION, message, null, null);
	}
	
	ModuleLoaderException(String message, Throwable cause) {
		super(MailFailureKind.CONFIGURATION, message, cause, null);
	}
}