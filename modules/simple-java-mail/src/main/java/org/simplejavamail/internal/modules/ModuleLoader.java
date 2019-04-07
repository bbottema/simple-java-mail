package org.simplejavamail.internal.modules;

import org.simplejavamail.internal.util.MiscUtil;

import java.util.HashMap;
import java.util.Map;

import static java.lang.String.format;

public class ModuleLoader {
	
	private static final Map<Class, Object> LOADED_MODULES = new HashMap<>();
	
	public static void clearLoadedModules() {
		LOADED_MODULES.clear();
	}
	
	public static AuthenticatedSocksModule loadAuthenticatedSocksModule() {
		if (!LOADED_MODULES.containsKey(AuthenticatedSocksModule.class)) {
			LOADED_MODULES.put(AuthenticatedSocksModule.class, loadModule(
					"Authenticated Socks",
					"org.simplejavamail.internal.authenticatedsockssupport.AuthenticatedSocksHelper",
					"https://github.com/bbottema/simple-java-mail/tree/develop/modules/authenticated-socks-module"));
		}
		return (AuthenticatedSocksModule) LOADED_MODULES.get(AuthenticatedSocksModule.class);
	}
	
	public static DKIMModule loadDKIMModule() {
		if (!LOADED_MODULES.containsKey(DKIMModule.class)) {
			LOADED_MODULES.put(DKIMModule.class, loadModule(
					"DKIM",
					"org.simplejavamail.internal.dkimsupport.DKIMSigner",
					"https://github.com/bbottema/simple-java-mail/tree/develop/modules/dkim-module"));
		}
		return (DKIMModule) LOADED_MODULES.get(DKIMModule.class);
	}

	public static OutlookModule loadOutlookModule() {
		if (!LOADED_MODULES.containsKey(OutlookModule.class)) {
			LOADED_MODULES.put(OutlookModule.class, loadModule(
					"Outlook",
					"org.simplejavamail.internal.outlooksupport.converter.OutlookEmailConverter",
					"https://github.com/bbottema/simple-java-mail/tree/develop/modules/outlook-module"
			));
		}
		return (OutlookModule) LOADED_MODULES.get(OutlookModule.class);
	}

	public static SMIMEModule loadSMimeModule() {
		if (!LOADED_MODULES.containsKey(SMIMEModule.class)) {
			LOADED_MODULES.put(SMIMEModule.class, loadModule(
					"S/MIME",
					"org.simplejavamail.internal.smimesupport.SMIMEDecryptor",
					"https://github.com/bbottema/simple-java-mail/tree/develop/modules/smime-module"
			));
		}
		return (SMIMEModule) LOADED_MODULES.get(SMIMEModule.class);
	}

	public static boolean smimeModuleAvailable() {
		return MiscUtil.classAvailable("org.simplejavamail.internal.smimesupport.SMIMEDecryptor");
	}

	@SuppressWarnings("unchecked")
	private static <T> T loadModule(String moduleName, String moduleClass, String moduleHome) {
		try {
			if (!MiscUtil.classAvailable(moduleClass)) {
				throw new org.simplejavamail.internal.modules.ModuleLoaderException(format(org.simplejavamail.internal.modules.ModuleLoaderException.ERROR_MODULE_MISSING, moduleName, moduleHome));
			}
			return (T) Class.forName(moduleClass).newInstance();
		} catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
			throw new org.simplejavamail.internal.modules.ModuleLoaderException(format(org.simplejavamail.internal.modules.ModuleLoaderException.ERROR_LOADING_MODULE, moduleName), e);
		}
	}
}