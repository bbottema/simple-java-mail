package org.simplejavamail.internal.clisupport.therapijavadoc;

import java.util.HashMap;
import java.util.Map;

public class SimpleHTMLEscapeUtil {
	public static final Map<Character, String> ESCAPE_MAP = new HashMap<Character, String>() {{
		put('"', "&quot;");
		put('&', "&amp;");
		put('<', "&lt;");
		put('>', "&gt;");
	}};

	public static String escapeHTML(String htmlStr) {
		final StringBuilder escaped = new StringBuilder();
		for (final Character c : htmlStr.toCharArray()) {
			escaped.append(ESCAPE_MAP.getOrDefault(c, c.toString()));
		}
		return escaped.toString();
	}
}