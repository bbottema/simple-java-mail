package org.simplejavamail.internal.clisupport;

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
		
		assertThatThrownBy(() -> colorizeDescriptions(strings))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("closed token without open token");
	}
	
	@Test
	public void colorizeDescriptions_UnbalanceTokenSets_TooManyOpened() {
		final List<String> strings = new ArrayList<>();
		strings.add("@| |@ @| @| |@");
		
		assertThatThrownBy(() -> colorizeDescriptions(strings))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("open token without closed token");
	}
	
	@Test
	public void testExtractJavadocDescription_extractJavadocExamples() {
		final String description = "Single RFC2822 address or delimited list of RFC2822 addresses of TO receiver(s). Any names included are ignored if a name was provided.";
		final String examples = " Examples:  \n" +
				"  - lolly.pop@pretzelfun.com \n" +
				"  - Lolly Pop<lolly.pop@pretzelfun.com> \n" +
				"  - a1@b1.c1,a2@b2.c2,a3@b3.c3 \r\n" +
				"  - a1@b1.c1;a2@b2.c2;a3@b3.c3 ";
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocDescription(description)).isEqualTo(description);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocDescription(description + examples)).isEqualTo(description);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocExamples(description)).isEqualTo(new String[0]);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocExamples(description + examples)).containsExactly(
				"lolly.pop@pretzelfun.com",
				"Lolly Pop<lolly.pop@pretzelfun.com>",
				"a1@b1.c1,a2@b2.c2,a3@b3.c3",
				"a1@b1.c1;a2@b2.c2;a3@b3.c3");
	}
	
	@Test
	public void testExtractJavadocDescription_extractJavadocExample() {
		final String description = "Single RFC2822 address or delimited list of RFC2822 addresses of TO receiver(s). Any names included are ignored if a name was provided.";
		final String example = " Example: lolly.pop@pretzelfun.com \n";
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocDescription(description)).isEqualTo(description);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocDescription(description + example)).isEqualTo(description);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocExamples(description)).isEqualTo(new String[0]);
		assertThat(BuilderApiToPicocliCommandsMapper.extractJavadocExamples(description + example)).containsExactly("lolly.pop@pretzelfun.com");
	}
}