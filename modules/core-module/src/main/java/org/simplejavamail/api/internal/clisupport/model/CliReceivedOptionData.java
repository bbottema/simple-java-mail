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

public class CliReceivedOptionData {
	private final CliDeclaredOptionSpec declaredOptionSpec;
	private final List<Object> providedOptionValues;
	
	public CliReceivedOptionData(CliDeclaredOptionSpec declaredOptionSpec, List<Object> providedOptionValues) {
		this.declaredOptionSpec = declaredOptionSpec;
		this.providedOptionValues = providedOptionValues;
	}
	
	public CliBuilderApiType determineTargetBuilderApi() {
		Class<?> apiNode = declaredOptionSpec.getSourceMethod().getDeclaringClass();
		return apiNode.getAnnotation(Cli.BuilderApiNode.class).builderApiType();
	}
	
	public CliDeclaredOptionSpec getDeclaredOptionSpec() {
		return declaredOptionSpec;
	}
	
	public List<Object> getProvidedOptionValues() {
		return providedOptionValues;
	}
}
