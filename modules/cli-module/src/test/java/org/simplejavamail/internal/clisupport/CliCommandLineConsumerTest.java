package org.simplejavamail.internal.clisupport;

import org.bbottema.javareflection.ClassUtils;
import org.bbottema.javareflection.model.MethodModifier;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.internal.clisupport.model.Cli;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionValue;
import org.simplejavamail.api.internal.clisupport.model.CliReceivedCommand;
import picocli.CommandLine;

import java.lang.reflect.Method;
import java.util.ArrayList;

import static java.util.Arrays.asList;
import static java.util.EnumSet.allOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType.EMAIL;

public class CliCommandLineConsumerTest {
	
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
