package org.simplejavamail.cli;

import static org.simplejavamail.internal.clisupport.CliSupport.runCLI;

/**
 * Entry class when using the command line interface. Wires all the args into the CLI support.
 * <p>
 * For CLI usage help, simply add the {@code --help} flag or add it to any available option, like so: <br>
 * <strong>{@code sjm send --mailer:async--help}</strong>.
 *
 * @see <a href="https://www.simplejavamail.org/modules.html#cli-module">More about the CLI module</a>
 * @see <a href="https://www.simplejavamail.org/cli.html#navigation">How to use simple Java Mail CLI</a>
 */
public class SimpleJavaMail {
	public static void main(String[] args) {
		runCLI(args);
	}
}