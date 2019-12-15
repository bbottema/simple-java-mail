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
package org.simplejavamail.internal.modules;

import org.simplejavamail.internal.util.MiscUtil;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static java.lang.String.format;

public class ModuleLoader {
	
	private static final Map<Class, Object> LOADED_MODULES = new HashMap<>();

	// used from junit tests
	private static final Collection<Class> FORCED_DISABLED_MODULES = new ArrayList<>();
	
	public static void clearLoadedModules() {
		LOADED_MODULES.clear();
	}
	
	public static AuthenticatedSocksModule loadAuthenticatedSocksModule() {
		if (!LOADED_MODULES.containsKey(AuthenticatedSocksModule.class)) {
			LOADED_MODULES.put(AuthenticatedSocksModule.class, loadModule(
					AuthenticatedSocksModule.class,
					"Authenticated Socks",
					"org.simplejavamail.internal.authenticatedsockssupport.AuthenticatedSocksHelper",
					"https://github.com/bbottema/simple-java-mail/tree/develop/modules/authenticated-socks-module"));
		}
		return (AuthenticatedSocksModule) LOADED_MODULES.get(AuthenticatedSocksModule.class);
	}
	
	public static DKIMModule loadDKIMModule() {
		if (!LOADED_MODULES.containsKey(DKIMModule.class)) {
			LOADED_MODULES.put(DKIMModule.class, loadModule(
					DKIMModule.class,
					"DKIM",
					"org.simplejavamail.internal.dkimsupport.DKIMSigner",
					"https://github.com/bbottema/simple-java-mail/tree/develop/modules/dkim-module"));
		}
		return (DKIMModule) LOADED_MODULES.get(DKIMModule.class);
	}

	public static OutlookModule loadOutlookModule() {
		if (!LOADED_MODULES.containsKey(OutlookModule.class)) {
			LOADED_MODULES.put(OutlookModule.class, loadModule(
					OutlookModule.class,
					"Outlook",
					"org.simplejavamail.internal.outlooksupport.converter.OutlookEmailConverter",
					"https://github.com/bbottema/simple-java-mail/tree/develop/modules/outlook-module"
			));
		}
		return (OutlookModule) LOADED_MODULES.get(OutlookModule.class);
	}

	public static SMIMEModule loadSmimeModule() {
		if (!LOADED_MODULES.containsKey(SMIMEModule.class)) {
			LOADED_MODULES.put(SMIMEModule.class, loadModule(
					SMIMEModule.class,
					"S/MIME",
					"org.simplejavamail.internal.smimesupport.SMIMESupport",
					"https://github.com/bbottema/simple-java-mail/tree/develop/modules/smime-module"
			));
		}
		return (SMIMEModule) LOADED_MODULES.get(SMIMEModule.class);
	}

	public static BatchModule loadBatchModule() {
		if (!LOADED_MODULES.containsKey(BatchModule.class)) {
			LOADED_MODULES.put(BatchModule.class, loadModule(
					BatchModule.class,
					"Batch",
					"org.simplejavamail.internal.batchsupport.BatchSupport",
					"https://github.com/bbottema/simple-java-mail/tree/develop/modules/batch-module"
			));
		}
		return (BatchModule) LOADED_MODULES.get(BatchModule.class);
	}

	public static boolean batchModuleAvailable() {
		return !FORCED_DISABLED_MODULES.contains(BatchModule.class) && MiscUtil.classAvailable("org.simplejavamail.internal.batchsupport.BatchSupport");
	}

	public static boolean smimeModuleAvailable() {
		return !FORCED_DISABLED_MODULES.contains(SMIMEModule.class) && MiscUtil.classAvailable("org.simplejavamail.internal.smimesupport.SMIMESupport");
	}

	@SuppressWarnings("unchecked")
	private static <T> T loadModule(Class moduleClass,String moduleName, String moduleImplClassName, String moduleHome) {
		try {
			if (FORCED_DISABLED_MODULES.contains(moduleClass)) {
				throw new IllegalAccessException("Module is focrfully disabled");
			}
			if (!MiscUtil.classAvailable(moduleImplClassName)) {
				throw new org.simplejavamail.internal.modules.ModuleLoaderException(format(org.simplejavamail.internal.modules.ModuleLoaderException.ERROR_MODULE_MISSING, moduleName, moduleHome));
			}
			return (T) Class.forName(moduleImplClassName).newInstance();
		} catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
			throw new org.simplejavamail.internal.modules.ModuleLoaderException(format(org.simplejavamail.internal.modules.ModuleLoaderException.ERROR_LOADING_MODULE, moduleName), e);
		}
	}

	// used from junit tests (using reflection, because it's invisible in the core-module)
	@SuppressWarnings("unused")
	public static void _forceDisableBatchModule() {
		FORCED_DISABLED_MODULES.add(BatchModule.class);
	}
}