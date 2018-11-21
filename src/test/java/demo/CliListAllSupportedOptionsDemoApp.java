package demo;

import org.simplejavamail.internal.clisupport.CliSupport;
import org.simplejavamail.internal.clisupport.model.CliDeclaredOptionSpec;

public class CliListAllSupportedOptionsDemoApp {
	public static void main(String[] args) {
		long startMs = System.currentTimeMillis();
		displayAllOptions(args);
		System.out.println(((System.currentTimeMillis() - startMs) / 1000d) + "ms");
	}
	
	private static void displayAllOptions(String[] args) {
		for (CliDeclaredOptionSpec declaredOption : CliSupport.DECLARED_OPTIONS) {
			CliSupport.runCLI(args.length > 0 ? args : new String[]{ "send", declaredOption.getName() + "--help" });
			System.out.print("\n\n\n\n");
		}
	}
}