package org.simplejavamail.internal.clisupport.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * In case method delegate to another with some defaults, this annotation can be used to indicate that description should be included in the CLI usage
 * help.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Deprecated
public @interface CliOptionDescriptionDelegate {
	Class<?> delegateClass();
	
	String delegateMethod();
	
	Class<?>[] delegateParameters();
}
