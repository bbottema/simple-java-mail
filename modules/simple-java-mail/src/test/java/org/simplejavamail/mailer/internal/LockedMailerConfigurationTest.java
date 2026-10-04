package org.simplejavamail.mailer.internal;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.mailer.CustomMailer;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.config.TransportStrategy;

import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import static java.util.Locale.ROOT;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.simplejavamail.mailer.internal.LockedEmailConfigurationTest.locked;

class LockedMailerConfigurationTest {

	@Test
	void builderAcceptsTheSameParsedValuesAndRejectsDifferentValuesAndResets() throws Exception {
		final SimpleJavaMail factory = locked(Map.of("smtp.host", "relay.example.org", "smtp.port", "02525"));
		try (Mailer first = factory.mailerBuilder().withSMTPServerPort(2525).buildMailer();
				Mailer second = factory.mailerBuilder().buildMailer()) {
			assertThat(first.getServerConfig().getPort()).isEqualTo(2525);
			assertThat(second.getServerConfig().getHost()).isEqualTo("relay.example.org");
		}
		assertThatThrownBy(() -> factory.mailerBuilder().withSMTPServerHost("other.example.org").buildMailer()).hasMessageContaining("locked.smtp.host");
		assertThatThrownBy(() -> factory.mailerBuilder().withSMTPServerPort(587).buildMailer()).hasMessageContaining("locked.smtp.port");
	}

	@Test
	void lowLevelPropertiesCannotChangeALockedHostOrTransportStrategy() {
		final SimpleJavaMail factory = locked(Map.of("smtp.host", "relay.example.org", "transportstrategy", "SMTP_TLS"));
		assertThatThrownBy(() -> factory.mailerBuilder().withProperty("mail.smtp.host", "other.example.org").buildMailer())
				.hasMessageContaining("locked.smtp.host");
		assertThatThrownBy(() -> factory.mailerBuilder().withProperty("mail.smtp.starttls.enable", "false").buildMailer())
				.hasMessageContaining("locked.transportstrategy");
	}

	@Test
	void callerOwnedSessionsAreCheckedBeforeBeingMutated() throws Exception {
		final SimpleJavaMail factory = locked(Map.of("smtp.host", "relay.example.org"));
		assertThatThrownBy(() -> factory.mailerBuilder(Session.getInstance(new Properties())).buildMailer())
				.hasMessageContaining("locked.smtp.host").hasMessageContaining("You supplied a Session")
				.hasMessageContaining("no recognized Simple Java Mail transport strategy").hasMessageContaining("factory locks an SMTP setting")
				.hasMessageContaining("mailerBuilder() without a Session").hasMessageContaining("remove this lock");
		final Properties properties = TransportStrategy.SMTP.generateProperties();
		properties.setProperty("mail.smtp.host", "other.example.org");
		final Session session = Session.getInstance(properties);
		final Properties before = (Properties) properties.clone();
		assertThatThrownBy(() -> factory.mailerBuilder(session).buildMailer()).hasMessageContaining("locked.smtp.host")
				.hasMessageContaining("different value for Jakarta Mail property mail.smtp.host").hasMessageContaining("agree with the lock")
				.hasMessageNotContaining("other.example.org").hasMessageNotContaining("relay.example.org");
		assertThat(properties).isEqualTo(before);
		properties.setProperty("mail.smtp.host", "relay.example.org");
		try (Mailer mailer = factory.mailerBuilder(session).buildMailer()) {
			assertThat(mailer.getSession()).isSameAs(session);
			assertThatThrownBy(() -> factory.mailerBuilder(session).buildMailer()).hasMessageContaining("already used by another Mailer")
					.hasMessageContaining("Both Mailers have locked configuration")
					.hasMessageContaining("settings fixed by simplejavamail.locked.* properties")
					.hasMessageContaining("replace the retained configuration used to check those locks")
					.hasMessageContaining("Reuse the existing Mailer").hasMessageContaining("mailerBuilder() without a Session");
		}
	}

