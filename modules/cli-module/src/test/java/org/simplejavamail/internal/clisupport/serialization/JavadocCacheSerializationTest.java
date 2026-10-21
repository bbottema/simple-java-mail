package org.simplejavamail.internal.clisupport.serialization;

import com.esotericsoftware.kryo.KryoException;
import com.github.therapi.runtimejavadoc.Comment;
import com.github.therapi.runtimejavadoc.CommentText;
import com.github.therapi.runtimejavadoc.InlineLink;
import com.github.therapi.runtimejavadoc.InlineTag;
import com.github.therapi.runtimejavadoc.InlineValue;
import com.github.therapi.runtimejavadoc.Link;
import com.github.therapi.runtimejavadoc.MethodJavadoc;
import com.github.therapi.runtimejavadoc.OtherJavadoc;
import com.github.therapi.runtimejavadoc.ParamJavadoc;
import com.github.therapi.runtimejavadoc.SeeAlsoJavadoc;
import com.github.therapi.runtimejavadoc.ThrowsJavadoc;
import com.github.therapi.runtimejavadoc.Value;
import org.junit.jupiter.api.Test;
import org.simplejavamail.api.email.EmailPopulatingBuilder;
import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.internal.clisupport.CliDataLocator;

import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JavadocCacheSerializationTest {

	@Test
	void roundTripsNestedImmutableJavadocsWithoutLosingContentOrImmutability() {
		Link link = new Link("Mailer", "org.simplejavamail.api.mailer.Mailer", "sendMail",
				asList("org.simplejavamail.api.email.Email", "boolean"));
		Comment description = new Comment(asList(new CommentText("naïve 日本語\n"), new InlineLink(link),
				new InlineTag("code", "x < y"), new InlineValue(new Value("java.nio.charset.StandardCharsets", "UTF_8"))));
		MethodJavadoc original = new MethodJavadoc("cached", asList("java.lang.String", "int"), description,
				asList(new ParamJavadoc("subject", text("Subject")), new ParamJavadoc("count", text("Count"))),
				Collections.singletonList(new ThrowsJavadoc("IllegalArgumentException", text("Invalid count"))),
				Collections.singletonList(new OtherJavadoc("since", text("9.3.8"))), text("The builder"),
				asList(new SeeAlsoJavadoc("Literal reference"),
						new SeeAlsoJavadoc(new SeeAlsoJavadoc.HtmlLink("Documentation", "https://example.invalid/help")),
						new SeeAlsoJavadoc(link)));

		Map<String, MethodJavadoc> restored = JavadocCacheSerialization.deserialize(
				JavadocCacheSerialization.serialize(Collections.singletonMap("cached(java.lang.String,int)", original)));
		MethodJavadoc actual = restored.get("cached(java.lang.String,int)");

		assertThat(actual.getName()).isEqualTo(original.getName());
		assertThat(actual.getParamTypes()).containsExactlyElementsOf(original.getParamTypes());
		assertThat(actual.getComment().getElements()).containsExactlyElementsOf(description.getElements());
		assertThat(actual.getParams()).extracting(param -> param.getName()).containsExactly("subject", "count");
		assertThat(actual.getParams().get(0).getComment().toString()).isEqualTo("Subject");
		assertThat(actual.getParams().get(1).getComment().toString()).isEqualTo("Count");
		assertThat(actual.getThrows()).extracting(thrown -> thrown.getName()).containsExactly("IllegalArgumentException");
		assertThat(actual.getThrows().get(0).getComment().toString()).isEqualTo("Invalid count");
		assertThat(actual.getReturns().toString()).isEqualTo("The builder");
		assertThat(actual.getOther()).extracting(other -> other.getName()).containsExactly("since");
		assertThat(actual.getOther().get(0).getComment().toString()).isEqualTo("9.3.8");
		assertThat(actual.getSeeAlso()).extracting(SeeAlsoJavadoc::getSeeAlsoType).containsExactly(
				SeeAlsoJavadoc.SeeAlsoType.STRING_LITERAL, SeeAlsoJavadoc.SeeAlsoType.HTML_LINK, SeeAlsoJavadoc.SeeAlsoType.JAVADOC_LINK);
		assertThat(actual.getSeeAlso().get(0).getStringLiteral()).isEqualTo("Literal reference");
		assertThat(actual.getSeeAlso().get(1).getHtmlLink().getText()).isEqualTo("Documentation");
		assertThat(actual.getSeeAlso().get(1).getHtmlLink().getLink()).isEqualTo("https://example.invalid/help");
		assertThat(actual.getSeeAlso().get(2).getLink()).isEqualTo(link);
		assertThatThrownBy(() -> actual.getComment().getElements().clear()).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> actual.getParamTypes().add("mutation")).isInstanceOf(UnsupportedOperationException.class);
		assertThatThrownBy(() -> actual.getSeeAlso().clear()).isInstanceOf(UnsupportedOperationException.class);
	}

	@Test
	void roundTripsAnEmptyCacheAsAMutableLookupMap() {
		Map<String, MethodJavadoc> restored = JavadocCacheSerialization.deserialize(
				JavadocCacheSerialization.serialize(Collections.emptyMap()));
		assertThat(restored).isEmpty();
		restored.put("new lookup", new MethodJavadoc("method", Collections.emptyList(), text("Loaded later"),
				Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Comment.createEmpty(), Collections.emptyList()));
		assertThat(restored).hasSize(1);
	}

	@Test
	void preservesEmptyMethodJavadocSemantics() throws Exception {
		Method method = EmailPopulatingBuilder.class.getMethod("withSubject", String.class);
		MethodJavadoc original = MethodJavadoc.createEmpty(method);
		MethodJavadoc restored = JavadocCacheSerialization.deserialize(
				JavadocCacheSerialization.serialize(Collections.singletonMap("empty", original))).get("empty");
		assertThat(restored.isEmpty()).isTrue();
		assertThat(restored.getName()).isEqualTo("withSubject");
		assertThat(restored.getParamTypes()).containsExactly("java.lang.String");
	}

	@Test
	void rejectsUnknownTruncatedAndTrailingData() {
		byte[] encoded = JavadocCacheSerialization.serialize(Collections.emptyMap());
		byte[] unknownFormat = encoded.clone();
		unknownFormat[0] ^= 1;
		assertThatThrownBy(() -> JavadocCacheSerialization.deserialize(unknownFormat)).isInstanceOf(KryoException.class);
		assertThatThrownBy(() -> JavadocCacheSerialization.deserialize(Arrays.copyOf(encoded, encoded.length - 1)))
				.isInstanceOf(KryoException.class);
		assertThatThrownBy(() -> JavadocCacheSerialization.deserialize(Arrays.copyOf(encoded, encoded.length + 1)))
				.isInstanceOf(KryoException.class);
	}

	@Test
	void rejectsEntriesThatAreNotMethodJavadocs() {
		byte[] encoded = JavadocCacheSerialization.serialize(Collections.singletonMap("invalid", null));
		assertThatThrownBy(() -> JavadocCacheSerialization.deserialize(encoded))
				.isInstanceOf(KryoException.class).hasMessageContaining("Invalid CLI Javadoc cache entry");
	}

	@Test
	void leavesTheExistingCliOptionCacheFormatReadable() throws Exception {
		List<CliDeclaredOptionSpec> options = SerializationUtil.deserialize(
				Files.readAllBytes(new File(CliDataLocator.locateCLIDataFile()).toPath()));
		assertThat(options).extracting(CliDeclaredOptionSpec::getName).contains("--email:to", "--email:withSubject");
	}

	private static Comment text(String value) {
		return new Comment(Collections.singletonList(new CommentText(value)));
	}
}
