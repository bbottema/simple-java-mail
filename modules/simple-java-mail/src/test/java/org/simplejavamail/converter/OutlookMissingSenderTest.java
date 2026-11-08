package org.simplejavamail.converter;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.EmailSourceProperty;
import org.simplejavamail.api.mailer.Mailer;
import org.simplejavamail.mailer.MailCompletenessException;
import org.simplejavamail.internal.moduleloader.ModuleLoader;
import org.simplejavamail.internal.modules.OpenPgpModule;
import org.simplejavamail.internal.modules.SMIMEModule;
import testutil.ConfigLoaderTestHelper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OutlookMissingSenderTest {
    private static final SimpleJavaMail MAIL = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());
    private static final String[] MISSING_SENDER = {"unsent draft.msg", "chinese message.msg", "SMIME (signed and clear text).msg"};
    @TempDir Path directory;

    @ParameterizedTest
    @MethodSource("missingSendersAndRoutes")
    void missingSenderRemainsAbsentThroughImportCopyAndSerialization(final String fixture, final Route route) throws Exception {
        final byte[] bytes = fixture(fixture);
        final Path path = directory.resolve("source.msg");
        Files.write(path, bytes);
        final Email imported = route.read(path, bytes);
        if (!fixture.startsWith("chinese")) {
            assertThat(imported.getSubject()).isEqualTo(fixture.startsWith("SMIME")
                    ? "Invio messaggio SMIME (signed and clear text)" : "MSG Test File");
            assertThat(imported.getPlainText()).isNotEmpty();
            assertThat(imported.getRecipients()).isNotEmpty();
        }

        for (final Email email : Arrays.asList(imported, MAIL.emailBuilder().copying(imported).buildEmail(), serializedCopy(imported))) {
            assertThat(email.getFromRecipient()).as("%s via %s", fixture, route).isNull();
            assertThat(email.getSubject()).isEqualTo(imported.getSubject());
            assertThat(email.getPlainText()).isEqualTo(imported.getPlainText());
            assertThat(email.getHTMLText()).isEqualTo(imported.getHTMLText());
            assertThat(email.getRecipients()).isEqualTo(imported.getRecipients());
            assertThat(email.getAttachments()).hasSize(imported.getAttachments().size());
            assertThat(email.getSourceData().getOrigin().getReference()).isEqualTo(imported.getSourceData().getOrigin().getReference());
            if (fixture.startsWith("SMIME")) {
                assertThat(email.getSourceData().getProperties()).anySatisfy(property -> {
                    assertThat(property.getName()).isEqualTo("0x0042");
                    assertThat(property.getValue()).isEqualTo("Alessandro Gasparini");
                });
                assertThat(email.getOriginalSmimeDetails().getSmimeSignedBy()).isEqualTo("shatzing5@outlook.com");
            }
            if (fixture.startsWith("chinese")) {
                assertThat(email.getSourceData().getProperties()).extracting(EmailSourceProperty::getValue).contains("haozl@Ctrip.com");
            }
        }
    }

    static Stream<Arguments> missingSendersAndRoutes() {
        return Arrays.stream(MISSING_SENDER).flatMap(fixture -> Arrays.stream(Route.values()).map(route -> Arguments.of(fixture, route)));
    }

    @ParameterizedTest
    @MethodSource("routes")
    void realSenderRetainsItsAddressAndName(final Route route) throws Exception {
        final byte[] bytes = fixture("simple email with TO and CC.msg");
        final Path path = directory.resolve("source.msg");
        Files.write(path, bytes);
        final Email email = route.read(path, bytes);
        assertThat(email.getFromRecipient().getAddress()).isEqualTo("elias.laugher@gmail.com");
        assertThat(email.getFromRecipient().getName()).isEqualTo("Elias Laugher");
        assertThat(email.getPlainText()).contains("Just a test to get an email with one cc recipient.");
    }

    static Stream<Route> routes() { return Arrays.stream(Route.values()); }

    @Test
    @SuppressWarnings("unchecked")
    void missingSenderDoesNotRequireOptionalProtectionModules() throws Exception {
        final Field disabledModules = ModuleLoader.class.getDeclaredField("FORCED_DISABLED_MODULES");
        disabledModules.setAccessible(true);
        final Set<Class<?>> disabled = (Set<Class<?>>) disabledModules.get(null);
        final boolean smimeAdded = disabled.add(SMIMEModule.class);
        final boolean openPgpAdded = disabled.add(OpenPgpModule.class);
        try {
            final Email imported = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(fixture("unsent draft.msg"))).toEmail();
            assertThat(imported.getFromRecipient()).isNull();
            assertThat(imported.getSubject()).isEqualTo("MSG Test File");
            assertThat(imported.getPlainText()).isNotEmpty();
        } finally {
            if (smimeAdded) disabled.remove(SMIMEModule.class);
            if (openPgpAdded) disabled.remove(OpenPgpModule.class);
        }
    }

    @Test
    void outgoingPreparationRequiresAnExplicitOrGovernedSender() throws Exception {
        final Email imported = MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(fixture("unsent draft.msg"))).toEmail();
        final Email supplied = MAIL.emailBuilder().copying(imported).from("Archive", "archive@example.org").buildEmail();
        final Email defaults = MAIL.emailBuilder().startingBlank().from("Default", "default@example.org").buildEmail();
        final Email overrides = MAIL.emailBuilder().startingBlank().from("Override", "override@example.org").buildEmail();
        final EmailPopulatingBuilder draft = MAIL.emailBuilder().copying(imported);
        final Email allowingDefaults = draft.withTemplateOptions(draft.getTemplateOptions().toBuilder().resetIgnoreDefaults().build()).buildEmail();
        try (Mailer ordinary = MAIL.mailerBuilder().withTransportModeLoggingOnly(true).buildMailer();
                Mailer defaulted = MAIL.mailerBuilder().withTransportModeLoggingOnly(true).withEmailDefaults(defaults).buildMailer();
                Mailer overridden = MAIL.mailerBuilder().withTransportModeLoggingOnly(true).withEmailOverrides(overrides).buildMailer()) {
            assertThatThrownBy(() -> ordinary.rehearse(imported, false)).isInstanceOf(MailCompletenessException.class).hasMessageContaining("missing sender");
            assertThat(ordinary.rehearse(supplied, false).getEffectiveEmail().getFromRecipient().getAddress()).isEqualTo("archive@example.org");
            assertThatThrownBy(() -> defaulted.rehearse(imported, false)).isInstanceOf(MailCompletenessException.class).hasMessageContaining("missing sender");
            assertThat(defaulted.rehearse(allowingDefaults, false).getEffectiveEmail().getFromRecipient().getAddress()).isEqualTo("default@example.org");
            assertThat(overridden.rehearse(imported, false).getEffectiveEmail().getFromRecipient().getAddress()).isEqualTo("override@example.org");
        }
        assertThat(imported.getFromRecipient()).isNull();
        assertThat(imported.isIgnoreDefaults()).isTrue();
    }

    private static byte[] fixture(final String name) throws Exception {
        try (InputStream input = OutlookMissingSenderTest.class.getResourceAsStream("/test-messages/" + name)) {
            return input.readAllBytes();
        }
    }

    private static Email serializedCopy(final Email email) throws Exception {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) { output.writeObject(email); }
        try (ObjectInputStream input = new ObjectInputStream(new ByteArrayInputStream(bytes.toByteArray()))) { return (Email) input.readObject(); }
    }

    enum Route {
        FLUENT_PATH { @Override Email read(Path path, byte[] bytes) { return MAIL.converter().fromOutlookMsg(path).toEmail(); } },
        FLUENT_STREAM { @Override Email read(Path path, byte[] bytes) { return MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(bytes)).toEmail(); } },
        FLUENT_METADATA_PATH { @Override Email read(Path path, byte[] bytes) { return MAIL.converter().fromOutlookMsg(path).toEmailBuilderWithOutlookData().buildEmail(); } },
        FLUENT_METADATA_STREAM { @Override Email read(Path path, byte[] bytes) { return MAIL.converter().fromOutlookMsg(new ByteArrayInputStream(bytes)).toEmailBuilderWithOutlookData().buildEmail(); } },
        STATIC_FILE { @Override Email read(Path path, byte[] bytes) { return EmailConverter.outlookMsgToEmail(path.toFile()); } },
        STATIC_STREAM { @Override Email read(Path path, byte[] bytes) { return EmailConverter.outlookMsgToEmail(new ByteArrayInputStream(bytes)); } },
        STATIC_METADATA_FILE { @Override Email read(Path path, byte[] bytes) { return EmailConverter.outlookMsgToEmailBuilderWithOutlookData(path.toFile()).buildEmail(); } },
        STATIC_METADATA_STREAM { @Override Email read(Path path, byte[] bytes) { return EmailConverter.outlookMsgToEmailBuilderWithOutlookData(new ByteArrayInputStream(bytes)).buildEmail(); } };
        abstract Email read(Path path, byte[] bytes);
    }
}
