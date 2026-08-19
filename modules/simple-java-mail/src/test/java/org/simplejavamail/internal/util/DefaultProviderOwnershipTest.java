package org.simplejavamail.internal.util;

import jakarta.activation.spi.MailcapRegistryProvider;
import jakarta.activation.spi.MimeTypeRegistryProvider;
import jakarta.mail.util.StreamProvider;
import org.junit.jupiter.api.Test;

import java.util.ServiceLoader;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultProviderOwnershipTest {

	@Test
	void defaultRuntimeUsesAngusForJakartaMailAndActivationServices() {
		assertThat(StreamProvider.provider().getClass().getName())
				.isEqualTo("org.eclipse.angus.mail.util.MailStreamProvider");
		assertThat(firstProvider(MailcapRegistryProvider.class).getClass().getName())
				.isEqualTo("org.eclipse.angus.activation.MailcapRegistryProviderImpl");
		assertThat(firstProvider(MimeTypeRegistryProvider.class).getClass().getName())
				.isEqualTo("org.eclipse.angus.activation.MimeTypeRegistryProviderImpl");
	}

	private static <T> T firstProvider(final Class<T> service) {
		return ServiceLoader.load(service).iterator().next();
	}
}
