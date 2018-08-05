package org.simplejavamail.internal.clisupport.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface CliSupported {
	
	enum RootCommand {
		send, connect, validate, convert, all
	}
	
	String paramPrefix();
	RootCommand[] applicableRootCommands();
}