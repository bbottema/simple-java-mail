package org.simplejavamail.internal.clisupport;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.config.ConfigLoader;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class OneShotMailerProviderTest {
	@Test
	void everyLeaseOwnsAndClosesItsMailerExactlyOnce() throws Exception {
		final Mailer mailer = mock(Mailer.class);
		final OneShotMailerProvider provider = new OneShotMailerProvider();
		final CliMailerProfile profile = CliMailerProfile.create(ConfigLoader.builder().load(), List.of(), null);

		final MailerProvider.Lease lease = provider.acquire(profile, () -> mailer);
		assertThat(lease.mailer()).isSameAs(mailer);
		lease.close();
		lease.close();

		verify(mailer, times(1)).close();
	}
}
