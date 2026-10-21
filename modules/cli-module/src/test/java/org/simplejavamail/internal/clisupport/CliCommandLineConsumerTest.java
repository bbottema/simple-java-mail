package org.simplejavamail.internal.clisupport;

import org.bbottema.javareflection.ClassUtils;
import org.bbottema.javareflection.model.MethodModifier;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionValue;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedCommand;
import org.simplejavamail.api.internal.clisupport.CliEmailRecipientBuilder;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.email.Email;
import org.simplejavamail.api.email.Recipient;
import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.config.ConfigLoader;
import picocli.CommandLine;

import java.lang.reflect.Method;
import java.util.ArrayList;

import static java.util.Arrays.asList;
import static java.util.EnumSet.allOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType.EMAIL;

public class CliCommandLineConsumerTest {
	@Test
	public void composedRecipientArrayArgumentsReachTheSharedListParserWithoutPreSplitting() throws Exception {
		final String source = "\"Support @ Acme, Europe\" <support@example.org>;Team: audit@example.org,backup@example.org;";
		final Method method = CliEmailRecipientBuilder.class.getMethod("withRecipients", String.class, boolean.class,
				jakarta.mail.Message.RecipientType.class, String[].class);
		final java.util.List<Object> arguments = CliCommandLineConsumer.convertProvidedOptionValues(
				new ArrayList<>(asList("false", source)), method);
		assertThat((String[]) arguments.get(3)).containsExactly(source);
		final EmailPopulatingBuilder builder = SimpleJavaMail.withConfig(ConfigLoader.builder().load()).emailBuilder().startingBlank();
		method.invoke(builder, arguments.toArray());
		assertThat(builder.buildEmail().getRecipients()).extracting(Recipient::getAddress)
				.containsExactly("support@example.org", "audit@example.org", "backup@example.org");
		assertThat(builder.buildEmail().getRecipients()).extracting(Recipient::getName)
				.containsExactly("Support @ Acme, Europe", null, null);
	}

	@Test
	public void unrelatedStringArrayOptionsKeepTheirExistingDelimiterConversion() throws Exception {
		final Method method = TestClass.class.getMethod("arrayValues", String[].class);
		assertThat((String[]) CliCommandLineConsumer.convertProvidedOptionValues(
				new ArrayList<>(asList("first@example.org;second@example.org,third@example.org")), method).get(0))
				.containsExactly("first@example.org", "second@example.org", "third@example.org");
		assertThat((String[]) CliCommandLineConsumer.convertProvidedOptionValues(
				new ArrayList<>(asList("first;second,third")), method).get(0)).containsExactly("first;second,third");
	}

	@Test
	public void exactEnvelopeSplittingPreservesQuotedNamesGroupsAndUnsafeSourceForStrictValidation() throws Exception {
		final Method method = ExactEmailBuilder.class.getMethod("withEnvelopeRecipients", String[].class);
		final String quoted = "\"Support @ Acme, Europe\" <support@example.org>";
		final String group = "Team: audit@example.org,backup@example.org;";
		final String unsafe = "evil@example.org\r\nBcc: victim@example.org";
		assertThat((String[]) CliCommandLineConsumer.convertProvidedOptionValues(
				new ArrayList<>(asList(quoted + ";" + group + "," + unsafe)), method).get(0))
				.containsExactly(quoted, group, unsafe);
		assertThat((String[]) CliCommandLineConsumer.convertProvidedOptionValues(
				new ArrayList<>(asList("one@example.org,,two@example.org")), method).get(0))
				.containsExactly("one@example.org", "", "two@example.org");
		assertThat((String[]) CliCommandLineConsumer.convertProvidedOptionValues(
				new ArrayList<>(asList(quoted + ";audit@example.org;   ")), method).get(0))
				.containsExactly(quoted, "audit@example.org");
		final byte[] eml = "From: visible@example.org\r\nTo: visible-recipient@example.org\r\nSubject: Exact\r\n\r\nbody\r\n"
				.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
		final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoader.builder().load());
		final String[] recipients = (String[]) CliCommandLineConsumer.convertProvidedOptionValues(
				new ArrayList<>(asList(quoted + ";audit@example.org")), method).get(0);
		final Email exact = mail.emailBuilder().startingFromExactEml(eml).withEnvelopeRecipients(recipients).buildEmail();
		assertThat(exact.getOverrideReceivers()).extracting(Recipient::getAddress).containsExactly("support@example.org", "audit@example.org");
		assertThat(mail.converter().fromEmail(exact).toEmlBytes()).containsExactly(eml);
		for (String rejected : new String[]{group, unsafe}) {
			final String[] entries = (String[]) CliCommandLineConsumer.convertProvidedOptionValues(
					new ArrayList<>(asList(rejected)), method).get(0);
			assertThatThrownBy(() -> mail.emailBuilder().startingFromExactEml(eml).withEnvelopeRecipients(entries))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}
	@Test
	public void numericArgumentsKeepTheirDeclaredWidthAndFraction() throws NoSuchMethodException {
		final Method method = TestClass.class.getMethod("numericValues", long.class, Long.class, double.class, Double.class);
		assertThat(CliCommandLineConsumer.convertProvidedOptionValues(
				new ArrayList<>(asList("4294967296", "9223372036854775807", "0.2", "0.75")), method))
				.containsExactly(4294967296L, Long.MAX_VALUE, 0.2, 0.75);
		assertThatThrownBy(() -> CliCommandLineConsumer.convertProvidedOptionValues(
				new ArrayList<>(asList("9223372036854775808", "1", "0.2", "0.75")), method))
				.isInstanceOf(CliExecutionException.class).hasMessage("Unable to parse a command-line value as long");
	}
	
