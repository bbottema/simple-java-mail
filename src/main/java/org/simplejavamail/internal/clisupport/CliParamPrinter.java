package org.simplejavamail.internal.clisupport;

import java.io.PrintStream;
import java.util.Collection;

public class CliParamPrinter {
	public static void printCliParams(Collection<CliOptionFlag> cliOptionFlags, PrintStream out) {
		out.println(cliOptionFlags);
	}
}
