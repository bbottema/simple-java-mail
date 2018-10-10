package org.simplejavamail.internal.clisupport.annotation;

import org.simplejavamail.internal.clisupport.model.CliBuilderApiType;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

public class Cli {
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	public @interface ExcludeApi {
		String reason();
	}
	
	/**
	 * Used to declare a method an option in the CLI support. Can defer description to another method, which can but doesn't have to be an option as well.
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.METHOD)
	public @interface OptionNameOverride {
		String value();
	}
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target({ElementType.TYPE})
	public @interface BuilderApiNode {
		CliBuilderApiType builderApiType();
	}
	
	/**
	 * Describes a method's param as a CLI option's value
	 */
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.PARAMETER)
	@Deprecated
	public @interface OptionValue {
		String name();
		String description() default "";
		String helpLabel();
		boolean required() default true;
		String[] example() default {};
	}
}
