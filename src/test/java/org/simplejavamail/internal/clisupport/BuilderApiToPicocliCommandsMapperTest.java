package org.simplejavamail.internal.clisupport;

import org.assertj.core.api.ThrowableAssert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

public class BuilderApiToPicocliCommandsMapperTest {
	
	@Test
	public void colorizeDescriptions() {
		List<String> strings = new ArrayList<>();
		strings.add("nothing to colorize");
		strings.add("one --item to colorize");
		strings.add("item @|--already|@ colorized");
		strings.add("@|item @|--already|@ c|@olorized");
		strings.add("@|one @|--item|@ --to|@--colorize");
		
		List<String> colorizedStrings = BuilderApiToPicocliCommandsMapper.colorizeDescriptions(strings);
		
		assertThat(colorizedStrings).containsExactly(
				"nothing to colorize",
				"one @|cyan --item|@ to colorize",
				"item @|--already|@ colorized",
				"@|item @|--already|@ c|@olorized",
				"@|one @|--item|@ --to|@@|cyan --colorize|@"
		);
	}
	
	@Test
	public void colorizeDescriptions_UnbalanceTokenSets_TooManyClosed() {
		final List<String> strings = new ArrayList<>();
		strings.add("@| |@ |@ @| |@");
		
		assertThatThrownBy(new ThrowableAssert.ThrowingCallable() {
			public void call() {
				BuilderApiToPicocliCommandsMapper.colorizeDescriptions(strings);
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
				BuilderApiToPicocliCommandsMapper.colorizeDescriptions(strings);
			}
		})
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("open token without closed token");
	}
}