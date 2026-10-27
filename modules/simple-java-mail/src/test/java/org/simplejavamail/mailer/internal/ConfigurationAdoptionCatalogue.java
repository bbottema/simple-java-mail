package org.simplejavamail.mailer.internal;

import org.simplejavamail.config.ConfigLoader.Property;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.Arrays;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Frozen route inventory, including parameter and return types, with explicit owner classifications.
 * Behavioral field adapters assert these categories while exercising their public selection/merge contracts.
 * Inventory completeness alone does not establish behavioral correctness.
 * Value builders remain core data contracts; their fields are resolved by the named Email/Mailer/batch consumer, not by source loading.
 * Named definitions and wildcard containers are loader-owned inputs; unknown provider keys retain provider-owned meaning.
 * Provider-negotiated capabilities and execution algorithms consume selected setup and are not additional setup configuration layers.
 * Callback-only registrations have one application source; declaration capture must preserve their identity without invoking them.
 */
final class ConfigurationAdoptionCatalogue {
    enum Owner {
        MAILER_DEBUG, MAILER_CONNECTION, MAILER_CONTENT_COMPATIBILITY, MAILER_TELEMETRY,
        MAILER_LIMITS, MAILER_RETRY, MAILER_FAILOVER, CONFIGURATION_CONTAINER,
        MAILER_VALIDATION, EMAIL_TEMPLATE, EMAIL_ENCODING, EMAIL_DELIVERY,
        MAILER_ASYNC, MAILER_DEADLINE, MAILER_POOL, MAILER_LOGGING,
        EMAIL_ATOMIC_POLICY, EMAIL_IMAGE, RAW_PROVIDER_INPUT, APPLICATION_CALLBACK_INPUT,
        CONVERTER_SETUP, CORE_VALUE_FALLBACK, EMAIL_CALENDAR, EMAIL_HEADER_COLLECTION,
        EMAIL_INPUT_POLICY, EMAIL_RECIPIENT_COLLECTION, EMAIL_RESOURCE_COLLECTION, EMAIL_TEMPLATE_OPTIONS,
        KEY_MATERIAL_INPUT, MAILER_TEMPLATE_SELECTION, RECIPIENT_GROUP_POLICY, STANDALONE_BATCH
    }
    static final Map<Property, Owner> PROPERTIES = properties();
    static final Map<String, Owner> API_OWNERS = apiOwners();
    static final Map<String, Set<String>> API_SIGNATURES = signaturesByContract();

    private ConfigurationAdoptionCatalogue() { }

