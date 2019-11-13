package org.simplejavamail.cli;

import static org.simplejavamail.internal.clisupport.CliSupport.*;

/**
 * Entry class when using the command line interface. Wires all the args into the CLI support.
 * <p>
 * For CLI usage help, simply add the {@code --help} flag or add it to any available option,
 * like so: {@code sjm send --mailer:async--help}.
 */
public class SimpleJavaMail {
	public static void main(String[] args) {
		runCLI(args);
	}
}