package org.simplejavamail.config;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.simplejavamail.api.email.ContentTransferEncoding;
import org.simplejavamail.api.email.config.DeliveryStatusNotification;
import org.simplejavamail.api.email.config.DkimConfig;
import org.simplejavamail.api.mailer.config.AsyncQueueOverflowPolicy;
import org.simplejavamail.api.mailer.config.ConnectionPoolClusterConfig;
import org.simplejavamail.api.mailer.config.LoadBalancingStrategy;
import org.simplejavamail.api.mailer.config.SessionDebugOutput;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader.Property;

/**
 * Schema-bound keys for ordinary {@link SimpleJavaMailConfig#get(ConfigKey)} reads.
 * Each declaration selects its parser and Java projection together; the same binding parses loaded values.
 * <p>
 * For example, {@code Integer port = config.get(ConfigKeys.SMTP_PORT)} and
 * {@code Duration timeout = config.get(ConfigKeys.SESSION_TIMEOUT)}. Missing values, including aggregate maps,
 * are null because a snapshot contains explicit input only. Present snapshot maps are detached and unmodifiable.
 * Runtime defaults remain with the owning builder. Values can contain credentials; use diagnostics when logging.
 * <p>
 * {@link ConfigKey#key()} supplies the unchanged textual name. Dynamic inputs and
 * {@link SimpleJavaMailConfig#asMap()} retain their parser representation and original units. Numeric-millisecond
 * inputs are projected to Duration for Java reads; ISO-duration settings retain their existing precision and limits.
 */
public final class ConfigKeys {
    private ConfigKeys() { }

    /** Configured input for {@code simplejavamail.javaxmail.debug}. */
    public static final ConfigKey<Boolean> JAVAXMAIL_DEBUG = PropertySchema.booleanKey(Property.JAVAXMAIL_DEBUG);

    /** Configured input for {@code simplejavamail.javaxmail.debug.out}. */
    public static final ConfigKey<SessionDebugOutput> JAVAXMAIL_DEBUG_OUTPUT = PropertySchema.sessionDebugOutputKey(Property.JAVAXMAIL_DEBUG_OUTPUT);

    /** Configured input for {@code simplejavamail.transportstrategy}. */
    public static final ConfigKey<TransportStrategy> TRANSPORT_STRATEGY = PropertySchema.transportStrategyKey(Property.TRANSPORT_STRATEGY);

    /** Configured input for {@code simplejavamail.smtp.host}. */
    public static final ConfigKey<String> SMTP_HOST = PropertySchema.stringKey(Property.SMTP_HOST);

    /** Configured input for {@code simplejavamail.smtp.port}. */
    public static final ConfigKey<Integer> SMTP_PORT = PropertySchema.integerKey(Property.SMTP_PORT);

    /** Configured input for {@code simplejavamail.smtp.username}. */
    public static final ConfigKey<String> SMTP_USERNAME = PropertySchema.stringKey(Property.SMTP_USERNAME);

    /** Configured input for {@code simplejavamail.smtp.password}. */
    public static final ConfigKey<String> SMTP_PASSWORD = PropertySchema.stringKey(Property.SMTP_PASSWORD);

    /** Configured input for {@code simplejavamail.smtp.clienthostname}. */
    public static final ConfigKey<String> SMTP_CLIENT_HOSTNAME = PropertySchema.stringKey(Property.SMTP_CLIENT_HOSTNAME);

    /** Configured input for {@code simplejavamail.smtp.legacycontentsupport}. */
    public static final ConfigKey<Boolean> SMTP_LEGACY_CONTENT_SUPPORT = PropertySchema.booleanKey(Property.SMTP_LEGACY_CONTENT_SUPPORT);

    /** Configured input for {@code simplejavamail.telemetry.enabled}. */
    public static final ConfigKey<Boolean> TELEMETRY_ENABLED = PropertySchema.booleanKey(Property.TELEMETRY_ENABLED);

