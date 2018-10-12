package org.simplejavamail.cli;

import org.simplejavamail.internal.clisupport.CliSupport;
import org.simplejavamail.internal.clisupport.model.CliDeclaredOptionSpec;

import java.io.IOException;

public class SimpleJavaMail {
	public static void main(String[] args) throws IOException {
		// FIXME load class dynamically so cli dependency can be optional
		// CliSupport.runCLI(args);
		for (CliDeclaredOptionSpec declaredOption : CliSupport.DECLARED_OPTIONS) {
			CliSupport.runCLI(new String[] { "send", declaredOption.getName() + "--help" });
			System.out.print("\n\n\n");
		}
	}
}