	@Test
	void reusedSessionErrorsIdentifyWhichMailerHasLocks() throws Exception {
		final SimpleJavaMail ordinary = locked(Map.of());
		final SimpleJavaMail restricted = locked(Map.of("smtp.host", "relay.example.org"));
		try (Mailer existing = ordinary.mailerBuilder().withSMTPServerHost("relay.example.org").buildMailer()) {
			final Properties before = (Properties) existing.getSession().getProperties().clone();
			assertThatThrownBy(() -> restricted.mailerBuilder(existing.getSession()).buildMailer())
					.hasMessageContaining("The Mailer you are building has locked configuration")
					.hasMessageContaining("settings fixed by simplejavamail.locked.* properties")
					.hasMessageNotContaining("The existing Mailer has locked configuration");
			assertThat(existing.getSession().getProperties()).isEqualTo(before);
		}
		try (Mailer existing = restricted.mailerBuilder().buildMailer()) {
			final Properties before = (Properties) existing.getSession().getProperties().clone();
			assertThatThrownBy(() -> ordinary.mailerBuilder(existing.getSession()).buildMailer())
					.hasMessageContaining("The existing Mailer has locked configuration")
					.hasMessageContaining("settings fixed by simplejavamail.locked.* properties")
					.hasMessageNotContaining("The Mailer you are building has locked configuration");
			assertThat(existing.getSession().getProperties()).isEqualTo(before);
		}
	}

	@Test
	void aPoolSessionWithoutRetainedConfigurationExplainsWhyOriginLocksCannotBeChecked() throws Exception {
		try (Mailer owner = locked(Map.of("smtp.host", "relay.example.org")).mailerBuilder().buildMailer()) {
			final Session externallyManaged = Session.getInstance(TransportStrategy.SMTP.generateProperties());
			assertThatThrownBy(() -> SessionBasedEmailToMimeMessageConverter.verifySelectedSession(owner.getSession(), externallyManaged))
					.hasMessageContaining("Mailer you are sending through has locked settings")
					.hasMessageContaining("values fixed by simplejavamail.locked.* properties")
					.hasMessageContaining("Session selected from its pool has no retained Mailer configuration")
					.hasMessageContaining("cannot check that connection against this Mailer's locks")
					.hasMessageContaining("Register the participating connections").hasMessageContaining("separate pool cluster");
		}
	}

	@Test
	void selectedConfigurationConflictsIdentifyThePoolDestinationRatherThanABuilderCall() throws Exception {
		try (Mailer origin = locked(Map.of("smtp.legacycontentsupport", "true")).mailerBuilder().withSMTPServerHost("relay.example.org").buildMailer();
				Mailer destination = locked(Map.of()).mailerBuilder().withSMTPServerHost("relay.example.org").buildMailer()) {
			assertThatThrownBy(() -> SessionBasedEmailToMimeMessageConverter.verifySelectedSession(origin.getSession(), destination.getSession()))
					.hasMessageContaining("locked.smtp.legacycontentsupport")
					.hasMessageContaining("selected SMTP configuration does not match this Mailer's locked setting")
					.hasMessageContaining("selected Mailer's configuration agree with the lock").hasMessageContaining("separate pool clusters");
		}
	}