    /** Configured input for {@code simplejavamail.telemetry.smtp.endpointattributes}. */
    public static final ConfigKey<Boolean> TELEMETRY_SMTP_ENDPOINT_ATTRIBUTES = PropertySchema.booleanKey(Property.TELEMETRY_SMTP_ENDPOINT_ATTRIBUTES);

    /** Configured input for {@code simplejavamail.smtp.ratelimit.group}. */
    public static final ConfigKey<String> SMTP_RATE_LIMIT_GROUP = PropertySchema.stringKey(Property.SMTP_RATE_LIMIT_GROUP);

    /** Configured input for {@code simplejavamail.smtp.ratelimit.messages.limit}. */
    public static final ConfigKey<Integer> SMTP_MESSAGE_RATE_LIMIT = PropertySchema.positiveIntegerKey(Property.SMTP_MESSAGE_RATE_LIMIT);

    /** Configured input for {@code simplejavamail.smtp.ratelimit.messages.period}. ISO-duration input keeps its existing precision and feature-owner validation. */
    public static final ConfigKey<Duration> SMTP_MESSAGE_RATE_PERIOD = PropertySchema.durationKey(Property.SMTP_MESSAGE_RATE_PERIOD);

    /** Configured input for {@code simplejavamail.smtp.ratelimit.recipients.limit}. */
    public static final ConfigKey<Integer> SMTP_RECIPIENT_RATE_LIMIT = PropertySchema.positiveIntegerKey(Property.SMTP_RECIPIENT_RATE_LIMIT);

    /** Configured input for {@code simplejavamail.smtp.ratelimit.recipients.period}. ISO-duration input keeps its existing precision and feature-owner validation. */
    public static final ConfigKey<Duration> SMTP_RECIPIENT_RATE_PERIOD = PropertySchema.durationKey(Property.SMTP_RECIPIENT_RATE_PERIOD);

    /** Configured input for {@code simplejavamail.smtp.ratelimit.allowbursts}. */
    public static final ConfigKey<Boolean> SMTP_RATE_LIMIT_ALLOW_BURSTS = PropertySchema.booleanKey(Property.SMTP_RATE_LIMIT_ALLOW_BURSTS);

    /** Configured input for {@code simplejavamail.smtp.retry.attempts}. */
    public static final ConfigKey<Integer> SMTP_RETRY_ATTEMPTS = PropertySchema.positiveIntegerKey(Property.SMTP_RETRY_ATTEMPTS);

    /** Configured input for {@code simplejavamail.smtp.retry.delay}. ISO-duration input keeps its existing precision and feature-owner validation. */
    public static final ConfigKey<Duration> SMTP_RETRY_DELAY = PropertySchema.durationKey(Property.SMTP_RETRY_DELAY);

    /** Configured input for {@code simplejavamail.smtp.retry.backoffmultiplier}. */
    public static final ConfigKey<Integer> SMTP_RETRY_BACKOFF_MULTIPLIER = PropertySchema.positiveIntegerKey(Property.SMTP_RETRY_BACKOFF_MULTIPLIER);

    /** Configured input for {@code simplejavamail.smtp.retry.maximumdelay}. ISO-duration input keeps its existing precision and feature-owner validation. */
    public static final ConfigKey<Duration> SMTP_RETRY_MAXIMUM_DELAY = PropertySchema.durationKey(Property.SMTP_RETRY_MAXIMUM_DELAY);

    /** Configured input for {@code simplejavamail.smtp.retry.jitter}. */
    public static final ConfigKey<Double> SMTP_RETRY_JITTER = PropertySchema.doubleKey(Property.SMTP_RETRY_JITTER);

    /** Configured input for {@code simplejavamail.defaults.mailsend.retry.waitingcapacity}. */
    public static final ConfigKey<Integer> DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY = PropertySchema.positiveIntegerKey(Property.DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY);

    /** Configured input for {@code simplejavamail.defaults.mailsend.retry.maximummessagesize}. */
    public static final ConfigKey<Long> DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE = PropertySchema.longKey(Property.DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE);

