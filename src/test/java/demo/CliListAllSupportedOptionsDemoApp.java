package demo;

import org.simplejavamail.internal.modules.ModuleLoader;

public class CliListAllSupportedOptionsDemoApp {
	
	/**
	 * For more detailed logging open log4j2.xml and change "org.simplejavamail.internal.clisupport" to debug.
	 */
	public static void main(String[] args) {
		long startMs = System.currentTimeMillis();
		ModuleLoader.loadCliModule().listUsagesForAllOptions();
		System.out.println(((System.currentTimeMillis() - startMs) / 1000d) + "ms");
	}
}