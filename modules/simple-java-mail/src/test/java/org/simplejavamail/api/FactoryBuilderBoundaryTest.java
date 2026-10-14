package org.simplejavamail.api;

import jakarta.mail.Message;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.converter.ConfiguredEmailConverter;
import org.simplejavamail.api.email.IRecipientBuilder;
import org.simplejavamail.api.email.IRecipientsBuilder;
import org.simplejavamail.config.ConfigLoader;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FactoryBuilderBoundaryTest {
    @Test
    void packagedConsumersDoNotReachIntoInternalImplementationPackages() throws Exception {
        for (final String fixture : List.of("provider-neutral-classpath", "provider-neutral-jpms", "third-party-probe", "managed-angus-jpms")) {
            try (Stream<Path> files = Files.walk(Path.of("src/test", fixture))) {
                for (final Path source : files.filter(path -> path.toString().endsWith(".java")).collect(Collectors.toList())) {
                    assertThat(Files.readAllLines(source)).as(source.toString())
                            .filteredOn(line -> line.startsWith("import org.simplejavamail."))
                            .noneMatch(line -> line.contains(".internal."));
                }
            }
        }
    }

    @Test
    void removedImplementationEntryPointsAreAbsentFromTheRuntime() {
        for (final String type : new String[] {"org.simplejavamail.recipient.RecipientBuilder",
                "org.simplejavamail.recipient.RecipientsBuilder", "org.simplejavamail.converter.ConfiguredEmailConverter",
                "org.simplejavamail.converter.internal.EmailConversionSupport"}) {
            assertThatThrownBy(() -> Class.forName(type)).isInstanceOf(ClassNotFoundException.class);
        }
    }

    @Test
    void recipientEntryPointsReturnIndependentBuildersThroughCoreContracts() {
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        final IRecipientBuilder recipient = mail.recipientBuilder().withAddress("alice@example.org").withType(Message.RecipientType.TO);
        final IRecipientsBuilder recipients = mail.recipientsBuilder().withRecipient(recipient.build());

        assertThat(mail.recipientBuilder()).isNotSameAs(recipient);
        assertThat(mail.recipientBuilder().getAddress()).isNull();
        assertThat(mail.recipientsBuilder()).isNotSameAs(recipients);
        assertThat(mail.recipientsBuilder().buildRecipients()).isEmpty();
        assertThat(recipients.buildRecipients()).extracting("address").containsExactly("alice@example.org");
    }

    @Test
    void configuredConverterCanBeUsedThroughItsCoreContract() {
        final ConfiguredEmailConverter converter = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).converter();
        final byte[] eml = ("From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Example\r\n\r\nHello\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        assertThat(converter.emlToEmailBuilder(new ByteArrayInputStream(eml)).buildEmail().getSubject()).isEqualTo("Example");
    }
}