    /** Configured input for {@code simplejavamail.defaults.mailsend.retry.memorylimit}. */
    public static final ConfigKey<Long> DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT = PropertySchema.longKey(Property.DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT);

    /** Configured input for {@code simplejavamail.failover.mailer}. */
    public static final ConfigKey<String> FAILOVER_MAILER = PropertySchema.stringKey(Property.FAILOVER_MAILER);

    /** Configured input for {@code simplejavamail.failover.primary.connectionattempts}. */
    public static final ConfigKey<Integer> FAILOVER_PRIMARY_CONNECTION_ATTEMPTS = PropertySchema.positiveIntegerKey(Property.FAILOVER_PRIMARY_CONNECTION_ATTEMPTS);

    /** Configured input for {@code simplejavamail.failover.primary.retrydelay}. ISO-duration input keeps its existing precision and feature-owner validation. */
    public static final ConfigKey<Duration> FAILOVER_PRIMARY_RETRY_DELAY = PropertySchema.durationKey(Property.FAILOVER_PRIMARY_RETRY_DELAY);

    /** Configured input for {@code simplejavamail.failover.primary.recheckinterval}. ISO-duration input keeps its existing precision and feature-owner validation. */
    public static final ConfigKey<Duration> FAILOVER_PRIMARY_RECHECK_INTERVAL = PropertySchema.durationKey(Property.FAILOVER_PRIMARY_RECHECK_INTERVAL);

    /** Configured input for {@code simplejavamail.failover.primary.backoffmultiplier}. */
    public static final ConfigKey<Integer> FAILOVER_PRIMARY_BACKOFF_MULTIPLIER = PropertySchema.positiveIntegerKey(Property.FAILOVER_PRIMARY_BACKOFF_MULTIPLIER);

    /** Configured input for {@code simplejavamail.failover.primary.maximumrecheckinterval}. ISO-duration input keeps its existing precision and feature-owner validation. */
    public static final ConfigKey<Duration> FAILOVER_PRIMARY_MAXIMUM_RECHECK_INTERVAL = PropertySchema.durationKey(Property.FAILOVER_PRIMARY_MAXIMUM_RECHECK_INTERVAL);

    /** Configured input for {@code simplejavamail.failover.onauthenticationfailure}. */
    public static final ConfigKey<Boolean> FAILOVER_ON_AUTHENTICATION_FAILURE = PropertySchema.booleanKey(Property.FAILOVER_ON_AUTHENTICATION_FAILURE);

    /** Configured input for {@code simplejavamail.failover.oncertificateverificationfailure}. */
    public static final ConfigKey<Boolean> FAILOVER_ON_CERTIFICATE_VERIFICATION_FAILURE = PropertySchema.booleanKey(Property.FAILOVER_ON_CERTIFICATE_VERIFICATION_FAILURE);

    /** Configured input for {@code simplejavamail.mailers.*}. Null when unconfigured; configured maps are detached and unmodifiable. */
    public static final ConfigKey<Map<String, SimpleJavaMailConfig>> NAMED_MAILERS = PropertySchema.namedMailersKey(Property.NAMED_MAILERS);

    /** Configured input for {@code simplejavamail.smtp.localaddress}. */
    public static final ConfigKey<String> SMTP_LOCAL_ADDRESS = PropertySchema.stringKey(Property.SMTP_LOCAL_ADDRESS);

    /** Configured input for {@code simplejavamail.smtp.localport}. */
    public static final ConfigKey<Integer> SMTP_LOCAL_PORT = PropertySchema.integerKey(Property.SMTP_LOCAL_PORT);

    /** Configured input for {@code simplejavamail.disable.all.clientvalidation}. */
    public static final ConfigKey<Boolean> DISABLE_ALL_CLIENTVALIDATION = PropertySchema.booleanKey(Property.DISABLE_ALL_CLIENTVALIDATION);

