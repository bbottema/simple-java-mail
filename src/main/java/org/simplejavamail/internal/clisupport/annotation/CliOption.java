package org.simplejavamail.internal.clisupport.annotation;

import org.simplejavamail.internal.clisupport.model.CliCommandType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Used to declare a method an option in the CLI support. Can defer description to another method, which can but doesn't have to be an option as well.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Deprecated
public @interface CliOption {
	String nameOverride() default ""; // to work around duplicate CLI commands for overloaded methods
	String[] description();
	CliCommandType[] applicableRootCommands() default {}; // inherited from parent api node
}
