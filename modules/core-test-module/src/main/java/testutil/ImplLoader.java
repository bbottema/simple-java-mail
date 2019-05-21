package testutil;

import org.simplejavamail.api.mailer.MailerRegularBuilder;

import java.lang.reflect.InvocationTargetException;

public class ImplLoader {
	
	public static MailerRegularBuilder<?> loadMailerBuilder() {
		try {
			Class<?> mailerBuilderClass = Class.forName("org.simplejavamail.mailer.internal.MailerRegularBuilderImpl");
			return (MailerRegularBuilder<?>) mailerBuilderClass.newInstance();
		} catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
			throw new AssertionError(e.getMessage(), e);
		}
	}
}