    /** Configured input for {@code simplejavamail.custom.sslfactory.class}. */
    public static final ConfigKey<String> CUSTOM_SSLFACTORY_CLASS = PropertySchema.stringKey(Property.CUSTOM_SSLFACTORY_CLASS);

    /** Configured input for {@code simplejavamail.proxy.host}. */
    public static final ConfigKey<String> PROXY_HOST = PropertySchema.stringKey(Property.PROXY_HOST);

    /** Configured input for {@code simplejavamail.proxy.port}. */
    public static final ConfigKey<Integer> PROXY_PORT = PropertySchema.integerKey(Property.PROXY_PORT);

    /** Configured input for {@code simplejavamail.proxy.username}. */
    public static final ConfigKey<String> PROXY_USERNAME = PropertySchema.stringKey(Property.PROXY_USERNAME);

    /** Configured input for {@code simplejavamail.proxy.password}. */
    public static final ConfigKey<String> PROXY_PASSWORD = PropertySchema.stringKey(Property.PROXY_PASSWORD);

    /** Configured input for {@code simplejavamail.proxy.socks5bridge.port}. */
    public static final ConfigKey<Integer> PROXY_SOCKS5BRIDGE_PORT = PropertySchema.integerKey(Property.PROXY_SOCKS5BRIDGE_PORT);

    /** Configured input for {@code simplejavamail.defaults.subject}. */
    public static final ConfigKey<String> DEFAULT_SUBJECT = PropertySchema.stringKey(Property.DEFAULT_SUBJECT);

    /** Configured input for {@code simplejavamail.defaults.content.transfer.encoding}. */
    public static final ConfigKey<ContentTransferEncoding> DEFAULT_CONTENT_TRANSFER_ENCODING = PropertySchema.contentTransferEncodingKey(Property.DEFAULT_CONTENT_TRANSFER_ENCODING);

    /** Configured input for {@code simplejavamail.defaults.body.text.content.transfer.encoding}. */
    public static final ConfigKey<ContentTransferEncoding> DEFAULT_PLAIN_TEXT_CONTENT_TRANSFER_ENCODING = PropertySchema.contentTransferEncodingKey(Property.DEFAULT_PLAIN_TEXT_CONTENT_TRANSFER_ENCODING);

    /** Configured input for {@code simplejavamail.defaults.body.html.content.transfer.encoding}. */
    public static final ConfigKey<ContentTransferEncoding> DEFAULT_HTML_TEXT_CONTENT_TRANSFER_ENCODING = PropertySchema.contentTransferEncodingKey(Property.DEFAULT_HTML_TEXT_CONTENT_TRANSFER_ENCODING);

    /** Configured input for {@code simplejavamail.defaults.body.calendar.content.transfer.encoding}. */
    public static final ConfigKey<ContentTransferEncoding> DEFAULT_CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING = PropertySchema.contentTransferEncodingKey(Property.DEFAULT_CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING);

    /** Configured input for {@code simplejavamail.defaults.from.name}. */
    public static final ConfigKey<String> DEFAULT_FROM_NAME = PropertySchema.stringKey(Property.DEFAULT_FROM_NAME);

    /** Configured input for {@code simplejavamail.defaults.from.address}. */
    public static final ConfigKey<String> DEFAULT_FROM_ADDRESS = PropertySchema.stringKey(Property.DEFAULT_FROM_ADDRESS);

    /** Configured input for {@code simplejavamail.defaults.replyto.name}. */
    public static final ConfigKey<String> DEFAULT_REPLYTO_NAME = PropertySchema.stringKey(Property.DEFAULT_REPLYTO_NAME);

    /** Configured input for {@code simplejavamail.defaults.replyto.address}. */
    public static final ConfigKey<String> DEFAULT_REPLYTO_ADDRESS = PropertySchema.stringKey(Property.DEFAULT_REPLYTO_ADDRESS);

    /** Configured input for {@code simplejavamail.defaults.bounceto.name}. */
    public static final ConfigKey<String> DEFAULT_BOUNCETO_NAME = PropertySchema.stringKey(Property.DEFAULT_BOUNCETO_NAME);

