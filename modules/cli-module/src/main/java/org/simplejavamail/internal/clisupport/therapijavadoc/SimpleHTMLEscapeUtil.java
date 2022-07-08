/*
 * Copyright © 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.internal.clisupport.therapijavadoc;

import java.util.HashMap;
import java.util.Map;

public class SimpleHTMLEscapeUtil {
	private static final Map<Character, String> ESCAPE_MAP = new HashMap<Character, String>() {{
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