    private static Map<Property, Owner> properties() {
        final Map<Property, Owner> owners = new EnumMap<>(Property.class);
        classify(owners, Owner.MAILER_DEBUG,
                Property.JAVAXMAIL_DEBUG, Property.JAVAXMAIL_DEBUG_OUTPUT);
        classify(owners, Owner.MAILER_CONNECTION,
                Property.TRANSPORT_STRATEGY, Property.CONNECTION_CONFLICT_POLICY, Property.SMTP_HOST, Property.SMTP_PORT,
                Property.SMTP_USERNAME, Property.SMTP_PASSWORD, Property.SMTP_CLIENT_HOSTNAME, Property.SMTP_LOCAL_ADDRESS,
                Property.SMTP_LOCAL_PORT, Property.CUSTOM_SSLFACTORY_CLASS, Property.PROXY_HOST, Property.PROXY_PORT,
                Property.PROXY_USERNAME, Property.PROXY_PASSWORD, Property.PROXY_SOCKS5BRIDGE_PORT, Property.DEFAULT_SESSION_TIMEOUT_MILLIS,
                Property.DEFAULT_CONNECT_TIMEOUT_MILLIS, Property.DEFAULT_READ_TIMEOUT_MILLIS, Property.DEFAULT_WRITE_TIMEOUT_MILLIS, Property.DEFAULT_TRUST_ALL_HOSTS,
                Property.DEFAULT_TRUSTED_HOSTS, Property.DEFAULT_VERIFY_SERVER_IDENTITY, Property.OPPORTUNISTIC_TLS);
        classify(owners, Owner.MAILER_CONTENT_COMPATIBILITY,
                Property.SMTP_LEGACY_CONTENT_SUPPORT);
        classify(owners, Owner.MAILER_TELEMETRY,
                Property.TELEMETRY_ENABLED, Property.TELEMETRY_SMTP_ENDPOINT_ATTRIBUTES);
        classify(owners, Owner.MAILER_LIMITS,
                Property.SMTP_RATE_LIMIT_GROUP, Property.SMTP_MESSAGE_RATE_LIMIT, Property.SMTP_MESSAGE_RATE_PERIOD, Property.SMTP_RECIPIENT_RATE_LIMIT,
                Property.SMTP_RECIPIENT_RATE_PERIOD, Property.SMTP_RATE_LIMIT_ALLOW_BURSTS);
        classify(owners, Owner.MAILER_RETRY,
                Property.SMTP_RETRY_ATTEMPTS, Property.SMTP_RETRY_DELAY, Property.SMTP_RETRY_BACKOFF_MULTIPLIER, Property.SMTP_RETRY_MAXIMUM_DELAY,
                Property.SMTP_RETRY_JITTER, Property.DEFAULT_MAIL_SEND_RETRY_WAITING_CAPACITY, Property.DEFAULT_MAIL_SEND_RETRY_MAXIMUM_MESSAGE_SIZE, Property.DEFAULT_MAIL_SEND_RETRY_MEMORY_LIMIT);
        classify(owners, Owner.MAILER_FAILOVER,
                Property.FAILOVER_MAILER, Property.FAILOVER_PRIMARY_CONNECTION_ATTEMPTS, Property.FAILOVER_PRIMARY_RETRY_DELAY, Property.FAILOVER_PRIMARY_RECHECK_INTERVAL,
                Property.FAILOVER_PRIMARY_BACKOFF_MULTIPLIER, Property.FAILOVER_PRIMARY_MAXIMUM_RECHECK_INTERVAL, Property.FAILOVER_ON_AUTHENTICATION_FAILURE, Property.FAILOVER_ON_CERTIFICATE_VERIFICATION_FAILURE);
        classify(owners, Owner.CONFIGURATION_CONTAINER,
                Property.NAMED_MAILERS);
        classify(owners, Owner.MAILER_VALIDATION,
                Property.DISABLE_ALL_CLIENTVALIDATION);
        classify(owners, Owner.EMAIL_TEMPLATE,
                Property.DEFAULT_SUBJECT, Property.DEFAULT_FROM_NAME, Property.DEFAULT_FROM_ADDRESS, Property.DEFAULT_REPLYTO_NAME,
                Property.DEFAULT_REPLYTO_ADDRESS, Property.DEFAULT_BOUNCETO_NAME, Property.DEFAULT_BOUNCETO_ADDRESS, Property.DEFAULT_TO_NAME,
                Property.DEFAULT_TO_ADDRESS, Property.DEFAULT_CC_NAME, Property.DEFAULT_CC_ADDRESS, Property.DEFAULT_BCC_NAME,
                Property.DEFAULT_BCC_ADDRESS);
        classify(owners, Owner.EMAIL_ENCODING,
                Property.DEFAULT_CONTENT_TRANSFER_ENCODING, Property.DEFAULT_PLAIN_TEXT_CONTENT_TRANSFER_ENCODING, Property.DEFAULT_HTML_TEXT_CONTENT_TRANSFER_ENCODING, Property.DEFAULT_CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING);
        classify(owners, Owner.EMAIL_DELIVERY,
                Property.DEFAULT_DELIVERY_STATUS_NOTIFICATION_NOTIFY, Property.DEFAULT_DELIVERY_STATUS_NOTIFICATION_RETURN_OPTION, Property.DEFAULT_REQUIRE_TLS, Property.DEFAULT_SEND_TO_ACCEPTED_RECIPIENTS);
        classify(owners, Owner.MAILER_ASYNC,
                Property.DEFAULT_POOL_SIZE, Property.DEFAULT_ASYNC_QUEUE_CAPACITY, Property.DEFAULT_ASYNC_QUEUE_OVERFLOW_POLICY, Property.DEFAULT_ASYNC_QUEUE_WAIT_TIMEOUT_MILLIS,
                Property.DEFAULT_POOL_KEEP_ALIVE_TIME);
        classify(owners, Owner.MAILER_DEADLINE,
                Property.DEFAULT_MAIL_SEND_TIMEOUT);
        classify(owners, Owner.MAILER_POOL,
                Property.DEFAULT_CONNECTIONPOOL_CLUSTER_KEY, Property.DEFAULT_CONNECTIONPOOL_CORE_SIZE, Property.DEFAULT_CONNECTIONPOOL_MAX_SIZE, Property.DEFAULT_CONNECTIONPOOL_CLAIMTIMEOUT_MILLIS,
                Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTER_MILLIS, Property.DEFAULT_CONNECTIONPOOL_EXPIREAFTERCREATION_MILLIS, Property.DEFAULT_CONNECTIONPOOL_LOADBALANCING_STRATEGY, Property.DEFAULT_CONNECTIONPOOL_CLUSTER_CONFIGS);
        classify(owners, Owner.MAILER_LOGGING,
                Property.TRANSPORT_MODE_LOGGING_ONLY);
        classify(owners, Owner.EMAIL_ATOMIC_POLICY,
                Property.SMIME_SIGNING_KEYSTORE, Property.SMIME_SIGNING_KEYSTORE_PASSWORD, Property.SMIME_SIGNING_KEY_ALIAS, Property.SMIME_SIGNING_KEY_PASSWORD,
                Property.SMIME_SIGNING_ALGORITHM, Property.SMIME_ENCRYPTION_KEY_ENCAPSULATION_ALGORITHM, Property.SMIME_ENCRYPTION_CIPHER, Property.DKIM_PRIVATE_KEY_FILE_OR_DATA,
                Property.DKIM_SELECTOR, Property.DKIM_SIGNING_DOMAIN, Property.DKIM_SIGNING_USE_LENGTH_PARAM, Property.DKIM_EXCLUDED_HEADERS_FROM_DEFAULT_SIGNING_LIST,
                Property.DKIM_SIGNING_HEADER_CANONICALIZATION, Property.DKIM_SIGNING_BODY_CANONICALIZATION, Property.DKIM_SIGNING_ALGORITHM, Property.SMIME_ENCRYPTION_CERTIFICATE);
        classify(owners, Owner.EMAIL_IMAGE,
                Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_DIR, Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_CLASSPATH, Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_ENABLE_URL, Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_DIR,
                Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_CLASSPATH, Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_BASE_URL, Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_DIR, Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_URL,
                Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_OUTSIDE_BASE_CLASSPATH, Property.EMBEDDEDIMAGES_DYNAMICRESOLUTION_MUSTBESUCCESFUL);
        classify(owners, Owner.RAW_PROVIDER_INPUT,
                Property.EXTRA_PROPERTIES);
        return Collections.unmodifiableMap(owners);
    }

