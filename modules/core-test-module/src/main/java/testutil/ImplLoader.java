package testutil;

import org.simplejavamail.api.mailer.MailerRegularBuilder;

import java.lang.reflect.InvocationTargetException;

public class ImplLoader {
	
	public static MailerRegularBuilder<?> loadMailerBuilder() {
		try {
			Class<?> mailerBuilderClass = Class.forName("org.simplejavamail.api.mailer.MailerBuilder");
			return (MailerRegularBuilder) mailerBuilderClass.getMethod("_createForCliOrTest").invoke(mailerBuilderClass);
		} catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException | InvocationTargetException e) {
			throw new AssertionError(e.getMessage(), e);
		}
	}
}