    /** Configured input for {@code simplejavamail.defaults.bounceto.address}. */
    public static final ConfigKey<String> DEFAULT_BOUNCETO_ADDRESS = PropertySchema.stringKey(Property.DEFAULT_BOUNCETO_ADDRESS);

    /** Configured input for {@code simplejavamail.defaults.delivery.status.notification.notify}. */
    public static final ConfigKey<String> DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY = PropertySchema.stringKey(Property.DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY);

    /** Configured input for {@code simplejavamail.defaults.delivery.status.notification.return.option}. */
    public static final ConfigKey<DeliveryStatusNotification.ReturnOption> DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION = PropertySchema.dsnReturnOptionKey(Property.DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION);

    /** Configured input for {@code simplejavamail.defaults.requiretls}. */
    public static final ConfigKey<Boolean> DEFAULT_REQUIRE_TLS = PropertySchema.booleanKey(Property.DEFAULT_REQUIRE_TLS);

    /** Configured input for {@code simplejavamail.defaults.sendtoacceptedrecipients}. */
    public static final ConfigKey<Boolean> DEFAULT_SEND_TO_ACCEPTED_RECIPIENTS = PropertySchema.booleanKey(Property.DEFAULT_SEND_TO_ACCEPTED_RECIPIENTS);

    /** Configured input for {@code simplejavamail.defaults.to.name}. */
    public static final ConfigKey<String> DEFAULT_TO_NAME = PropertySchema.stringKey(Property.DEFAULT_TO_NAME);

    /** Configured input for {@code simplejavamail.defaults.to.address}. */
    public static final ConfigKey<String> DEFAULT_TO_ADDRESS = PropertySchema.stringKey(Property.DEFAULT_TO_ADDRESS);

    /** Configured input for {@code simplejavamail.defaults.cc.name}. */
    public static final ConfigKey<String> DEFAULT_CC_NAME = PropertySchema.stringKey(Property.DEFAULT_CC_NAME);

    /** Configured input for {@code simplejavamail.defaults.cc.address}. */
    public static final ConfigKey<String> DEFAULT_CC_ADDRESS = PropertySchema.stringKey(Property.DEFAULT_CC_ADDRESS);

    /** Configured input for {@code simplejavamail.defaults.bcc.name}. */
    public static final ConfigKey<String> DEFAULT_BCC_NAME = PropertySchema.stringKey(Property.DEFAULT_BCC_NAME);

    /** Configured input for {@code simplejavamail.defaults.bcc.address}. */
    public static final ConfigKey<String> DEFAULT_BCC_ADDRESS = PropertySchema.stringKey(Property.DEFAULT_BCC_ADDRESS);

    /** Configured input for {@code simplejavamail.defaults.poolsize}. */
    public static final ConfigKey<Integer> DEFAULT_POOL_SIZE = PropertySchema.integerKey(Property.DEFAULT_POOL_SIZE);

    /** Configured input for {@code simplejavamail.defaults.async.queue.capacity}. */
    public static final ConfigKey<Integer> DEFAULT_ASYNC_QUEUE_CAPACITY = PropertySchema.integerKey(Property.DEFAULT_ASYNC_QUEUE_CAPACITY);

    /** Configured input for {@code simplejavamail.defaults.mailsend.timeout}. ISO-duration input keeps its existing precision and feature-owner validation. */
    public static final ConfigKey<Duration> DEFAULT_MAIL_SEND_TIMEOUT = PropertySchema.durationKey(Property.DEFAULT_MAIL_SEND_TIMEOUT);

    /** Configured input for {@code simplejavamail.defaults.async.queue.overflowpolicy}. */
    public static final ConfigKey<AsyncQueueOverflowPolicy> DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY = PropertySchema.asyncQueueOverflowPolicyKey(Property.DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY);

