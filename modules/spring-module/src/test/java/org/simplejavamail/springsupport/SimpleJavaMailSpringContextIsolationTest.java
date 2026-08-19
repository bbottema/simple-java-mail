package org.simplejavamail.springsupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.SimpleJavaMailConfig;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SimpleJavaMailSpringContextIsolationTest {

	@Test
	void springContextsAndAnExplicitFactoryKeepIndependentSnapshots() {
		final String conventionalHost = SimpleJavaMail.fromDefaults().mailerBuilder().getHost();
		final AnnotationConfigApplicationContext contextA = context("a.example.test", 2525, "A");
		final AnnotationConfigApplicationContext contextB = context("b.example.test", 2526, "B");
		final SimpleJavaMail explicit = SimpleJavaMail.withConfig(config("explicit.example.test", 2527));

		try {
			final SimpleJavaMail factoryA = contextA.getBean(SimpleJavaMail.class);
			final SimpleJavaMail factoryB = contextB.getBean(SimpleJavaMail.class);

			assertThat(factoryA.mailerBuilder().getHost()).isEqualTo("a.example.test");
			assertThat(factoryB.mailerBuilder().getHost()).isEqualTo("b.example.test");
			assertThat(explicit.mailerBuilder().getHost()).isEqualTo("explicit.example.test");
			assertThat(SimpleJavaMail.fromDefaults().mailerBuilder().getHost()).isEqualTo(conventionalHost);
			assertThat(contextA.getBean(SimpleJavaMailConfig.class).getStringProperty(ConfigLoader.Property.DEFAULT_SUBJECT)).isEqualTo("A");
			assertThat(contextB.getBean(SimpleJavaMailConfig.class).getStringProperty(ConfigLoader.Property.DEFAULT_SUBJECT)).isEqualTo("B");
			assertThat(contextA.containsBean("defaultMailerBuilder")).isFalse();

			contextA.close();

			assertThat(factoryB.mailerBuilder().getHost()).isEqualTo("b.example.test");
			assertThat(explicit.mailerBuilder().getHost()).isEqualTo("explicit.example.test");
		} finally {
			if (contextA.isActive()) {
				contextA.close();
			}
			contextB.close();
		}
	}

	private static AnnotationConfigApplicationContext context(final String host, final int port, final String subject) {
		final Map<String, Object> properties = new LinkedHashMap<>();
		properties.put(ConfigLoader.Property.SMTP_HOST.key(), host);
		properties.put(ConfigLoader.Property.SMTP_PORT.key(), Integer.toString(port));
		properties.put(ConfigLoader.Property.DEFAULT_SUBJECT.key(), subject);
		final AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
		context.getEnvironment().getPropertySources().addFirst(new MapPropertySource("test", properties));
		context.register(SimpleJavaMailSpringSupport.class);
		context.refresh();
		return context;
	}

	private static SimpleJavaMailConfig config(final String host, final int port) {
		final Map<String, Object> properties = new LinkedHashMap<>();
		properties.put(ConfigLoader.Property.SMTP_HOST.key(), host);
		properties.put(ConfigLoader.Property.SMTP_PORT.key(), port);
		return ConfigLoader.builder().withMap(properties).load();
	}
}
