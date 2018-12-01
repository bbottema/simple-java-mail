package org.simplejavamail.cli;

import org.simplejavamail.internal.modules.ModuleLoader;

/**
 * Wires all the args into the CLI support, which is dynamically class-loaded as it is an optional dependency.
 * <p>
 * A note on the Javadoc-to-CLI-usage conversion: Everything is converted generically with the following exception:
 * <ul>
 * <li>[<strong>{@code (?i)Alias for:?}</strong>]:<br/>Javadoc with an "Alias for: {@ocde {@link}}" occurrence (or variation) are processed
 * specially</li>
 * <li>[<strong>{@code (?i)(?:delegates|delegating) to:?}</strong>]:<br/>Javadoc with an "Delegates to: {@ocde {@link}}" occurrence (or variation) are
 * processed specially</li>
 * <li>[<strong>{@code (?i)(?s).*(?<examples> examples?:\s*)}</strong>]:<br/>Javadoc with an "Examples: HTML list of examples" occurrence (or
 * variation) are processed specially</li>
 * </ul>
 */
public class SimpleJavaMail {
	public static void main(String[] args) {
		ModuleLoader.loadCliModule().runCLI(args);
	}
}