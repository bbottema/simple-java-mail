package org.simplejavamail.mailer.internal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.converter.ConfiguredEmailConverter;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.config.ConfigLoader;

import java.lang.reflect.Method;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static testutil.configcontracts.ConfigurationContractCoverage.invoke;
import static testutil.configcontracts.ConfigurationContractCoverage.record;

class ConverterInputBuilderRouteContractTest {
    static Stream<Method> extraRoutes() throws Exception {
        return Stream.of(ConfiguredEmailConverter.IncomingConversion.class.getDeclaredMethod("toEmailBuilder"),
                ConfiguredEmailConverter.OutlookConversion.class.getDeclaredMethod("toEmailBuilder"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("extraRoutes")
    void importedBuilderRetainsSourceFactsAndBorrowsTheSuppliedStream(final Method method) throws Exception {
        final boolean outlook = method.getDeclaringClass() == ConfiguredEmailConverter.OutlookConversion.class;
        final byte[] bytes = outlook ? EmailContractTestSupport.resourceBytes("/test-messages/simple email with TO and CC.msg")
                : EmailBootstrapRouteContractTest.EML.clone();
        final byte[] before = bytes.clone();
        final EmailContractTestSupport.BorrowedInput input = new EmailContractTestSupport.BorrowedInput(bytes);
        final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
        final Object conversion = outlook ? mail.converter().fromOutlookMsg(input) : mail.converter().fromEml(input);
        assertThat(input.available()).isEqualTo(bytes.length);
        final EmailPopulatingBuilder builder = (EmailPopulatingBuilder) invoke(conversion, method);
        assertThat(builder.getSubject()).isEqualTo(outlook ? "Test E-Mail" : "Source");
        assertThat(builder.getPlainText()).isNotEmpty();
        assertThat(builder.getRecipients()).isNotEmpty();
        assertThat(input.closed).isFalse();
        assertThat(builder.withSubject("Edited").buildEmail().getSubject()).isEqualTo("Edited");
        input.reset();
        assertThat(input.readAllBytes()).containsExactly(before);
        assertThat(input.closed).isFalse();
        record("rule", "converter.input-builder");
        record("rule", "converter.input-ownership");
    }
}
