package org.simplejavamail.internal.util;

import org.assertj.core.api.ThrowableAssert;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.simplejavamail.internal.util.StringUtil.StringFormatter.formatterForPattern;
import static org.simplejavamail.internal.util.StringUtil.replaceNestedTokens;

public class StringUtilTest {
	
	@Test
	public void testReplaceNestedTokensAtDepth0() {
		assertThat(replaceNestedTokens("nothing to replace", 0, "@|", "|@", "--[\\w:]*", formatterForPattern("@|cyan %s|@")))
				.isEqualTo("nothing to replace");
		assertThat(replaceNestedTokens("one --item to replace", 0, "@|", "|@", "--[\\w:]*", formatterForPattern("@|cyan %s|@")))
				.isEqualTo("one @|cyan --item|@ to replace");
		assertThat(replaceNestedTokens("item @|--already|@ replaced", 0, "@|", "|@", "--[\\w:]*", formatterForPattern("@|cyan %s|@")))
				.isEqualTo("item @|--already|@ replaced");
		assertThat(replaceNestedTokens("<item <--already> r>eplaced", 0, "<", ">", "--[\\w:]*", formatterForPattern("<cyan %s>")))
				.isEqualTo("<item <--already> r>eplaced");
		assertThat(replaceNestedTokens("--[one --[--item]-- --to]----replace", 0, "--[", "]--", "--[\\w:]*", formatterForPattern("REPLACE")))
				.isEqualTo("--[one --[--item]-- --to]--REPLACE");
	}
	
	@Test
	public void testReplaceNestedTokensAtDepth2() {
		assertThat(replaceNestedTokens("nothing to replace", 2, "@|", "|@", "--[\\w:]*", formatterForPattern("@|cyan %s|@")))
				.isEqualTo("nothing to replace");
		assertThat(replaceNestedTokens("no --item to replace", 2, "@|", "|@", "--[\\w:]*", formatterForPattern("@|cyan %s|@")))
				.isEqualTo("no --item to replace");
		assertThat(replaceNestedTokens("item @|--at|@ level 1", 2, "@|", "|@", "--[\\w:]*", formatterForPattern("@|cyan %s|@")))
				.isEqualTo("item @|--at|@ level 1");
		assertThat(replaceNestedTokens("<item <--to> r>eplace", 2, "<", ">", "--[\\w:]*", formatterForPattern("TO")))
				.isEqualTo("<item <TO> r>eplace");
		assertThat(replaceNestedTokens("--[one --[--item]-- --to]----replace", 2, "--[", "]--", "--[\\w:]*", formatterForPattern("ITEM")))
				.isEqualTo("--[one --[ITEM]-- --to]----replace");
	}
	
	@Test
	public void colorizeDescriptions_UnbalanceTokenSets_TooManyClosed() {
		assertThatThrownBy(new ThrowableAssert.ThrowingCallable() {
			public void call() {
				replaceNestedTokens("{{ ] ] {{ ]", 0, "{{", "]", "--[\\w:]*", formatterForPattern("%s"));
			}
		})
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("closed token without open token");
	}
	
	@Test
	public void colorizeDescriptions_UnbalanceTokenSets_TooManyOpened() {
		assertThatThrownBy(new ThrowableAssert.ThrowingCallable() {
			public void call() {
				replaceNestedTokens("{ } { { }", 0, "{", "}", "--[\\w:]*", formatterForPattern("%s"));
			}
		})
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("open token without closed token");
	}
}