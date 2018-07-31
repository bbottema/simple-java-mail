package org.simplejavamail.internal.clisupport.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface CliSupported {
	
	String paramPrefix() default "";
	
	// FIXME this should be on CliParam, CliSupported corresponds with Command on picocli, while CliParam corresponds to OptionsSpec
	String helpLabel() default "";
	
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.PARAMETER)
	@interface CliParam {
		
		String name() default "";
		String example();
	}
}