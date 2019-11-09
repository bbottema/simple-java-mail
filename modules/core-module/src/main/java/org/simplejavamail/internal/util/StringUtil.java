package org.simplejavamail.internal.util;

import org.jetbrains.annotations.NotNull;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.lang.String.format;
import static java.util.regex.Pattern.compile;
import static java.util.regex.Pattern.quote;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;

public class StringUtil {
	
	/**
	 * @return A string containing <em>n times str</em>. Example of padding with 5 spaces: nStrings(5, " ") + rest.
	 */
	public static String nStrings(int n, String str) {
		return new String(new char[n]).replace("\0", str);
	}
	
	@NotNull
	public static String replaceNestedTokens(String lineWithTokens, int nestingDepth, final String tokenOpen, final String tokenClose, final String tokenRegexToReplace, StringFormatter tokenReplacer) {
		final Pattern startsWithOpen = compile(quote(tokenOpen));
		final Pattern startsWithClose = compile(quote(tokenClose));
		final Pattern startsWithTokenToReplace = compile(format("(?<token>%s)", tokenRegexToReplace));
		
		final StringBuilder lineWithTokensReplaced = new StringBuilder();
		
		int countOpenTokens = 0;
		int pos = 0;
		
		while (pos < lineWithTokens.length()) {
			final String remainingLine = lineWithTokens.substring(pos);
			
			if (startsWithOpen.matcher(remainingLine).lookingAt()) {
				countOpenTokens++;
				lineWithTokensReplaced.append(tokenOpen);
				pos += tokenOpen.length();
			} else if (startsWithClose.matcher(remainingLine).lookingAt()) {
				countOpenTokens--;
				lineWithTokensReplaced.append(tokenClose);
				pos += tokenClose.length();
			} else if (countOpenTokens == nestingDepth) {
				Matcher startsWithTokenMatcher = startsWithTokenToReplace.matcher(remainingLine);
				if (startsWithTokenMatcher.lookingAt()) {
					String matchedToken = startsWithTokenMatcher.group("token");
					lineWithTokensReplaced.append(tokenReplacer.apply(matchedToken));
					pos += matchedToken.length();
				} else {
					lineWithTokensReplaced.append(lineWithTokens.charAt(pos++));
				}
			} else {
				lineWithTokensReplaced.append(lineWithTokens.charAt(pos++));
			}
			assumeTrue(countOpenTokens >= 0, "Unbalanced token sets: closed token without open token\n\t" + lineWithTokens);
		}
		assumeTrue(countOpenTokens == 0, "Unbalanced token sets: open token without closed token\n\t" + lineWithTokens);
		return lineWithTokensReplaced.toString();
	}
	
	public static class StringFormatter {
		private final String formatPattern;
		
		public static StringFormatter formatterForPattern(@NotNull String pattern) {
			return new StringFormatter(pattern);
		}
		
		private StringFormatter(String formatPattern) {
			this.formatPattern = formatPattern;
		}
		
		String apply(String input) {
			return format(formatPattern, input);
		}
	}
	
	public static String padRight(String s, int n) {
		return format("%1$-" + n + "s", s);
	}
}
