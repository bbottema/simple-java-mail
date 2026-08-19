package testutil;

import org.simplejavamail.api.mailer.MailerRegularBuilder;
import org.simplejavamail.config.SimpleJavaMailConfig;

import java.lang.reflect.InvocationTargetException;

public class ImplLoader {
	
	public static MailerRegularBuilder<?> loadMailerBuilder() {
		try {
			Class<?> mailerBuilderClass = Class.forName("org.simplejavamail.mailer.internal.MailerRegularBuilderImpl");
			return (MailerRegularBuilder<?>) mailerBuilderClass
					.getConstructor(SimpleJavaMailConfig.class)
					.newInstance(ConfigLoaderTestHelper.emptyConfig());
		} catch (ClassNotFoundException | IllegalAccessException | InstantiationException | NoSuchMethodException | InvocationTargetException e) {
			throw new AssertionError(e.getMessage(), e);
		}
	}
}
