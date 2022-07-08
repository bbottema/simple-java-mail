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
package org.simplejavamail.internal.modules;

import org.jetbrains.annotations.NotNull;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.internal.general.EmailPopulatingBuilderFactory;
import org.simplejavamail.api.internal.outlooksupport.model.EmailFromOutlookMessage;
import org.simplejavamail.internal.util.InternalEmailConverter;

import java.io.File;
import java.io.InputStream;

public interface OutlookModule {
	EmailFromOutlookMessage outlookMsgToEmailBuilder(@NotNull File msgFile, @NotNull EmailStartingBuilder emailStartingBuilder, @NotNull EmailPopulatingBuilderFactory builderFactory, @NotNull InternalEmailConverter internalEmailConverter);
	EmailFromOutlookMessage outlookMsgToEmailBuilder(@NotNull String msgData, @NotNull EmailStartingBuilder emailStartingBuilder, @NotNull EmailPopulatingBuilderFactory builderFactory, @NotNull InternalEmailConverter internalEmailConverter);
	EmailFromOutlookMessage outlookMsgToEmailBuilder(@NotNull InputStream msgInputStream, @NotNull EmailStartingBuilder emailStartingBuilder, @NotNull EmailPopulatingBuilderFactory builderFactory, @NotNull InternalEmailConverter internalEmailConverter);
}