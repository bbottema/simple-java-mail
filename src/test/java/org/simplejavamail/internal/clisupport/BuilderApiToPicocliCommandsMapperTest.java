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
	public void textColorizeDescriptions() {
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
}