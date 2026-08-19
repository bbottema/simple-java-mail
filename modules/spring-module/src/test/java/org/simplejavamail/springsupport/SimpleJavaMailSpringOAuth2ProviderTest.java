package org.simplejavamail.springsupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.env.StandardEnvironment;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SimpleJavaMailSpringOAuth2ProviderTest {

	@Test
	void noProviderBeanShouldLeaveDefaultMailerWithoutAProvider() throws Exception {
		final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();

		try (Mailer mailer = springSupport().defaultMailer(factory(false), beanFactory.getBeanProvider(OAuth2AccessTokenProvider.class))) {
			assertThat(mailer.getOperationalConfig().getOAuth2AccessTokenProvider()).isNull();
		}
	}

	@Test
	void oneProviderBeanShouldBeAppliedToDefaultMailer() throws Exception {
		final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		final OAuth2AccessTokenProvider provider = () -> "token";
		registerProvider(beanFactory, "provider", provider, false);

		try (Mailer mailer = springSupport().defaultMailer(factory(true), beanFactory.getBeanProvider(OAuth2AccessTokenProvider.class))) {
			assertThat(mailer.getOperationalConfig().getOAuth2AccessTokenProvider()).isSameAs(provider);
		}
	}

	@Test
	void multipleProviderBeansShouldRequireDisambiguation() {
		final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		registerProvider(beanFactory, "first", () -> "one", false);
		registerProvider(beanFactory, "second", () -> "two", false);

		assertThatThrownBy(() -> springSupport().defaultMailer(factory(false), beanFactory.getBeanProvider(OAuth2AccessTokenProvider.class)))
				.isInstanceOf(NoUniqueBeanDefinitionException.class);
	}

	@Test
	void primaryProviderBeanShouldBeAppliedWhenSeveralExist() throws Exception {
		final DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		final OAuth2AccessTokenProvider primary = () -> "primary";
		registerProvider(beanFactory, "primary", primary, true);
		registerProvider(beanFactory, "secondary", () -> "secondary", false);

		try (Mailer mailer = springSupport().defaultMailer(factory(true), beanFactory.getBeanProvider(OAuth2AccessTokenProvider.class))) {
			assertThat(mailer.getOperationalConfig().getOAuth2AccessTokenProvider()).isSameAs(primary);
		}
	}

	private static SimpleJavaMailSpringSupport springSupport() {
		return new SimpleJavaMailSpringSupport(new StandardEnvironment());
	}

	private static SimpleJavaMail factory(final boolean oauth2) {
		final Map<String, Object> properties = new LinkedHashMap<>();
		properties.put(ConfigLoader.Property.TRANSPORT_MODE_LOGGING_ONLY.key(), true);
		if (oauth2) {
			properties.put(ConfigLoader.Property.TRANSPORT_STRATEGY.key(), TransportStrategy.SMTP_OAUTH2);
		}
		return SimpleJavaMail.withConfig(ConfigLoader.builder()
				.withMap(properties)
				.load());
	}

	private static void registerProvider(DefaultListableBeanFactory beanFactory,
			String name,
			OAuth2AccessTokenProvider provider,
			boolean primary) {
		final RootBeanDefinition definition = new RootBeanDefinition(OAuth2AccessTokenProvider.class);
		definition.setInstanceSupplier(() -> provider);
		definition.setPrimary(primary);
		beanFactory.registerBeanDefinition(name, definition);
	}
}
