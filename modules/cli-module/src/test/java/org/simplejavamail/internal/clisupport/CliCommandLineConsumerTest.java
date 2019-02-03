package org.simplejavamail.internal.clisupport;

import org.bbottema.javareflection.ClassUtils;
import org.bbottema.javareflection.model.MethodModifier;
import org.junit.Test;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;

import static java.util.Arrays.asList;
import static java.util.EnumSet.allOf;
import static org.assertj.core.api.Assertions.assertThat;

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
	
	@SuppressWarnings("unused")
	public static class TestClass {
		public void testMethod(
				@Nullable String optional1,
				int mandatory1,
				@Nullable String optional2,
				int mandatory2,
				int mandatory3,
				@Nullable String optional3,
				@Nullable String optional4,
				int mandatory4) {
		}
	}
}