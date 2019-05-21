package demo;

import java.io.File;

public class ResourceFolderHelper {

	public static String determineResourceFolder(@SuppressWarnings("SameParameterValue") final String module) {
		// the following is needed bacause this is a project with submodules
		if (new File("src/test/resources/log4j2.xml").exists()) {
			return "src";
		} else if (new File("modules/" + module + "/src/test/resources/log4j2.xml").exists()) {
			return "modules/" + module + "/src";
		} else {
			throw new AssertionError("Was unable to locate resources folder");
		}
	}
}
