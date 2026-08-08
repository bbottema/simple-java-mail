package org.simplejavamail.springsupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.api.mailer.config.OAuth2AccessTokenProvider;
import org.springframework.beans.factory.NoUniqueBeanDefinitionException;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.core.env.ConfigurableEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SimpleJavaMailSpringOAuth2ProviderTest {

	@Test
	void noProviderBeanShouldLeaveDefaultMailerBuilderUntouched() {
		DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		MailerGenericBuilder<?> builder = builder();
		Mailer expectedMailer = builder.buildMailer();

		Mailer mailer = springSupport().defaultMailer(builder, beanFactory.getBeanProvider(OAuth2AccessTokenProvider.class));

		assertThat(mailer).isSameAs(expectedMailer);
		verify(builder, never()).withOAuth2AccessTokenProvider(any());
	}

	@Test
	void oneProviderBeanShouldBeAppliedToDefaultMailer() {
		DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		OAuth2AccessTokenProvider provider = () -> "token";
		registerProvider(beanFactory, "provider", provider, false);
		MailerGenericBuilder<?> builder = builder();

		springSupport().defaultMailer(builder, beanFactory.getBeanProvider(OAuth2AccessTokenProvider.class));

		verify(builder).withOAuth2AccessTokenProvider(provider);
	}

	@Test
	void multipleProviderBeansShouldRequireDisambiguation() {
		DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		registerProvider(beanFactory, "first", () -> "one", false);
		registerProvider(beanFactory, "second", () -> "two", false);
		MailerGenericBuilder<?> builder = builder();

		assertThatThrownBy(() -> springSupport().defaultMailer(builder, beanFactory.getBeanProvider(OAuth2AccessTokenProvider.class)))
				.isInstanceOf(NoUniqueBeanDefinitionException.class);
		verify(builder, never()).buildMailer();
	}

	@Test
	void primaryProviderBeanShouldBeAppliedWhenSeveralExist() {
		DefaultListableBeanFactory beanFactory = new DefaultListableBeanFactory();
		OAuth2AccessTokenProvider primary = () -> "primary";
		registerProvider(beanFactory, "primary", primary, true);
		registerProvider(beanFactory, "secondary", () -> "secondary", false);
		MailerGenericBuilder<?> builder = builder();

		springSupport().defaultMailer(builder, beanFactory.getBeanProvider(OAuth2AccessTokenProvider.class));

		verify(builder).withOAuth2AccessTokenProvider(primary);
	}

	private static SimpleJavaMailSpringSupport springSupport() {
		return new SimpleJavaMailSpringSupport(mock(ConfigurableEnvironment.class));
	}

	@SuppressWarnings("unchecked")
	private static MailerGenericBuilder<?> builder() {
		MailerGenericBuilder<?> builder = mock(MailerGenericBuilder.class);
		Mailer mailer = mock(Mailer.class);
		when(builder.buildMailer()).thenReturn(mailer);
		return builder;
	}

	private static void registerProvider(DefaultListableBeanFactory beanFactory,
			String name,
			OAuth2AccessTokenProvider provider,
			boolean primary) {
		RootBeanDefinition definition = new RootBeanDefinition(OAuth2AccessTokenProvider.class);
		definition.setInstanceSupplier(() -> provider);
		definition.setPrimary(primary);
		beanFactory.registerBeanDefinition(name, definition);
	}
}
