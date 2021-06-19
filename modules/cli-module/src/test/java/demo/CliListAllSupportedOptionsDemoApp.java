package demo;

import org.simplejavamail.internal.clisupport.CliSupport;
import org.simplejavamail.internal.clisupport.therapijavadoc.TherapiJavadocHelper;

/**
 * This is a general smokescreen test as well as a deployment step to make sure cli.data and
 * therapi.data are generated when packaging CLI artifacts.
 */
public class CliListAllSupportedOptionsDemoApp {
	
	/**
	 * For more detailed logging open log4j2.xml and change "org.simplejavamail.internal.clisupport" to debug.
	 */
	public static void main(String[] args) {
		long startMs = System.currentTimeMillis();
		CliSupport.listUsagesForAllOptions();
		System.out.println(((System.currentTimeMillis() - startMs) / 1000d) + "s");
		TherapiJavadocHelper.persistCache();
	}
}