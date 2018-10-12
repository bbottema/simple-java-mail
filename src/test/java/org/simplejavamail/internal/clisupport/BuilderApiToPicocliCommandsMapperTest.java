package org.simplejavamail.internal.clisupport;

import org.assertj.core.api.ThrowableAssert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.internal.clisupport.BuilderApiToPicocliCommandsMapper.colorizeDescriptions;

public class BuilderApiToPicocliCommandsMapperTest {
	
	@Test
	public void testColorizeDescriptions() {
		assertThat(colorizeDescriptions(singletonList("nothing to colorize"))).containsExactly("nothing to colorize");
		assertThat(colorizeDescriptions(singletonList("one --x:item to colorize"))).containsExactly("one @|cyan --x:item|@ to colorize");
		assertThat(colorizeDescriptions(singletonList("item @|--x:already|@ colorized"))).containsExactly("item @|--x:already|@ colorized");
		assertThat(colorizeDescriptions(singletonList("@|item @|--x:already|@ c|@olorized"))).containsExactly("@|item @|--x:already|@ c|@olorized");
		assertThat(colorizeDescriptions(singletonList("@|one @|--x:item|@ --x:to|@--x:colorize"))).containsExactly("@|one @|--x:item|@ --x:to|@@|cyan --x:colorize|@");
	}
	
	@Test
	public void colorizeDescriptions_UnbalanceTokenSets_TooManyClosed() {
		final List<String> strings = new ArrayList<>();
		strings.add("@| |@ |@ @| |@");
		
		assertThatThrownBy(new ThrowableAssert.ThrowingCallable() {
			public void call() {
				colorizeDescriptions(strings);
			}
		})
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("closed token without open token");
	}
	
	@Test
	public void colorizeDescriptions_UnbalanceTokenSets_TooManyOpened() {
		final List<String> strings = new ArrayList<>();
		strings.add("@| |@ @| @| |@");
		
		assertThatThrownBy(new ThrowableAssert.ThrowingCallable() {
			public void call() {
				colorizeDescriptions(strings);
			}
		})
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("open token without closed token");
	}

	@Test
	public void testExtractJavadocDescription_extractJavadocExamples() {
		final String description = "\t/**\n"
				+ "\t * Alias for {@link #toWithFixedName(String, String...)}.\n"
				+ "\t *\n"
				+ "\t * @param name               The optional name of the TO receiver(s) of the email. If multiples addresses are provided, all addresses will be in\n"
				+ "\t *                           this same name. Examples:\n"
				+ "\t * @param oneOrMoreAddresses Single RFC2822 address or delimited list of RFC2822 addresses of TO receiver(s). Any names included are ignored if a\n"
				+ "\t *                           name was provided.";
		final String examples = " Examples:\n"
				+ "\t *                           <ul>\n"
				+ "\t *                           <li>lolly.pop@pretzelfun.com</li>\n"
				+ "\t *                           <li>Lolly Pop<lolly.pop@pretzelfun.com></li>\n"
				+ "\t *                           <li>a1@b1.c1,a2@b2.c2,a3@b3.c3</li>\n"
				+ "\t *                           <li>a1@b1.c1;a2@b2.c2;a3@b3.c3</li>\n"
				+ "\t *                           </ul>\n"
				+ "\t */";
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocDescription(description)).isEqualTo(description);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocDescription(description + examples)).isEqualTo(description);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocExamples(description)).isEqualTo(new String[0]);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocExamples(description + examples)).containsExactly(
				"lolly.pop@pretzelfun.com",
				"Lolly Pop<lolly.pop@pretzelfun.com>",
				"a1@b1.c1,a2@b2.c2,a3@b3.c3",
				"a1@b1.c1;a2@b2.c2;a3@b3.c3");
	}
}