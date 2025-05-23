package org.simplejavamail.springsupport;

import org.jetbrains.annotations.Nullable;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.api.mailer.MailerGenericBuilder;
import org.simplejavamail.config.ConfigLoader;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.mailer.internal.MailerRegularBuilderImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;

import java.util.Properties;

/**
 * Provides a {@link Mailer} bean completely configured from property defaults as well as a {@link MailerGenericBuilder}
 * for your convenience in case you wish to further configure it in Java code. For this to work, you need to include
 * default properties in your Spring config.
 * <p>
 * Note that there are some overloaded property names, which is because of the recently added Spring Boot IDE hints support,
 * and due to the fact that Spring Boot does not support dashes.
 * <p>
 * Using profiles, you can have environment specific configurations that way. See
 * <a href="https://www.simplejavamail.org/#/configuration">simplejavamail.org</a> for example configuration.
 * <p>
 * The following properties will be applied
 * <ul>
 * <li>simplejavamail.javaxmail.debug</li>
 * <li>simplejavamail.transportstrategy</li>
 * <li>simplejavamail.smtp.host</li>
 * <li>simplejavamail.smtp.port</li>
 * <li>simplejavamail.smtp.username</li>
 * <li>simplejavamail.smtp.password</li>
 * <li>simplejavamail.disable.all.clientvalidation</li>
 * <li>simplejavamail.custom.sslfactory.class</li>
 * <li>simplejavamail.proxy.host</li>
 * <li>simplejavamail.proxy.port</li>
 * <li>simplejavamail.proxy.username</li>
 * <li>simplejavamail.proxy.password</li>
 * <li>simplejavamail.proxy.socks5bridge.port</li>
 * <li>simplejavamail.defaults.content.transfer.encoding</li>
 * <li>simplejavamail.defaults.subject</li>
 * <li>simplejavamail.defaults.from.name</li>
 * <li>simplejavamail.defaults.from.address</li>
 * <li>simplejavamail.defaults.replyto.name</li>
 * <li>simplejavamail.defaults.replyto.address</li>
 * <li>simplejavamail.defaults.bounceto.name</li>
 * <li>simplejavamail.defaults.bounceto.address</li>
 * <li>simplejavamail.defaults.to.name</li>
 * <li>simplejavamail.defaults.to.address</li>
 * <li>simplejavamail.defaults.cc.name</li>
 * <li>simplejavamail.defaults.cc.address</li>
 * <li>simplejavamail.defaults.bcc.name</li>
 * <li>simplejavamail.defaults.bcc.address</li>
 * <li>simplejavamail.defaults.poolsize</li>
 * <li>simplejavamail.defaults.poolsize.keepalivetime</li>
 * <li>simplejavamail.defaults.poolsize-more.keepalivetime</li>
 * <li>simplejavamail.defaults.connectionpool.clusterkey.uuid</li>
 * <li>simplejavamail.defaults.connectionpool.coresize</li>
 * <li>simplejavamail.defaults.connectionpool.maxsize</li>
 * <li>simplejavamail.defaults.connectionpool.claimtimeout.millis</li>
 * <li>simplejavamail.defaults.connectionpool.expireafter.millis</li>
 * <li>simplejavamail.defaults.connectionpool.loadbalancing.strategy</li>
 * <li>simplejavamail.defaults.sessiontimeoutmillis</li>
 * <li>simplejavamail.defaults.trustallhosts</li>
 * <li>simplejavamail.defaults.trustedhosts</li>
 * <li>simplejavamail.defaults.verifyserveridentity</li>
 * <li>simplejavamail.transport.mode.logging.only</li>
 * <li>simplejavamail.opportunistic.tls</li>
 * <li>simplejavamail.smime.signing.keystore</li>
 * <li>simplejavamail.smime.signing.keystore_password</li>
 * <li>simplejavamail.smime.signing.keystore-password</li>
 * <li>simplejavamail.smime.signing.key_alias</li>
 * <li>simplejavamail.smime.signing.key-alias</li>
 * <li>simplejavamail.smime.signing.key_password</li>
 * <li>simplejavamail.smime.signing.key-password</li>
 * <li>simplejavamail.smime.encryption.certificate</li>
 * <li>simplejavamail.smime.signing.algorithm</li>
 * <li>simplejavamail.smime.encryption.key_encapsulation_algorithm</li>
 * <li>simplejavamail.smime.encryption.cipher</li>
 * <li>simplejavamail.dkim.signing.private_key_file_or_data</li>
 * <li>simplejavamail.dkim.signing.private-key-file-or-data</li>
 * <li>simplejavamail.dkim.signing.selector</li>
 * <li>simplejavamail.dkim.signing.signing_domain</li>
 * <li>simplejavamail.dkim.signing.signing-domain</li>
 * <li>simplejavamail.dkim.signing.use_length_param</li>
 * <li>simplejavamail.dkim.signing.excluded_headers_from_default_signing_list</li>
 * <li>simplejavamail.dkim.signing.excluded-headers-from-default-signing-list</li>
 * <li>simplejavamail.dkim.signing.header_canonicalization</li>
 * <li>simplejavamail.dkim.signing.body_canonicalization</li>
 * <li>simplejavamail.dkim.signing.algorithm</li>
 * <li>simplejavamail.embeddedimages.dynamicresolution.enable.dir</li>
 * <li>simplejavamail.embeddedimages.dynamicresolution.enable.url</li>
 * <li>simplejavamail.embeddedimages.dynamicresolution.enable.classpath</li>
 * <li>simplejavamail.embeddedimages.dynamicresolution.base.dir</li>
 * <li>simplejavamail.embeddedimages.dynamicresolution.base.url</li>
 * <li>simplejavamail.embeddedimages.dynamicresolution.base.classpath</li>
 * <li>simplejavamail.embeddedimages.dynamicresolution.outside.base.dir</li>
 * <li>simplejavamail.embeddedimages.dynamicresolution.outside.base.classpath</li>
 * <li>simplejavamail.embeddedimages.dynamicresolution.outside.base.url</li>
 * <li>simplejavamail.embeddedimages.dynamicresolution.mustbesuccesful</li>
 * <li>simplejavamail.extraproperties.*</li>
 * </ul>
 */
