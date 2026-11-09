package org.simplejavamail.mailer.internal;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.converter.ConfiguredEmailConverter;
import org.simplejavamail.api.converter.config.ImportLimitsConfig;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.EmailProblem;
import org.simplejavamail.config.ConfigKey;
import org.simplejavamail.config.ConfigKeys;
import org.simplejavamail.config.ConfigLoader;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.mailer.internal.ConfigurationAdoptionCatalogue.Owner.CONVERTER_IMPORT_LIMITS;
import static testutil.configcontracts.ConfigurationContractCoverage.invoke;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

/** Exact public setup routes are paired with independently expected import behavior. */
class ImportLimitsConfigurationContractTest {

    // Three represented MIME nodes, at depths one and two, with a raw size determined by this literal source.
    private static final byte[] SOURCE = ("From: sender@example.org\r\nTo: reader@example.org\r\nSubject: Import policy\r\n"
            + "MIME-Version: 1.0\r\nContent-Type: multipart/mixed; boundary=policy\r\n\r\n"
            + "--policy\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\nReadable body\r\n"
            + "--policy\r\nContent-Type: application/octet-stream\r\nContent-Disposition: attachment; filename=file.bin\r\n"
            + "Content-Transfer-Encoding: base64\r\n\r\nAQID\r\n--policy--\r\n").getBytes(UTF_8);

    enum Member {
        BYTES("MaximumInputBytes", "maximumInputBytes", long.class, ConfigKeys.IMPORT_MAXIMUM_INPUT_BYTES, SOURCE.length),
        PARTS("MaximumPartCount", "maximumPartCount", int.class, ConfigKeys.IMPORT_MAXIMUM_PART_COUNT, 3),
        DEPTH("MaximumNestingDepth", "maximumNestingDepth", int.class, ConfigKeys.IMPORT_MAXIMUM_NESTING_DEPTH, 2);

        final String suffix;
        final String field;
        final Class<?> parameter;
        final ConfigKey<?> key;
        final int sourceCost;

        Member(final String suffix, final String field, final Class<?> parameter, final ConfigKey<?> key, final int sourceCost) {
            this.suffix = suffix;
            this.field = field;
            this.parameter = parameter;
            this.key = key;
            this.sourceCost = sourceCost;
        }

        Object number(final int value) {
            if (parameter == long.class) return Long.valueOf(value);
            return Integer.valueOf(value);
        }

        ImportLimitsConfig choice(final Integer supplied) throws Exception {
            final ImportLimitsConfig.Builder builder = ImportLimitsConfig.builder();
            if (supplied != null) builder.getClass().getMethod("with" + suffix, parameter).invoke(builder, number(supplied));
            return builder.build();
        }

        Object supplied(final Object value) throws Exception {
            return value.getClass().getMethod("get" + suffix).invoke(value);
        }

        boolean exceeds(final int selected) { return selected > 0 && sourceCost > selected; }

        static Member forMethod(final Method method) {
            return Stream.of(values()).filter(member -> method.getName().endsWith(member.suffix)).findFirst().orElseThrow(AssertionError::new);
        }
    }