	@Test
	void hiddenAuthenticationAndOpaqueCustomTransportAreNotTreatedAsVerified() {
		final SimpleJavaMail factory = locked(Map.of("smtp.password", "central-secret"));
		final Authenticator authenticator = new Authenticator() {
			@Override
			protected PasswordAuthentication getPasswordAuthentication() {
				return new PasswordAuthentication("session-user", "session-secret");
			}
		};
		assertThatThrownBy(() -> factory.mailerBuilder(Session.getInstance(new Properties(), authenticator)).buildMailer())
				.hasMessageContaining("locked.smtp.password").hasMessageContaining("factory locks the SMTP credential")
				.hasMessageContaining("This key uses simplejavamail.locked.*")
				.hasMessageContaining("fixed for every Mailer created by this factory")
				.hasMessageContaining("you supplied a Session").hasMessageContaining("cannot inspect or replace that Authenticator")
				.hasMessageContaining("mailerBuilder() without a Session").hasMessageContaining("remove the SMTP credential lock")
				.hasMessageNotContaining("central-secret").hasMessageNotContaining("session-secret").hasMessageNotContaining("session-user");
		assertThatThrownBy(() -> factory.mailerBuilder().withCustomMailer(mock(CustomMailer.class)).buildMailer())
				.hasMessageContaining("withCustomMailer(...)").hasMessageContaining("callback")
				.hasMessageContaining("factory locks a setting").hasMessageContaining("cannot check what the callback does")
				.hasMessageContaining("remove the corresponding locks").hasMessageNotContaining("central-secret");
		assertThatThrownBy(() -> locked(Map.of("defaults.bcc.address", "archive@example.org")).mailerBuilder()
				.withCustomMailer(mock(CustomMailer.class)).buildMailer()).hasMessageContaining("delivery envelope");
	}

	@Test
	void callerOwnedExecutorsAndRuntimeCredentialsExplainTheirConflictWithLocks() {
		assertThatThrownBy(() -> locked(Map.of("defaults.poolsize", "1")).mailerBuilder()
				.withExecutorService(mock(ExecutorService.class)).buildMailer())
				.hasMessageContaining("withExecutorService(...)").hasMessageContaining("factory locks worker or queue settings")
				.hasMessageContaining("cannot configure or verify").hasMessageContaining("remove the corresponding locks");
		assertThatThrownBy(() -> locked(Map.of("smtp.password", "central-secret")).mailerBuilder()
				.withOAuth2AccessTokenProvider(() -> "runtime-secret").buildMailer())
				.hasMessageContaining("withOAuth2AccessTokenProvider(...)").hasMessageContaining("factory locks the SMTP credential")
				.hasMessageContaining("these settings cannot be combined").hasMessageContaining("remove the SMTP credential lock")
				.hasMessageNotContaining("central-secret").hasMessageNotContaining("runtime-secret");
	}

	@ParameterizedTest
	@ValueSource(booleans = {false, true})
	void callerOwnedSessionsCannotConfirmALockedUsernameEvenWhenItsVisiblePropertyMatches(final boolean withAuthenticator) {
		final SimpleJavaMail factory = locked(Map.of("smtp.username", "company-user"));
		final Properties properties = TransportStrategy.SMTP.generateProperties();
		properties.setProperty("mail.smtp.host", "relay.example.org");
		properties.setProperty("mail.smtp.user", "company-user");
		properties.setProperty("mail.smtp.auth", "true");
		final AtomicInteger authenticationCalls = new AtomicInteger();
		final Authenticator authenticator = new Authenticator() {
			@Override
			protected PasswordAuthentication getPasswordAuthentication() {
				authenticationCalls.incrementAndGet();
				return new PasswordAuthentication("another-user", "session-secret");
			}
		};
		final Session session = Session.getInstance(properties, withAuthenticator ? authenticator : null);
		final Properties before = (Properties) properties.clone();
		assertThatThrownBy(() -> factory.mailerBuilder(session).buildMailer())
				.hasMessageContaining("locked.smtp.username").hasMessageContaining("factory locks the SMTP username")
				.hasMessageContaining("cached credentials").hasMessageContaining("Authenticator")
				.hasMessageContaining("mailerBuilder() without a Session").hasMessageContaining("remove the SMTP username lock")
				.hasMessageNotContaining("company-user").hasMessageNotContaining("another-user").hasMessageNotContaining("session-secret");
		assertThat(properties).isEqualTo(before);
		assertThat(authenticationCalls).hasValue(0);
	}

