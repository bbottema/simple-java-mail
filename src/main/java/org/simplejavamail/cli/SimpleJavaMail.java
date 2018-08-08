package org.simplejavamail.cli;

import org.simplejavamail.internal.clisupport.CliSupport;

public class SimpleJavaMail {
	public static void main(String[] args) {
		// FIXME load class dynamically so cli dependency can be optional
		CliSupport.runCLI(args);
	}
}