    /** Configured input for {@code simplejavamail.defaults.async.queue.waittimeoutmillis}. Java view of signed Integer milliseconds: whole-millisecond precision and integer bounds are unchanged. */
    public static final ConfigKey<Duration> ASYNC_QUEUE_WAIT_TIMEOUT = PropertySchema.millisecondKey(Property.DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT_MILLIS);

    /** Configured input for {@code simplejavamail.defaults.connectionpool.clusterkey.uuid}. */
    public static final ConfigKey<String> DEFAULT_CONNECTIONPOOL_CLUSTER_KEY = PropertySchema.stringKey(Property.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY);

    /** Configured input for {@code simplejavamail.defaults.connectionpool.coresize}. */
    public static final ConfigKey<Integer> DEFAULT_CONNECTIONPOOL_CORE_SIZE = PropertySchema.integerKey(Property.DEFAULT_CONNECTIONPOOL_CORE_SIZE);

    /** Configured input for {@code simplejavamail.defaults.connectionpool.maxsize}. */
    public static final ConfigKey<Integer> DEFAULT_CONNECTIONPOOL_MAX_SIZE = PropertySchema.integerKey(Property.DEFAULT_CONNECTIONPOOL_MAX_SIZE);

    /** Configured input for {@code simplejavamail.defaults.connectionpool.claimtimeout.millis}. Java view of signed Integer milliseconds: whole-millisecond precision and integer bounds are unchanged. */
    public static final ConfigKey<Duration> CONNECTION_POOL_CLAIM_TIMEOUT = PropertySchema.millisecondKey(Property.DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS);

    /** Configured input for {@code simplejavamail.defaults.connectionpool.expireafter.millis}. Java view of signed Integer milliseconds: whole-millisecond precision and integer bounds are unchanged. */
    public static final ConfigKey<Duration> CONNECTION_POOL_EXPIRE_AFTER = PropertySchema.millisecondKey(Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS);

    /** Configured input for {@code simplejavamail.defaults.connectionpool.expireaftercreation.millis}. Java view of signed Integer milliseconds: whole-millisecond precision and integer bounds are unchanged. */
    public static final ConfigKey<Duration> CONNECTION_POOL_EXPIRE_AFTER_CREATION = PropertySchema.millisecondKey(Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION_MILLIS);

    /** Configured input for {@code simplejavamail.defaults.connectionpool.loadbalancing.strategy}. */
    public static final ConfigKey<LoadBalancingStrategy> DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY = PropertySchema.loadBalancingStrategyKey(Property.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY);

    /** Configured input for {@code simplejavamail.defaults.connectionpool.clusters.*}. Null when unconfigured; configured maps are detached and unmodifiable. */
    public static final ConfigKey<Map<UUID, ConnectionPoolClusterConfig>> DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS = PropertySchema.poolClustersKey(Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS);

    /** Configured input for {@code simplejavamail.defaults.poolsize.keepalivetime}. Java view of signed Integer milliseconds: whole-millisecond precision and integer bounds are unchanged. */
    public static final ConfigKey<Duration> THREAD_POOL_KEEP_ALIVE_TIME = PropertySchema.millisecondKey(Property.DEFAULT_POOL_KEEP_ALIVE_TIME);

    /** Configured input for {@code simplejavamail.defaults.sessiontimeoutmillis}. Java view of signed Integer milliseconds: whole-millisecond precision and integer bounds are unchanged. */
    public static final ConfigKey<Duration> SESSION_TIMEOUT = PropertySchema.millisecondKey(Property.DEFAULT_SESSION_TIMEOUT_MILLIS);

    /** Configured input for {@code simplejavamail.defaults.trustallhosts}. */
    public static final ConfigKey<Boolean> DEFAULT_TRUST_ALL_HOSTS = PropertySchema.booleanKey(Property.DEFAULT_TRUST_ALL_HOSTS);

    /** Configured input for {@code simplejavamail.defaults.trustedhosts}. */
    public static final ConfigKey<String> DEFAULT_TRUSTED_HOSTS = PropertySchema.stringKey(Property.DEFAULT_TRUSTED_HOSTS);

