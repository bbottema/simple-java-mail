package demo;

import org.simplejavamail.internal.modules.ModuleLoader;

public class CliShowGlobalHelpDemoApp {
	
	/**
	 * For more detailed logging open log4j2.xml and change "org.simplejavamail.internal.clisupport" to debug.
	 */
	public static void main(String[] args) {
		ModuleLoader.loadCliModule().runCLI(new String[]{
				"--help"
		});
	}
}