    static Stream<Method> valueRoutes() {
        return EmailContractTestSupport.routes(java.util.Set.of(CONVERTER_IMPORT_LIMITS), ImportLimitsConfig.Builder.class);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("valueRoutes")
    void everyValueBuilderRouteFeedsTheActualImport(final Method method) throws Exception {
        final Member member = Member.forMethod(method);
        final ImportLimitsConfig.Builder builder = member.choice(1).toBuilder();
        final Object[] arguments = method.getParameterCount() == 0 ? new Object[0] : new Object[] {member.number(2)};
        assertThat(invoke(builder, method, arguments)).isSameAs(builder);
        final Integer expected = method.getName().startsWith("useConfigured") ? null : method.getName().startsWith("reset") ? 0 : 2;
        assertThat(member.supplied(builder)).isEqualTo(expected == null ? null : member.number(expected));
        final ImportLimitsConfig supplied = builder.build();
        assertThat(member.supplied(supplied)).isEqualTo(expected == null ? null : member.number(expected));
        final Email imported = factory(Map.of()).converter().fromEml(SOURCE).withImportLimitsConfig(supplied).toEmail();
        assertThat(limited(imported)).isEqualTo(member.exceeds(expected == null ? 0 : expected));
        ConfigurationAdoptionCatalogue.assertValueBuilderCategory(CONVERTER_IMPORT_LIMITS, ImportLimitsConfig.Builder.class);
        record("field", "converter.import." + member.field);
        record("rule", "import.limits.choice-resolution");
        record("case", "import.limits.selection");
    }

    enum Source { EML, MIME, OUTLOOK }

    @ParameterizedTest
    @EnumSource(Source.class)
    void everyReceivingStageAppliesReplacementAndRestorationBeforeReading(final Source source) throws Exception {
        final Map<String, Object> captured = source == Source.MIME
                ? Map.of(ConfigKeys.IMPORT_MAXIMUM_PART_COUNT.key(), 1)
                : Map.of(ConfigKeys.IMPORT_MAXIMUM_INPUT_BYTES.key(), 8L);
        final SimpleJavaMail factory = factory(captured);
        final ImportLimitsConfig unlimited = ImportLimitsConfig.builder().withMaximumInputBytes(0).withMaximumPartCount(0)
                .withMaximumNestingDepth(0).build();

        final Stage full = stage(factory, source);
        assertThat(full.supplied()).isEqualTo(ImportLimitsConfig.builder().build());
        assertThat(invoke(full.value, full.withMethod(), unlimited)).isSameAs(full.value);
        assertThat(full.supplied()).isEqualTo(unlimited);
        full.assertUnread();
        assertThat(limited(full.toEmail())).isFalse();

        final Stage restored = stage(factory, source);
        invoke(restored.value, restored.withMethod(), unlimited);
        assertThatThrownBy(() -> invoke(restored.value, restored.withMethod(), new Object[] {null})).isInstanceOf(NullPointerException.class);
        assertThat(restored.supplied()).isEqualTo(unlimited);
        final ImportLimitsConfig replacement = ImportLimitsConfig.builder().withMaximumNestingDepth(0).build();
        invoke(restored.value, restored.withMethod(), replacement);
        assertThat(restored.supplied().getMaximumInputBytes()).isNull();
        assertThat(restored.supplied().getMaximumPartCount()).isNull();
        assertThat(invoke(restored.value, restored.restoreMethod())).isSameAs(restored.value);
        assertThat(restored.supplied()).isEqualTo(ImportLimitsConfig.builder().build());
        restored.assertUnread();
        assertThat(limited(restored.toEmail())).isTrue();
        restored.assertBorrowed();

        ConfigurationAdoptionCatalogue.assertApiCategory(CONVERTER_IMPORT_LIMITS, restored.contract(),
                "withImportLimitsConfig", "useConfiguredImportLimitsConfig");
        record("adapter", "converter.import-limits");
        record("rule", "import.limits.group-replacement");
        record("rule", "import.limits.passive-inspection");
        record("case", "import.limits.stage-restoration");
    }

    @ParameterizedTest
    @EnumSource(Member.class)
    void eachMemberKeepsSourceChoiceAndLockSemantics(final Member member) throws Exception {
        for (final int property : new int[] {0, 1}) {
            for (final Integer javaChoice : new Integer[] {null, 0, 1, 2}) {
                for (final boolean locked : new boolean[] {false, true}) {
                    final String key = locked ? member.key.key().replace("simplejavamail.", "simplejavamail.locked.") : member.key.key();
                    final SimpleJavaMail factory = factory(Map.of(key, member.number(property)));
                    final BorrowedInput input = new BorrowedInput(SOURCE);
                    final ConfiguredEmailConverter.EmlConversion conversion = factory.converter().fromEml(input)
                            .withImportLimitsConfig(member.choice(javaChoice));
                    final int selected = javaChoice == null ? property : javaChoice;
                    if (locked && selected != property) {
                        assertThatThrownBy(conversion::toEmail).isInstanceOf(IllegalArgumentException.class)
                                .hasMessageContaining("simplejavamail.locked.", "import policy");
                        assertThat(input.available()).isEqualTo(SOURCE.length);
                        conversion.useConfiguredImportLimitsConfig();
                        assertThat(limited(conversion.toEmail())).isEqualTo(member.exceeds(property));
                    } else {
                        assertThat(limited(conversion.toEmail())).isEqualTo(member.exceeds(selected));
                    }
                    assertThat(input.closed).isFalse();
                }
            }
        }
        ConfigurationAdoptionCatalogue.assertPropertyCategory(CONVERTER_IMPORT_LIMITS, member.key.key());
        record("property", member.key.key());
        record("field", "converter.import." + member.field);
        record("rule", "import.limits.choice-resolution");
        record("rule", "import.limits.lock-authority");
        record("case", "import.limits.selection");
    }

    @Test
    void copyingTheGroupPreservesChoicesAndReturnsAnIndependentDraft() throws Exception {
        final ImportLimitsConfig original = ImportLimitsConfig.builder().withMaximumPartCount(0).withMaximumNestingDepth(1).build();
        final ImportLimitsConfig.Builder copy = (ImportLimitsConfig.Builder) invoke(original, ImportLimitsConfig.class.getMethod("toBuilder"));
        assertThat(copy.getMaximumInputBytes()).isNull();
        assertThat(copy.getMaximumPartCount()).isZero();
        assertThat(copy.getMaximumNestingDepth()).isEqualTo(1);
        final ImportLimitsConfig changed = copy.resetMaximumNestingDepth().build();
        assertThat(original.getMaximumNestingDepth()).isEqualTo(1);
        assertThat(limited(factory(Map.of()).converter().fromEml(SOURCE).withImportLimitsConfig(original).toEmail())).isTrue();
        assertThat(limited(factory(Map.of()).converter().fromEml(SOURCE).withImportLimitsConfig(changed).toEmail())).isFalse();
        record("rule", "import.limits.copy-state");
        record("case", "import.limits.copy");
    }

    @Test
    void factorySnapshotsRemainIndependentAfterTheirSourcesChange() {
        final Map<String, Object> source = new LinkedHashMap<>();
        source.put(ConfigKeys.IMPORT_MAXIMUM_INPUT_BYTES.key(), 8L);
        final SimpleJavaMail first = factory(source);
        source.put(ConfigKeys.IMPORT_MAXIMUM_INPUT_BYTES.key(), 0L);
        final SimpleJavaMail second = factory(source);
        assertThat(limited(first.converter().fromEml(SOURCE).toEmail())).isTrue();
        assertThat(limited(second.converter().fromEml(SOURCE).toEmail())).isFalse();
        assertThat(limited(first.converter().fromEml(SOURCE).toEmail())).isTrue();
        record("case", "import.limits.factory-isolation");
    }

    private static boolean limited(final Email email) {
        return email.getProblems().stream().anyMatch(problem -> problem.getCode() == EmailProblem.Code.IMPORT_LIMIT_EXCEEDED);
    }

    private static SimpleJavaMail factory(final Map<String, ?> choices) {
        return SimpleJavaMail.withConfig(ConfigLoader.builder().withMap("import policy", choices).load());
    }

    private static Stage stage(final SimpleJavaMail factory, final Source source) throws Exception {
        if (source == Source.MIME) {
            final MimeMessage message = new MimeMessage(Session.getInstance(new Properties()), new ByteArrayInputStream(SOURCE));
            return new Stage(source, factory.converter().fromMimeMessage(message), null);
        }
        final byte[] bytes = source == Source.OUTLOOK
                ? EmailContractTestSupport.resourceBytes("/test-messages/simple email with TO and CC.msg") : SOURCE;
        final BorrowedInput input = new BorrowedInput(bytes);
        return new Stage(source, source == Source.OUTLOOK ? factory.converter().fromOutlookMsg(input) : factory.converter().fromEml(input), input);
    }

    private static final class Stage {
        final Source source;
        final Object value;
        final BorrowedInput input;
        final int remaining;

        Stage(final Source source, final Object value, final BorrowedInput input) {
            this.source = source;
            this.value = value;
            this.input = input;
            this.remaining = input == null ? 0 : input.available();
        }

        Class<?> contract() { return source == Source.OUTLOOK ? ConfiguredEmailConverter.OutlookConversion.class : ConfiguredEmailConverter.IncomingConversion.class; }
        Method withMethod() throws Exception { return contract().getMethod("withImportLimitsConfig", ImportLimitsConfig.class); }
        Method restoreMethod() throws Exception { return contract().getMethod("useConfiguredImportLimitsConfig"); }
        ImportLimitsConfig supplied() throws Exception { return (ImportLimitsConfig) contract().getMethod("getImportLimitsConfig").invoke(value); }
        Email toEmail() throws Exception { return (Email) contract().getMethod("toEmail").invoke(value); }
        void assertUnread() { if (input != null) assertThat(input.available()).isEqualTo(remaining); }
        void assertBorrowed() { if (input != null) assertThat(input.closed).isFalse(); }
    }

    private static final class BorrowedInput extends ByteArrayInputStream {
        boolean closed;
        BorrowedInput(final byte[] bytes) { super(bytes); }
        @Override public void close() { closed = true; }
    }
}