    /** Configured input for {@code simplejavamail.defaults.verifyserveridentity}. */
    public static final ConfigKey<Boolean> DEFAULT_VERIFY_SERVER_IDENTITY = PropertySchema.booleanKey(Property.DEFAULT_VERIFY_SERVER_IDENTITY);

    /** Configured input for {@code simplejavamail.transport.mode.logging.only}. */
    public static final ConfigKey<Boolean> TRANSPORT_MODE_LOGGING_ONLY = PropertySchema.booleanKey(Property.TRANSPORT_MODE_LOGGING_ONLY);

    /** Configured input for {@code simplejavamail.opportunistic.tls}. */
    public static final ConfigKey<Boolean> OPPORTUNISTIC_TLS = PropertySchema.booleanKey(Property.OPPORTUNISTIC_TLS);

    /** Configured input for {@code simplejavamail.smime.signing.keystore}. */
    public static final ConfigKey<String> SMIME_SIGNING_KEYSTORE = PropertySchema.stringKey(Property.SMIME_SIGNING_KEYSTORE);

    /** Configured input for {@code simplejavamail.smime.signing.keystore_password}. */
    public static final ConfigKey<String> SMIME_SIGNING_KEYSTORE_PASSWORD = PropertySchema.stringKey(Property.SMIME_SIGNING_KEYSTORE_PASSWORD);

    /** Configured input for {@code simplejavamail.smime.signing.key_alias}. */
    public static final ConfigKey<String> SMIME_SIGNING_KEY_ALIAS = PropertySchema.stringKey(Property.SMIME_SIGNING_KEY_ALIAS);

    /** Configured input for {@code simplejavamail.smime.signing.key_password}. */
    public static final ConfigKey<String> SMIME_SIGNING_KEY_PASSWORD = PropertySchema.stringKey(Property.SMIME_SIGNING_KEY_PASSWORD);

    /** Configured input for {@code simplejavamail.smime.signing.algorithm}. */
    public static final ConfigKey<String> SMIME_SIGNING_ALGORITHM = PropertySchema.stringKey(Property.SMIME_SIGNING_ALGORITHM);

    /** Configured input for {@code simplejavamail.smime.encryption.key_encapsulation_algorithm}. */
    public static final ConfigKey<String> SMIME_ENCRYPTION_KEY_ENCAPSULATION_ALGORITHM = PropertySchema.stringKey(Property.SMIME_ENCRYPTION_KEY_ENCAPSULATION_ALGORITHM);

    /** Configured input for {@code simplejavamail.smime.encryption.cipher}. */
    public static final ConfigKey<String> SMIME_ENCRYPTION_CIPHER = PropertySchema.stringKey(Property.SMIME_ENCRYPTION_CIPHER);

    /** Configured input for {@code simplejavamail.dkim.signing.private_key_file_or_data}. */
    public static final ConfigKey<String> DKIM_PRIVATE_KEY_FILE_OR_DATA = PropertySchema.stringKey(Property.DKIM_PRIVATE_KEY_FILE_OR_DATA);

    /** Configured input for {@code simplejavamail.dkim.signing.selector}. */
    public static final ConfigKey<String> DKIM_SELECTOR = PropertySchema.stringKey(Property.DKIM_SELECTOR);

    /** Configured input for {@code simplejavamail.dkim.signing.signing_domain}. */
    public static final ConfigKey<String> DKIM_SIGNING_DOMAIN = PropertySchema.stringKey(Property.DKIM_SIGNING_DOMAIN);

    /** Configured input for {@code simplejavamail.dkim.signing.use_length_param}. */
    public static final ConfigKey<Boolean> DKIM_SIGNING_USE_LENGTH_PARAM = PropertySchema.booleanKey(Property.DKIM_SIGNING_USE_LENGTH_PARAM);

    /** Configured input for {@code simplejavamail.dkim.signing.excluded_headers_from_default_signing_list}. */
    public static final ConfigKey<String> DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST = PropertySchema.stringKey(Property.DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST);

