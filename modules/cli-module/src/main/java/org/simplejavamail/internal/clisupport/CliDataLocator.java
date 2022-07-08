/*
 * Copyright © 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.internal.clisupport;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;

/**
 * This helper makes sure we can load cli.data no matter how the code is run (from mvn, intellij, command line).
 */
public class CliDataLocator {

	public static String locateCLIDataFile() {
		return locateDataFile("cli.data");
	}

	public static String locateTherapiDataFile() {
		return locateDataFile("therapi.data");
	}

	@NotNull
	private static String locateDataFile(String dataFileName) {
		// the following is needed bacause this is a project with submodules, and it changes depending on how the code is executed
		if (new File("src/test/resources/log4j2.xml").exists()) {
			return "src/main/resources/" + dataFileName;
		} else if (new File("modules/cli-module/src/test/resources/log4j2.xml").exists()) {
			return "modules/cli-module/src/main/resources/" + dataFileName;
		} else {
			URL resource = CliDataLocator.class.getClassLoader().getResource("log4j2.xml");
			if (resource != null) {
				try {
					return new File(resource.toURI()).getParentFile().getPath() + "/" + dataFileName;
				} catch (URISyntaxException e) {
					throw new RuntimeException(e);
				}
			}
			throw new AssertionError("Was unable to locate resources folder. Did you delete log4j2.xml?");
		}
	}
}
