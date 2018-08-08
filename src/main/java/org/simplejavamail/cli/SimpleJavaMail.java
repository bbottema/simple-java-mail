package org.simplejavamail.cli;

import org.simplejavamail.internal.clisupport.CliSupport;

public class SimpleJavaMail {
	public static void main(String[] args) {
		CliSupport.runCLI(args.length > 0 ? args : new String[] {
				"send",
				"--email:replyingToSenderWithDefaultQuoteMarkup",
				"\"value\"",
		});
	}
}