	@Test
	public void convertProvidedOptionValues() {
		Method testMethod = ClassUtils.collectMethodsByName(TestClass.class, TestClass.class, allOf(MethodModifier.class), "testMethod").iterator().next();
		assertThat(CliCommandLineConsumer.convertProvidedOptionValues(
				new ArrayList<>(asList("o1", "1", "o2", "2", "3", "o3", "o4", "4")), testMethod))
				.containsExactly("o1", 1, "o2", 2, 3, "o3", "o4", 4);
		
		assertThat(CliCommandLineConsumer.convertProvidedOptionValues(
				new ArrayList<>(asList("1", "2", "3", "4")), testMethod))
				.containsExactly(null, 1, null, 2, 3, null, null, 4);
		
		assertThat(CliCommandLineConsumer.convertProvidedOptionValues(
				new ArrayList<>(asList("o1", "1", "o2", "2", "3", "4")), testMethod))
				.containsExactly("o1", 1, "o2", 2, 3, null, null, 4);
	}

	@Test
	public void consumeCommandLineInputOmitsCliOptionalParametersAtRuntime() {
		Method testMethod = ClassUtils.collectMethodsByName(TestClass.class, TestClass.class, allOf(MethodModifier.class), "testMethod").iterator().next();
		CliDeclaredOptionSpec declaredOption = new CliDeclaredOptionSpec(
				"--email:test-method",
				new ArrayList<>(),
				asList(
						new CliDeclaredOptionValue("optional1", "String", "", false, new String[0]),
						new CliDeclaredOptionValue("mandatory1", "int", "", true, new String[0]),
						new CliDeclaredOptionValue("optional2", "String", "", false, new String[0]),
						new CliDeclaredOptionValue("mandatory2", "int", "", true, new String[0]),
						new CliDeclaredOptionValue("mandatory3", "int", "", true, new String[0]),
						new CliDeclaredOptionValue("optional3", "String", "", false, new String[0]),
						new CliDeclaredOptionValue("optional4", "String", "", false, new String[0]),
						new CliDeclaredOptionValue("mandatory4", "int", "", true, new String[0])),
				EMAIL,
				testMethod);
		CommandLine commandLine = CliCommandLineProducer.configurePicoCli(new ArrayList<>(asList(declaredOption)), 120);

		CliReceivedCommand receivedCommand = CliCommandLineConsumer.consumeCommandLineInput(
				commandLine.parseArgs("send", "--email:test-method", "1", "2", "3", "4"),
				asList(declaredOption));

		assertThat(receivedCommand.getReceivedOptions()).hasSize(1);
		assertThat(receivedCommand.getReceivedOptions().get(0).getProvidedOptionValues())
				.containsExactly(null, 1, null, 2, 3, null, null, 4);

		CliReceivedCommand receivedCommandWithOptionalValues = CliCommandLineConsumer.consumeCommandLineInput(
				commandLine.parseArgs("send", "--email:test-method", "o1", "1", "o2", "2", "3", "o3", "o4", "4"),
				asList(declaredOption));

		assertThat(receivedCommandWithOptionalValues.getReceivedOptions()).hasSize(1);
		assertThat(receivedCommandWithOptionalValues.getReceivedOptions().get(0).getProvidedOptionValues())
				.containsExactly("o1", 1, "o2", 2, 3, "o3", "o4", 4);
	}
	
	@SuppressWarnings("unused")
	public static class TestClass {
		public void arrayValues(final String[] values) {
		}
		public void numericValues(final long primitiveLong, final Long boxedLong, final double primitiveDouble, final Double boxedDouble) {
		}

		public void testMethod(
				@Nullable @Cli.Optional String optional1,
				int mandatory1,
				@Nullable @Cli.Optional String optional2,
				int mandatory2,
				int mandatory3,
				@Nullable @Cli.Optional String optional3,
				@Nullable @Cli.Optional String optional4,
				int mandatory4) {
		}
	}
}