    private static void classify(final Map<Property, Owner> owners, final Owner owner, final Property... properties) {
        for (Property property : properties) {
            if (owners.put(property, owner) != null) { throw new AssertionError("Duplicate owner for " + property); }
        }
    }

    private static Map<String, Owner> apiOwners() {
        final Map<String, Owner> owners = new LinkedHashMap<>();
        final InputStream resource = ConfigurationAdoptionCatalogue.class.getResourceAsStream("/configuration-api-routes.tsv");
        if (resource == null) { throw new AssertionError("Missing frozen configuration API route inventory"); }
        try (BufferedReader input = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            for (String line; (line = input.readLine()) != null;) {
                if (line.isBlank() || line.startsWith("#")) { continue; }
                final String[] columns = line.split("\t", -1);
                if (columns.length != 2 || owners.put(columns[0], Owner.valueOf(columns[1])) != null) {
                    throw new AssertionError("Invalid or duplicate configuration route: " + line);
                }
            }
        } catch (IOException failure) {
            throw new AssertionError("Cannot read the configuration route inventory", failure);
        }
        return Collections.unmodifiableMap(owners);
    }

    private static Map<String, Set<String>> signaturesByContract() {
        final Map<String, Set<String>> contracts = new LinkedHashMap<>();
        for (String signature : API_OWNERS.keySet()) {
            final String contract = signature.substring(0, signature.indexOf('#'));
            contracts.computeIfAbsent(contract, ignored -> new LinkedHashSet<>()).add(signature);
        }
        contracts.replaceAll((contract, signatures) -> Collections.unmodifiableSet(signatures));
        return Collections.unmodifiableMap(contracts);
    }

    static boolean isChoiceMethod(final Method method) {
        final Class<?> contract = method.getDeclaringClass();
        final boolean valueLookup = contract == org.simplejavamail.config.SimpleJavaMailConfig.class
                && method.getName().equals("valueOrProperty");
        return Modifier.isPublic(method.getModifiers()) && !Modifier.isStatic(method.getModifiers())
                && !method.isBridge() && !method.isSynthetic()
                && (valueLookup || contract.isAssignableFrom(method.getReturnType()));
    }

    static String signature(final Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName() + "("
                + Arrays.stream(method.getParameterTypes()).map(Class::getTypeName).collect(Collectors.joining(","))
                + "):" + method.getReturnType().getTypeName();
    }

    /** Called by executable field contracts, so a category reassignment must still satisfy that field's tested rule. */
    static void assertApiCategory(final Owner expected, final Class<?> contract, final String... methodNames) {
        for (String name : methodNames) {
            final Set<String> routes = Arrays.stream(contract.getMethods()).filter(ConfigurationAdoptionCatalogue::isChoiceMethod)
                    .filter(method -> method.getName().equals(name)).map(ConfigurationAdoptionCatalogue::signature)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
            assertThat(routes).as("Public routes for %s.%s", contract.getName(), name).isNotEmpty();
            for (String route : routes) {
                assertThat(API_OWNERS).as("Behavioral contract category for %s", route).containsEntry(route, expected);
            }
        }
    }

    static void assertValueBuilderCategory(final Owner expected, final Class<?> builder) {
        final String[] methods = Arrays.stream(builder.getDeclaredMethods()).filter(ConfigurationAdoptionCatalogue::isChoiceMethod)
                .map(Method::getName).distinct().toArray(String[]::new);
        assertThat(methods).as("Choice methods on %s", builder.getName()).isNotEmpty();
        assertApiCategory(expected, builder, methods);
    }

    static void assertPropertyCategory(final Owner expected, final String key) {
        final Property property = Arrays.stream(Property.values()).filter(candidate -> candidate.key().equals(key)).findFirst()
                .orElseThrow(() -> new AssertionError("No source property for " + key));
        assertThat(PROPERTIES).as("Behavioral contract category for %s", key).containsEntry(property, expected);
    }
}
