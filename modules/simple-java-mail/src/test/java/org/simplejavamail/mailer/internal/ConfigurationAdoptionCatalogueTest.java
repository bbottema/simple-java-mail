package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.config.ConfigLoader.Property;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationAdoptionCatalogueTest {
    @Test
    void overloadsAreSeparateClassifiedRoutes() {
        assertThat(ConfigurationAdoptionCatalogue.API_OWNERS)
                .containsEntry("org.simplejavamail.api.email.EmailPopulatingBuilder#withHeader(java.lang.String,java.lang.Object):org.simplejavamail.api.email.EmailPopulatingBuilder",
                        ConfigurationAdoptionCatalogue.Owner.EMAIL_HEADER_COLLECTION)
                .containsEntry("org.simplejavamail.api.email.EmailPopulatingBuilder#withHeader(java.lang.String,java.lang.Object,boolean):org.simplejavamail.api.email.EmailPopulatingBuilder",
                        ConfigurationAdoptionCatalogue.Owner.EMAIL_HEADER_COLLECTION);
    }

    @Test
    void everyConfiguredPropertyHasAnExplicitResolutionOwner() {
        assertThat(ConfigurationAdoptionCatalogue.PROPERTIES.keySet()).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(Property.class));
    }

    @Test
    void everyPublicChoiceMethodIsAccountedForIncludingGroupMembersAndCompanions() throws Exception {
        for (Map.Entry<String, Set<String>> entry : ConfigurationAdoptionCatalogue.API_SIGNATURES.entrySet()) {
            final Class<?> contract = Class.forName(entry.getKey());
            assertThat(choiceSignatures(contract)).as(entry.getKey()).containsExactlyInAnyOrderElementsOf(entry.getValue());
        }
    }

    @Test
    void everyDeclaredCategoryHasARecordedConsumer() {
        final Set<ConfigurationAdoptionCatalogue.Owner> assigned = EnumSet.noneOf(ConfigurationAdoptionCatalogue.Owner.class);
        assigned.addAll(ConfigurationAdoptionCatalogue.PROPERTIES.values());
        assigned.addAll(ConfigurationAdoptionCatalogue.API_OWNERS.values());
        assertThat(assigned).containsExactlyInAnyOrderElementsOf(EnumSet.allOf(ConfigurationAdoptionCatalogue.Owner.class));
    }

    @Test
    void anAddedPublicConfigurationValueBuilderNeedsAnAdoptionDecision() throws Exception {
        final Path module = Files.isDirectory(Path.of("../core-module/src/main/java")) ? Path.of("../core-module/src/main/java")
                : Path.of("modules/core-module/src/main/java");
        final Pattern builder = Pattern.compile("public\\s+static\\s+(?:final\\s+)?class\\s+(\\w*Builder)\\b");
        for (String scope : Arrays.asList("email", "mailer")) {
            final Path sources = module.resolve("org/simplejavamail/api/" + scope + "/config");
            try (Stream<Path> files = Files.list(sources)) {
                for (Path source : (Iterable<Path>) files.filter(path -> path.toString().endsWith(".java"))::iterator) {
                    final Matcher declaration = builder.matcher(Files.readString(source));
                    while (declaration.find()) {
                        final String owner = "org.simplejavamail.api." + scope + ".config."
                                + source.getFileName().toString().replace(".java", "") + "$" + declaration.group(1);
                        if (!choiceSignatures(Class.forName(owner)).isEmpty()) {
                            assertThat(ConfigurationAdoptionCatalogue.API_SIGNATURES).as("New value builder %s needs classification", owner).containsKey(owner);
                        }
                    }
                }
            }
        }
    }

    @Test
    void everyTemplateSelectorHasAnExplicitBehaviorCategory() {
        final Set<String> classified = names("SUBJECT BODY_TEXT BODY_HTML FROM_RECIPIENT BOUNCETO_RECIPIENT SENT_DATE ID MAIL_TO_FORWARD "
                + "HEADERS ATTACHMENTS EMBEDDED_IMAGES REPLYTO_RECIPIENT ALL_RECIPIENTS TO_RECIPIENTS CC_RECIPIENTS BCC_RECIPIENTS OVERRIDE_RECEIVERS "
                + "CALENDAR_METHOD CALENDAR_TEXT RETURN_RECEIPT_TO DISPOSITION_NOTIFICATION_TO USE_RETURN_RECEIPT_TO USE_DISPOSITION_NOTIFICATION_TO "
                + "DELIVERY_STATUS_NOTIFICATION TLS_REQUIRED_FOR_ONWARD_DELIVERY SENDING_TO_ACCEPTED_RECIPIENTS "
                + "CONTENT_TRANSFER_ENCODING PLAIN_TEXT_CONTENT_TRANSFER_ENCODING HTML_TEXT_CONTENT_TRANSFER_ENCODING CALENDAR_TEXT_CONTENT_TRANSFER_ENCODING "
                + "SMIME_SIGNING_CONFIG SMIME_ENCRYPTION_CONFIG OPENPGP_SIGNING_CONFIG OPENPGP_ENCRYPTION_CONFIG DKIM_SIGNING_CONFIG");
        final Set<String> actual = new LinkedHashSet<>();
        for (EmailProperty property : EmailProperty.values()) { actual.add(property.name()); }
        assertThat(actual).containsExactlyInAnyOrderElementsOf(classified);
    }

    private static Set<String> choiceSignatures(final Class<?> contract) {
        final Set<String> actual = new LinkedHashSet<>();
        for (Method method : contract.getDeclaredMethods()) {
            if (ConfigurationAdoptionCatalogue.isChoiceMethod(method)) {
                actual.add(ConfigurationAdoptionCatalogue.signature(method));
            }
        }
        return actual;
    }

    private static Set<String> names(final String names) {
        return new LinkedHashSet<>(Arrays.asList(names.split(" ")));
    }
}
