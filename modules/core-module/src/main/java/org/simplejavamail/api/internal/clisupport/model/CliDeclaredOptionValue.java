/*
 * Copyright (C) 2009 Benny Bottema (benny@bennybottema.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.simplejavamail.api.internal.clisupport.model;

import java.util.List;

import static java.util.Arrays.asList;
import static org.simplejavamail.internal.util.ListUtil.getFirst;
import static org.simplejavamail.internal.util.StringUtil.nStrings;

public class CliDeclaredOptionValue {
	private final String name;
	private final String helpLabel;
	private final String description;
	private final boolean required;
	private final String[] examples;
	
	public CliDeclaredOptionValue(String name, String helpLabel, String description, boolean required, String[] examples) {
		this.name = name;
		this.helpLabel = helpLabel;
		this.description = description;
		this.required = required;
		this.examples = examples.clone();
	}
	
	public String formatDescription() {
		if (examples.length == 0) {
			return description;
		} else if (examples.length == 1) {
			return description + "\n    example: " + examples[0];
		} else {
			return description + "\n    examples: " + formatExamplesText("    examples: ".length(), asList(examples));
		}
	}
	
	private static String formatExamplesText(int indent, List<String> examples) {
		StringBuilder examplesFormatted = new StringBuilder().append(getFirst(examples)).append("\n");
		for (String example : examples.subList(1, examples.size())) {
			examplesFormatted.append(nStrings(indent, " ")).append(example).append("\n");
		}
		return examplesFormatted.toString();
	}
	
	public String getName() {
		return name;
	}
	
	public String getHelpLabel() {
		return helpLabel;
	}
	
	public boolean isRequired() {
		return required;
	}
}