    /** Configured input for {@code simplejavamail.dkim.signing.header_canonicalization}. */
    public static final ConfigKey<DkimConfig.Canonicalization> DKIM_SIGNING_HEADER_CANONICALIZATION = PropertySchema.dkimCanonicalizationKey(Property.DKIM_SIGNING_HEADER_CANONICALIZATION);

    /** Configured input for {@code simplejavamail.dkim.signing.body_canonicalization}. */
    public static final ConfigKey<DkimConfig.Canonicalization> DKIM_SIGNING_BODY_CANONICALIZATION = PropertySchema.dkimCanonicalizationKey(Property.DKIM_SIGNING_BODY_CANONICALIZATION);

    /** Configured input for {@code simplejavamail.dkim.signing.algorithm}. */
    public static final ConfigKey<String> DKIM_SIGNING_ALGORITHM = PropertySchema.stringKey(Property.DKIM_SIGNING_ALGORITHM);

    /** Configured input for {@code simplejavamail.smime.encryption.certificate}. */
    public static final ConfigKey<String> SMIME_ENCRYPTION_CERTIFICATE = PropertySchema.stringKey(Property.SMIME_ENCRYPTION_CERTIFICATE);

    /** Configured input for {@code simplejavamail.embeddedimages.dynamicresolution.enable.dir}. */
    public static final ConfigKey<Boolean> EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_DIR = PropertySchema.booleanKey(Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_DIR);

    /** Configured input for {@code simplejavamail.embeddedimages.dynamicresolution.enable.classpath}. */
    public static final ConfigKey<Boolean> EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_CLASSPATH = PropertySchema.booleanKey(Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_CLASSPATH);

    /** Configured input for {@code simplejavamail.embeddedimages.dynamicresolution.enable.url}. */
    public static final ConfigKey<Boolean> EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_URL = PropertySchema.booleanKey(Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_URL);

    /** Configured input for {@code simplejavamail.embeddedimages.dynamicresolution.base.dir}. */
    public static final ConfigKey<String> EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_DIR = PropertySchema.stringKey(Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_DIR);

    /** Configured input for {@code simplejavamail.embeddedimages.dynamicresolution.base.classpath}. */
    public static final ConfigKey<String> EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_CLASSPATH = PropertySchema.stringKey(Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_CLASSPATH);

    /** Configured input for {@code simplejavamail.embeddedimages.dynamicresolution.base.url}. */
    public static final ConfigKey<String> EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_URL = PropertySchema.stringKey(Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_URL);

    /** Configured input for {@code simplejavamail.embeddedimages.dynamicresolution.outside.base.dir}. */
    public static final ConfigKey<Boolean> EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_DIR = PropertySchema.booleanKey(Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_DIR);

    /** Configured input for {@code simplejavamail.embeddedimages.dynamicresolution.outside.base.url}. */
    public static final ConfigKey<Boolean> EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_URL = PropertySchema.booleanKey(Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_URL);

    /** Configured input for {@code simplejavamail.embeddedimages.dynamicresolution.outside.base.classpath}. */
    public static final ConfigKey<Boolean> EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_CLASSPATH = PropertySchema.booleanKey(Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_CLASSPATH);

    /** Configured input for {@code simplejavamail.embeddedimages.dynamicresolution.mustbesuccesful}. */
    public static final ConfigKey<Boolean> EMBEDDEDIMAGES_DYNAMICRESOLUTION_MUSTBESUCCESFUL = PropertySchema.booleanKey(Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_MUSTBESUCCESFUL);

    /** Configured input for {@code simplejavamail.extraproperties.*}. Null when unconfigured; configured maps are detached and unmodifiable. */
    public static final ConfigKey<Map<String, String>> EXTRA_PROPERTIES = PropertySchema.extraPropertiesKey(Property.EXTRA_PROPERTIES);

    static {
        PropertySchema.verifyKeyCatalogue();
    }

    // A parser lookup must complete the catalogue's class initialization before reading its registered bindings.
    static void initialize() { }
}