	@Test
	void aManagedSessionUsesTheLockedUsername() throws Exception {
		try (Mailer mailer = locked(Map.of("smtp.username", "company-user")).mailerBuilder()
				.withSMTPServerHost("relay.example.org").buildMailer()) {
			assertThat(mailer.getServerConfig().getUsername()).isEqualTo("company-user");
			assertThat(mailer.getSession().getProperty("mail.smtp.user")).isEqualTo("company-user");
		}
	}

	@Test
	void embeddedImageLocksAreCheckedBeforeResolution() {
		final SimpleJavaMail factory = locked(Map.of("embeddedimages.dynamicresolution.enable.dir", "false"));
		assertThatThrownBy(() -> factory.emailBuilder().startingBlank().withEmbeddedImageAutoResolutionForFiles(true).buildEmail())
				.hasMessageContaining("locked.embeddedimages");
	}

	@Test
	void lockedExtrasRemainRequiredOnTheSelectedSession() throws Exception {
		final SimpleJavaMail factory = locked(Map.of("extraproperties.mail.smtp.ehlo", "true"));
		assertThatThrownBy(() -> factory.mailerBuilder().withSMTPServerHost("relay.example.org").withProperty("mail.smtp.ehlo", "false").buildMailer())
				.hasMessageContaining("locked.extraproperties.mail.smtp.ehlo");
		try (Mailer mailer = factory.mailerBuilder().withSMTPServerHost("relay.example.org").buildMailer()) {
			mailer.getSession().getProperties().setProperty("mail.smtp.ehlo", "false");
			assertThatThrownBy(() -> SessionBasedEmailToMimeMessageConverter.verifySelectedSession(mailer.getSession(), mailer.getSession()))
					.hasMessageContaining("locked.extraproperties.mail.smtp.ehlo");
		}
	}

	@Test
	void lockedLowLevelTimeoutReplacesAutomaticDefaultsButRejectsExplicitContradictions() throws Exception {
		final SimpleJavaMail factory = locked(Map.of("extraproperties.mail.smtp.timeout", "1000"));
		try (Mailer mailer = factory.mailerBuilder().withSMTPServerHost("relay.example.org").buildMailer()) {
			assertThat(mailer.getSession().getProperty("mail.smtp.timeout")).isEqualTo("1000");
		}
		assertThatThrownBy(() -> factory.mailerBuilder().withSMTPServerHost("relay.example.org").withSessionTimeout(2000).buildMailer())
				.hasMessageContaining("locked.extraproperties.mail.smtp.timeout");
		try (Mailer ignored = factory.mailerBuilder().withSMTPServerHost("relay.example.org").withSessionTimeout(1000).buildMailer()) {
			assertThat(ignored.getSession().getProperty("mail.smtp.timeout")).isEqualTo("1000");
		}
	}

	@Test
	void contradictoryLocksAreRejectedWithoutExposingEitherValue() {
		final SimpleJavaMail factory = locked(Map.of("smtp.host", "one.example.org", "extraproperties.mail.smtp.host", "two.example.org"));
		assertThatThrownBy(() -> factory.mailerBuilder().buildMailer()).hasMessageContaining("locked.extraproperties.mail.smtp.host")
				.hasMessageContaining("simplejavamail.locked.smtp.host").hasMessageContaining("configure the same SMTP setting, but their values differ")
				.hasMessageContaining("Make the two locked values agree").hasMessageContaining("remove one of these locks")
				.hasMessageNotContaining("one.example.org").hasMessageNotContaining("two.example.org");
	}

	@Test
	void trustAllCannotBypassALockedTrustedHostList() {
		assertThatThrownBy(() -> locked(Map.of("defaults.trustedhosts", "relay.example.org")).mailerBuilder()
				.withSMTPServerHost("relay.example.org").trustingAllHosts(true).buildMailer()).hasMessageContaining("locked.defaults.trustedhosts");
	}

