package org.simplejavamail.internal.clisupport.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface CliOption {
	String nameOverride() default ""; // to work around duplicate CLI commands for overloaded methods
	String[] description();
	CliCommand[] applicableRootCommands() default {};
}
