package org.simplejavamail.internal.clisupport.annotation;

import org.simplejavamail.internal.clisupport.model.CliBuilderApiType;
import org.simplejavamail.internal.clisupport.model.CliCommandType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Placed at the root of the Builder API, indicates that a class should be indexed for CLI options
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface CliSupportedBuilderApi {
	CliBuilderApiType builderApiType();
	CliCommandType[] applicableRootCommands();
}