	@Test
	void lockedClusterIdentifiersCompareTheirUuidValueRatherThanLetterCase() throws Exception {
		final UUID cluster = UUID.randomUUID();
		final SimpleJavaMail factory = locked(Map.of("defaults.connectionpool.clusterkey.uuid", cluster.toString().toUpperCase(ROOT)));
		try (Mailer mailer = factory.mailerBuilder().withSMTPServerHost("relay.example.org").withConnectionPoolCoreSize(0)
				.withClusterKey(cluster).buildMailer()) {
			assertThat(mailer.getOperationalConfig().getClusterKey()).isEqualTo(cluster);
		}
		assertThatThrownBy(() -> factory.mailerBuilder().withClusterKey(UUID.randomUUID()).buildMailer()).hasMessageContaining("locked.defaults");
	}

	@Test
	void lockedTrustedHostsUseTheExistingListParsingIncludingTrailingSeparators() throws Exception {
		final SimpleJavaMail factory = locked(Map.of("defaults.trustedhosts", "relay.example.org;backup.example.org;"));
		try (Mailer mailer = factory.mailerBuilder().withSMTPServerHost("relay.example.org").buildMailer()) {
			assertThat(mailer.getSession().getProperty("mail.smtp.ssl.trust")).isEqualTo("relay.example.org backup.example.org");
		}
		assertThatThrownBy(() -> factory.mailerBuilder().withSMTPServerHost("relay.example.org").trustingSSLHosts("other.example.org").buildMailer())
				.hasMessageContaining("locked.defaults.trustedhosts");
	}

	@Test
	void unrecognizedBooleanTextIsNotAssumedToMeanFalse() {
		assertThatThrownBy(() -> locked(Map.of("defaults.verifyserveridentity", "false")).mailerBuilder()
				.withSMTPServerHost("relay.example.org").withProperty("mail.smtp.ssl.checkserveridentity", "not-a-boolean").buildMailer())
				.hasMessageContaining("locked.defaults.verifyserveridentity");
	}

	@Test
	void plainSocketFactoriesCannotHideCustomTlsHandlingFromLockedCertificateChecks() {
		assertThatThrownBy(() -> locked(Map.of("defaults.verifyserveridentity", "true")).mailerBuilder()
				.withSMTPServerHost("relay.example.org").withProperty("mail.smtp.socketFactory.class", "application.CustomFactory").buildMailer())
				.hasMessageContaining("caller-supplied socket factory").hasMessageContaining("locked.defaults.verifyserveridentity")
				.hasMessageContaining("Remove the custom socket factory")
				.hasMessageContaining("or remove the locked properties for certificate trust and server-identity checking");
	}

	@Test
	void lockingSmtpDoesNotImplicitlyLockItsSeparateOpportunisticTlsSetting() throws Exception {
		final SimpleJavaMail factory = locked(Map.of("transportstrategy", "SMTP"));
		try (Mailer mailer = factory.mailerBuilder().withSMTPServerHost("relay.example.org").withOpportunisticTLS(false).buildMailer()) {
			assertThat(mailer.getSession().getProperty("mail.smtp.starttls.enable")).isNull();
		}
	}

	@Test
	void lockedDisabledOpportunisticTlsAcceptsTheNormalAbsentSessionProperty() throws Exception {
		final SimpleJavaMail factory = locked(Map.of("opportunistic.tls", "false"));
		try (Mailer mailer = factory.mailerBuilder().withSMTPServerHost("relay.example.org").buildMailer()) {
			assertThat(mailer.getSession().getProperty("mail.smtp.starttls.enable")).isNull();
		}
		assertThatThrownBy(() -> factory.mailerBuilder().withSMTPServerHost("relay.example.org").withOpportunisticTLS(true).buildMailer())
				.hasMessageContaining("locked.opportunistic.tls");
	}
}