@Configuration
public class SimpleJavaMailSpringSupport {

	private final ConfigurableEnvironment environment;

	// for unknown reason @RequiredArgsConstructor doesn't work for this class
	public SimpleJavaMailSpringSupport(ConfigurableEnvironment environment) {
		this.environment = environment;
	}

	@Bean
	public Mailer defaultMailer(MailerGenericBuilder<?> defaultMailerBuilder) {
		return defaultMailerBuilder.buildMailer();
	}

	@SuppressWarnings("deprecation")
	@Bean("defaultMailerBuilder")
	public MailerGenericBuilder<?> loadGlobalConfigAndCreateDefaultMailer(
				// now obviously there are easier ways to do this, but this is the only way
				// I can think of that actually works across Spring versions
				@Nullable @Value("${simplejavamail.javaxmail.debug:#{null}}") final String javaxmailDebug,
				@Nullable @Value("${simplejavamail.transportstrategy:#{null}}") final String transportstrategy,
				@Nullable @Value("${simplejavamail.smtp.host:#{null}}") final String smtpHost,
				@Nullable @Value("${simplejavamail.smtp.port:#{null}}") final String smtpPort,
				@Nullable @Value("${simplejavamail.smtp.username:#{null}}") final String smtpUsername,
				@Nullable @Value("${simplejavamail.smtp.password:#{null}}") final String smtpPassword,
				@Nullable @Value("${simplejavamail.disable.all.clientvalidation:#{null}}") final String disableAllClientValidation,
				@Nullable @Value("${simplejavamail.custom.sslfactory.class:#{null}}") final String customSSLFactoryClass,
				@Nullable @Value("${simplejavamail.custom.sslfactory.clazz:#{null}}") final String customSSLFactoryClassSpringBoot,
				@Nullable @Value("${simplejavamail.proxy.host:#{null}}") final String proxyHost,
				@Nullable @Value("${simplejavamail.proxy.port:#{null}}") final String proxyPort,
				@Nullable @Value("${simplejavamail.proxy.username:#{null}}") final String proxyUsername,
				@Nullable @Value("${simplejavamail.proxy.password:#{null}}") final String proxyPassword,
				@Nullable @Value("${simplejavamail.proxy.socks5bridge.port:#{null}}") final String proxySocks5bridgePort,
				@Nullable @Value("${simplejavamail.defaults.content.transfer.encoding:#{null}}") final String defaultContentTransferEncoding,
				@Nullable @Value("${simplejavamail.defaults.subject:#{null}}") final String defaultSubject,
				@Nullable @Value("${simplejavamail.defaults.from.name:#{null}}") final String defaultFromName,
				@Nullable @Value("${simplejavamail.defaults.from.address:#{null}}") final String defaultFromAddress,
				@Nullable @Value("${simplejavamail.defaults.replyto.name:#{null}}") final String defaultReplytoName,
				@Nullable @Value("${simplejavamail.defaults.replyto.address:#{null}}") final String defaultReplytoAddress,
				@Nullable @Value("${simplejavamail.defaults.bounceto.name:#{null}}") final String defaultBouncetoName,
				@Nullable @Value("${simplejavamail.defaults.bounceto.address:#{null}}") final String defaultBouncetoAddress,
				@Nullable @Value("${simplejavamail.defaults.to.name:#{null}}") final String defaultToName,
				@Nullable @Value("${simplejavamail.defaults.to.address:#{null}}") final String defaultToAddress,
				@Nullable @Value("${simplejavamail.defaults.cc.name:#{null}}") final String defaultCcName,
				@Nullable @Value("${simplejavamail.defaults.cc.address:#{null}}") final String defaultCcAddress,
				@Nullable @Value("${simplejavamail.defaults.bcc.name:#{null}}") final String defaultBccName,
				@Nullable @Value("${simplejavamail.defaults.bcc.address:#{null}}") final String defaultBccAddress,
				@Nullable @Value("${simplejavamail.defaults.poolsize:#{null}}") final String defaultPoolsize,
				@Nullable @Value("${simplejavamail.defaults.poolsize.keepalivetime:#{null}}") final String defaultPoolKeepAlivetime,
				@Nullable @Value("${simplejavamail.defaults.poolsize-more.keepalivetime:#{null}}") final String defaultPoolKeepAlivetimeSpringBoot,
				@Nullable @Value("${simplejavamail.defaults.connectionpool.clusterkey.uuid:#{null}}") final String defaultConnectionPoolCluterKey,
				@Nullable @Value("${simplejavamail.defaults.connectionpool.coresize:#{null}}") final String defaultConnectionPoolCoreSize,
				@Nullable @Value("${simplejavamail.defaults.connectionpool.maxsize:#{null}}") final String defaultConnectionPoolMaxSize,
				@Nullable @Value("${simplejavamail.defaults.connectionpool.claimtimeout.millis:#{null}}") final String defaultConnectionPoolClaimTimeoutMillis,
				@Nullable @Value("${simplejavamail.defaults.connectionpool.expireafter.millis:#{null}}") final String defaultConnectionPoolExpireAfterMillis,
				@Nullable @Value("${simplejavamail.defaults.connectionpool.loadbalancing.strategy:#{null}}") final String defaultConnectionPoolLoadBalancingStrategy,
				@Nullable @Value("${simplejavamail.defaults.sessiontimeoutmillis:#{null}}") final String defaultSessionTimeoutMillis,
				@Nullable @Value("${simplejavamail.defaults.trustallhosts:#{null}}") final String defaultTrustAllHosts,
				@Nullable @Value("${simplejavamail.defaults.trustedhosts:#{null}}") final String defaultTrustedHosts,
				@Nullable @Value("${simplejavamail.defaults.verifyserveridentity:#{null}}") final String defaultVerifyServerIdentity,
				@Nullable @Value("${simplejavamail.transport.mode.logging.only:#{null}}") final String transportModeLoggingOnly,
				@Nullable @Value("${simplejavamail.opportunistic.tls:#{null}}") final String opportunisticTls,
				@Nullable @Value("${simplejavamail.smime.signing.keystore:#{null}}") final String smimeSigningKeyStore,
				@Nullable @Value("${simplejavamail.smime.signing.keystore_password:#{null}}") final String smimeSigningKeyStorePassword,
				@Nullable @Value("${simplejavamail.smime.signing.keystore-password:#{null}}") final String smimeSigningKeyStorePasswordSpringBoot,
				@Nullable @Value("${simplejavamail.smime.signing.key_alias:#{null}}") final String smimeSigningKeyAlias,
				@Nullable @Value("${simplejavamail.smime.signing.key-alias:#{null}}") final String smimeSigningKeyAliasSpringBoot,
				@Nullable @Value("${simplejavamail.smime.signing.key_password:#{null}}") final String smimeSigningKeyPassword,
				@Nullable @Value("${simplejavamail.smime.signing.key-password:#{null}}") final String smimeSigningKeyPasswordSpringBoot,
				@Nullable @Value("${simplejavamail.smime.encryption.certificate:#{null}}") final String smimeEncryptionCertificate,
				@Nullable @Value("${simplejavamail.smime.signing.algorithm:#{null}}") final String smimeSigningAlgorithm,
				@Nullable @Value("${simplejavamail.smime.encryption.key_encapsulation_algorithm:#{null}}") final String smimeEncryptionKeyEncapsulationAlgorithm,
				@Nullable @Value("${simplejavamail.smime.encryption.cipher:#{null}}") final String smimeEncryptionCipher,
				@Nullable @Value("${simplejavamail.dkim.signing.private_key_file_or_data:#{null}}") final String dkimSigningPrivateKeyFileOrData,
				@Nullable @Value("${simplejavamail.dkim.signing.private-key-file-or-data:#{null}}") final String dkimSigningPrivateKeyFileOrDataSpringBoot,
				@Nullable @Value("${simplejavamail.dkim.signing.selector:#{null}}") final String dkimSigningSelector,
				@Nullable @Value("${simplejavamail.dkim.signing.signing_domain:#{null}}") final String dkimSigningDomain,
				@Nullable @Value("${simplejavamail.dkim.signing.signing-domain:#{null}}") final String dkimSigningDomainSpringBoot,
				@Nullable @Value("${simplejavamail.dkim.signing.use_length_param:#{null}}") final String dkimSigningUseLengthParam,
				@Nullable @Value("${simplejavamail.dkim.signing.excluded_headers_from_default_signing_list:#{null}}") final String dkimSigningExcludedHeadersFromDefaultSigningList,
				@Nullable @Value("${simplejavamail.dkim.signing.excluded-headers-from-default-signing-list:#{null}}") final String dkimSigningExcludedHeadersFromDefaultSigningListSpringBoot,
				@Nullable @Value("${simplejavamail.dkim.signing.header_canonicalization:#{null}}") final String dkimSigningHeaderCanonicalization,
				@Nullable @Value("${simplejavamail.dkim.signing.body_canonicalization:#{null}}") final String dkimSigningBodyCanonicalization,
				@Nullable @Value("${simplejavamail.dkim.signing.algorithm:#{null}}") final String dkimSigningAlgorithm,
				@Nullable @Value("${simplejavamail.embeddedimages.dynamicresolution.enable.dir:#{null}}") final String embeddedimagesDynamicresolutionEnableDir,
				@Nullable @Value("${simplejavamail.embeddedimages.dynamicresolution.enable.url:#{null}}") final String embeddedimagesDynamicresolutionEnableUrl,
				@Nullable @Value("${simplejavamail.embeddedimages.dynamicresolution.enable.classpath:#{null}}") final String embeddedimagesDynamicresolutionEnableClassPath,
				@Nullable @Value("${simplejavamail.embeddedimages.dynamicresolution.base.dir:#{null}}") final String embeddedimagesDynamicresolutionBaseDir,
				@Nullable @Value("${simplejavamail.embeddedimages.dynamicresolution.base.url:#{null}}") final String embeddedimagesDynamicresolutionBaseUrl,
				@Nullable @Value("${simplejavamail.embeddedimages.dynamicresolution.base.classpath:#{null}}") final String embeddedimagesDynamicresolutionBaseClassPath,
				@Nullable @Value("${simplejavamail.embeddedimages.dynamicresolution.outside.base.dir:#{null}}") final String embeddedimagesDynamicresolutionOutsideBaseDir,
				@Nullable @Value("${simplejavamail.embeddedimages.dynamicresolution.outside.base.classpath:#{null}}") final String embeddedimagesDynamicresolutionOutsideBaseClassPath,
				@Nullable @Value("${simplejavamail.embeddedimages.dynamicresolution.outside.base.url:#{null}}") final String embeddedimagesDynamicresolutionOutsideBaseUrl,
				@Nullable @Value("${simplejavamail.embeddedimages.dynamicresolution.mustbesuccesful:#{null}}") final String embeddedimagesDynamicresolutionMustBeSuccesful) {
		final Properties emailProperties = new Properties();
		setNullableProperty(emailProperties, Property.JAVAXMAIL_DEBUG.key(), javaxmailDebug);
		setNullableProperty(emailProperties, Property.TRANSPORT_STRATEGY.key(), transportstrategy);
		setNullableProperty(emailProperties, Property.SMTP_HOST.key(), smtpHost);
		setNullableProperty(emailProperties, Property.SMTP_PORT.key(), smtpPort);
		setNullableProperty(emailProperties, Property.SMTP_USERNAME.key(), smtpUsername);
		setNullableProperty(emailProperties, Property.SMTP_PASSWORD.key(), smtpPassword);
		setNullableProperty(emailProperties, Property.DISABLE_ALL_CLIENTVALIDATION.key(), disableAllClientValidation);
		if (customSSLFactoryClass != null) {
			setNullableProperty(emailProperties, Property.CUSTOM_SSLFACTORY_CLASS.key(), customSSLFactoryClass);
		} else {
			setNullableProperty(emailProperties, Property.CUSTOM_SSLFACTORY_CLASS.key(), customSSLFactoryClassSpringBoot); // can still be null
		}
		setNullableProperty(emailProperties, Property.PROXY_HOST.key(), proxyHost);
		setNullableProperty(emailProperties, Property.PROXY_PORT.key(), proxyPort);
		setNullableProperty(emailProperties, Property.PROXY_USERNAME.key(), proxyUsername);
		setNullableProperty(emailProperties, Property.PROXY_PASSWORD.key(), proxyPassword);
		setNullableProperty(emailProperties, Property.PROXY_SOCKS5BRIDGE_PORT.key(), proxySocks5bridgePort);
		setNullableProperty(emailProperties, Property.DEFAULT_CONTENT_TRANSFER_ENCODING.key(), defaultContentTransferEncoding);
		setNullableProperty(emailProperties, Property.DEFAULT_SUBJECT.key(), defaultSubject);
		setNullableProperty(emailProperties, Property.DEFAULT_FROM_NAME.key(), defaultFromName);
		setNullableProperty(emailProperties, Property.DEFAULT_FROM_ADDRESS.key(), defaultFromAddress);
		setNullableProperty(emailProperties, Property.DEFAULT_REPLYTO_NAME.key(), defaultReplytoName);
		setNullableProperty(emailProperties, Property.DEFAULT_REPLYTO_ADDRESS.key(), defaultReplytoAddress);
		setNullableProperty(emailProperties, Property.DEFAULT_BOUNCETO_NAME.key(), defaultBouncetoName);
		setNullableProperty(emailProperties, Property.DEFAULT_BOUNCETO_ADDRESS.key(), defaultBouncetoAddress);
		setNullableProperty(emailProperties, Property.DEFAULT_TO_NAME.key(), defaultToName);
		setNullableProperty(emailProperties, Property.DEFAULT_TO_ADDRESS.key(), defaultToAddress);
		setNullableProperty(emailProperties, Property.DEFAULT_CC_NAME.key(), defaultCcName);
		setNullableProperty(emailProperties, Property.DEFAULT_CC_ADDRESS.key(), defaultCcAddress);
		setNullableProperty(emailProperties, Property.DEFAULT_BCC_NAME.key(), defaultBccName);
		setNullableProperty(emailProperties, Property.DEFAULT_BCC_ADDRESS.key(), defaultBccAddress);
		setNullableProperty(emailProperties, Property.DEFAULT_POOL_SIZE.key(), defaultPoolsize);
		if (defaultPoolKeepAlivetime != null) {
			setNullableProperty(emailProperties, Property.DEFAULT_POOL_KEEP_ALIVE_TIME.key(), defaultPoolKeepAlivetime);
		} else {
			setNullableProperty(emailProperties, Property.DEFAULT_POOL_KEEP_ALIVE_TIME.key(), defaultPoolKeepAlivetimeSpringBoot);
		}
		setNullableProperty(emailProperties, Property.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY.key(), defaultConnectionPoolCluterKey);
		setNullableProperty(emailProperties, Property.DEFAULT_CONNECTIONPOOL_CORE_SIZE.key(), defaultConnectionPoolCoreSize);
		setNullableProperty(emailProperties, Property.DEFAULT_CONNECTIONPOOL_MAX_SIZE.key(), defaultConnectionPoolMaxSize);
		setNullableProperty(emailProperties, Property.DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS.key(), defaultConnectionPoolClaimTimeoutMillis);
		setNullableProperty(emailProperties, Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS.key(), defaultConnectionPoolExpireAfterMillis);
		setNullableProperty(emailProperties, Property.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY.key(), defaultConnectionPoolLoadBalancingStrategy);
		setNullableProperty(emailProperties, Property.DEFAULT_SESSION_TIMEOUT_MILLIS.key(), defaultSessionTimeoutMillis);
		setNullableProperty(emailProperties, Property.DEFAULT_TRUST_ALL_HOSTS.key(), defaultTrustAllHosts);
		setNullableProperty(emailProperties, Property.DEFAULT_TRUSTED_HOSTS.key(), defaultTrustedHosts);
		setNullableProperty(emailProperties, Property.DEFAULT_VERIFY_SERVER_IDENTITY.key(), defaultVerifyServerIdentity);
		setNullableProperty(emailProperties, Property.TRANSPORT_MODE_LOGGING_ONLY.key(), transportModeLoggingOnly);
		setNullableProperty(emailProperties, Property.OPPORTUNISTIC_TLS.key(), opportunisticTls);
		setNullableProperty(emailProperties, Property.SMIME_SIGNING_KEYSTORE.key(), smimeSigningKeyStore);
		if (smimeSigningKeyStorePassword != null) {
			setNullableProperty(emailProperties, Property.SMIME_SIGNING_KEYSTORE_PASSWORD.key(), smimeSigningKeyStorePassword);
		} else {
			setNullableProperty(emailProperties, Property.SMIME_SIGNING_KEYSTORE_PASSWORD.key(), smimeSigningKeyStorePasswordSpringBoot);
		}
		if (smimeSigningKeyAlias != null) {
			setNullableProperty(emailProperties, Property.SMIME_SIGNING_KEY_ALIAS.key(), smimeSigningKeyAlias);
		} else {
			setNullableProperty(emailProperties, Property.SMIME_SIGNING_KEY_ALIAS.key(), smimeSigningKeyAliasSpringBoot);
		}
		if (smimeSigningKeyPassword != null) {
			setNullableProperty(emailProperties, Property.SMIME_SIGNING_KEY_PASSWORD.key(), smimeSigningKeyPassword);
		} else {
			setNullableProperty(emailProperties, Property.SMIME_SIGNING_KEY_PASSWORD.key(), smimeSigningKeyPasswordSpringBoot);
		}
		setNullableProperty(emailProperties, Property.SMIME_SIGNING_ALGORITHM.key(), smimeSigningAlgorithm);
		setNullableProperty(emailProperties, Property.SMIME_ENCRYPTION_KEY_ENCAPSULATION_ALGORITHM.key(), smimeEncryptionKeyEncapsulationAlgorithm);
		setNullableProperty(emailProperties, Property.SMIME_ENCRYPTION_CIPHER.key(), smimeEncryptionCipher);
		setNullableProperty(emailProperties, Property.SMIME_ENCRYPTION_CERTIFICATE.key(), smimeEncryptionCertificate);
		if (dkimSigningPrivateKeyFileOrData != null) {
			setNullableProperty(emailProperties, Property.DKIM_PRIVATE_KEY_FILE_OR_DATA.key(), dkimSigningPrivateKeyFileOrData);
		} else {
			setNullableProperty(emailProperties, Property.DKIM_PRIVATE_KEY_FILE_OR_DATA.key(), dkimSigningPrivateKeyFileOrDataSpringBoot);
		}
		setNullableProperty(emailProperties, Property.DKIM_SELECTOR.key(), dkimSigningSelector);
		if (dkimSigningDomain != null) {
			setNullableProperty(emailProperties, Property.DKIM_SIGNING_DOMAIN.key(), dkimSigningDomain);
		} else {
			setNullableProperty(emailProperties, Property.DKIM_SIGNING_DOMAIN.key(), dkimSigningDomainSpringBoot);
		}
		setNullableProperty(emailProperties, Property.DKIM_SIGNING_USE_LENGTH_PARAM.key(), dkimSigningUseLengthParam);
		if (dkimSigningExcludedHeadersFromDefaultSigningList != null) {
			setNullableProperty(emailProperties, Property.DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST.key(), dkimSigningExcludedHeadersFromDefaultSigningList);
		} else {
			setNullableProperty(emailProperties, Property.DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST.key(), dkimSigningExcludedHeadersFromDefaultSigningListSpringBoot);
		}
		setNullableProperty(emailProperties, Property.DKIM_SIGNING_HEADER_CANONICALIZATION.key(), dkimSigningHeaderCanonicalization);
		setNullableProperty(emailProperties, Property.DKIM_SIGNING_BODY_CANONICALIZATION.key(), dkimSigningBodyCanonicalization);
		setNullableProperty(emailProperties, Property.DKIM_SIGNING_ALGORITHM.key(), dkimSigningAlgorithm);
		setNullableProperty(emailProperties, Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_DIR.key(), embeddedimagesDynamicresolutionEnableDir);
		setNullableProperty(emailProperties, Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_CLASSPATH.key(), embeddedimagesDynamicresolutionEnableClassPath);
		setNullableProperty(emailProperties, Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_URL.key(), embeddedimagesDynamicresolutionEnableUrl);
		setNullableProperty(emailProperties, Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_DIR.key(), embeddedimagesDynamicresolutionBaseDir);
		setNullableProperty(emailProperties, Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_CLASSPATH.key(), embeddedimagesDynamicresolutionBaseClassPath);
		setNullableProperty(emailProperties, Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_URL.key(), embeddedimagesDynamicresolutionBaseUrl);
		setNullableProperty(emailProperties, Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_DIR.key(), embeddedimagesDynamicresolutionOutsideBaseDir);
		setNullableProperty(emailProperties, Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_CLASSPATH.key(), embeddedimagesDynamicresolutionOutsideBaseClassPath);
		setNullableProperty(emailProperties, Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_URL.key(), embeddedimagesDynamicresolutionOutsideBaseUrl);
		setNullableProperty(emailProperties, Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_MUSTBESUCCESFUL.key(), embeddedimagesDynamicresolutionMustBeSuccesful);

		for (PropertySource<?> source : environment.getPropertySources()) {
			if (source instanceof EnumerablePropertySource) {
				for (String name : ((EnumerablePropertySource<?>) source).getPropertyNames()) {
					if (name.startsWith("simplejavamail.extraproperties.")) {
						emailProperties.setProperty(name, environment.getProperty(name));
					}
				}
			}
		}

		ConfigLoader.loadProperties(emailProperties, true);

		// This will configure itself with the global config and is ready to use.
		// Ofcourse this is optional simply as a convenience default.
		return new MailerRegularBuilderImpl();
	}

	private static void setNullableProperty(final Properties emailProperties, final String key, @Nullable final String value) {
		if (value != null) {
			emailProperties.setProperty(key, value);
		}
	}
}
