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

import org.jetbrains.annotations.NotNull;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

import static java.lang.String.format;

public enum CliBuilderApiType {
    EMAIL("email"), MAILER("mailer");
	
	@NotNull
    private final String paramPrefix;
    
    CliBuilderApiType(@NotNull String paramPrefix) {
        this.paramPrefix = paramPrefix;
    }
	
	public static Collection<CliBuilderApiType> findForCliSynopsis(String synopsis) {
		Set<CliBuilderApiType> foundForSynopsis = new HashSet<>();
		for (CliBuilderApiType builderApiType : values()) {
			if (synopsis.matches(format(".*--%s:.*", builderApiType.paramPrefix))) {
				foundForSynopsis.add(builderApiType);
			}
		}
		return foundForSynopsis;
	}
	
	@NotNull
	public String getParamPrefix() {
		return paramPrefix;
	}
}
