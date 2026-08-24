package org.simplejavamail.internal.clisupport.serialization;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionValue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.api.internal.clisupport.model.CliBuilderApiType.EMAIL;

public class SerializationUtilTest {

	@Test
	public void serializesCliOptionSpecsWithoutReflectingIntoJdkCollections() throws Exception {
		Method method = TestBuilderApi.class.getDeclaredMethod("option", String.class, int.class, boolean.class);
		CliDeclaredOptionSpec optionSpec = new CliDeclaredOptionSpec(
				"--email:option",
				asList("Line one", "Line two"),
				asList(
						new CliDeclaredOptionValue("optional", "String", "", false, new String[] { "example" }),
						new CliDeclaredOptionValue("mandatory", "int", "", true, new String[0])),
				EMAIL,
				method);

		CliDeclaredOptionSpec deserialized = SerializationUtil.deserialize(SerializationUtil.serialize(optionSpec));

		assertThat(deserialized.getName()).isEqualTo("--email:option");
		assertThat(deserialized.getDescription()).containsExactly("Line one", "Line two");
		assertThat(deserialized.getPossibleOptionValues()).extracting(CliDeclaredOptionValue::getName)
				.containsExactly("optional", "mandatory");
		assertThat(deserialized.getSourceMethod()).isEqualTo(method);
		assertThatThrownBy(() -> deserialized.getDescription().add("mutation"))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	public void serializesTheUnmodifiableListsUsedByTherapiJavadoc() {
		final List<String> source = Collections.unmodifiableList(new ArrayList<>(List.of("first", "second")));

		final List<String> deserialized = SerializationUtil.deserialize(SerializationUtil.serialize(source));

		assertThat(deserialized).containsExactly("first", "second");
		assertThatThrownBy(() -> deserialized.add("mutation"))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	public void serializesTheUnmodifiableMapsUsedByTherapiJavadoc() {
		final Map<String, String> source = Collections.unmodifiableMap(
				new LinkedHashMap<>(Map.of("name", "description")));

		final Map<String, String> deserialized = SerializationUtil.deserialize(SerializationUtil.serialize(source));

		assertThat(deserialized).containsExactly(Map.entry("name", "description"));
		assertThatThrownBy(() -> deserialized.put("mutation", "rejected"))
				.isInstanceOf(UnsupportedOperationException.class);
	}

	@SuppressWarnings("unused")
	public static class TestBuilderApi {
		public void option(String optional, int mandatory, boolean flag) {
		}
	}
}
