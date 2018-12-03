package org.simplejavamail.internal.modules;

import org.simplejavamail.internal.util.MiscUtil;

import java.util.HashMap;
import java.util.Map;

import static java.lang.String.format;

public class ModuleLoader {
	
	private static Map<Class, Object> LOADED_MODULES = new HashMap<>();
	
	public static void clearLoadedModules() {
		LOADED_MODULES.clear();
	}
	
	public static DKIMModule loadDKIMModule() {
		if (!LOADED_MODULES.containsKey(DKIMModule.class)) {
			LOADED_MODULES.put(DKIMModule.class, loadModule(
					"DKIM",
					"org.simplejavamail.internal.modules.DKIMModuleMarker",
					"org.simplejavamail.converter.internal.mimemessage.DKIMSigner",
					"https://github.com/simple-java-mail/dkim-module"
			));
		}
		return (DKIMModule) LOADED_MODULES.get(DKIMModule.class);
	}
	
	public static CLIModule loadCliModule() {
		if (!LOADED_MODULES.containsKey(CLIModule.class)) {
			LOADED_MODULES.put(CLIModule.class, loadModule(
					"CLI",
					"org.simplejavamail.internal.modules.CliModuleMarker",
					"org.simplejavamail.internal.clisupport.CliSupport",
					"https://github.com/simple-java-mail/cli-module"));
		}
		return (CLIModule) LOADED_MODULES.get(CLIModule.class);
	}
	
	public static OutlookModule loadOutlookModule() {
		if (!LOADED_MODULES.containsKey(OutlookModule.class)) {
			LOADED_MODULES.put(OutlookModule.class, loadModule(
					"Outlook",
					"org.simplejavamail.internal.modules.OutlookModuleMarker",
					"org.simplejavamail.converter.internal.outlook.OutlookEmailConverter",
					"https://github.com/simple-java-mail/outlook-module"
			));
		}
		return (OutlookModule) LOADED_MODULES.get(OutlookModule.class);
	}
	
	@SuppressWarnings("unchecked")
	public static <T> T loadModule(String moduleName, String modulePrerequisiteClass, String moduleClass, String moduleHome) {
		try {
			if (!MiscUtil.classAvailable(modulePrerequisiteClass)) {
				throw new ModuleLoaderException(format(ModuleLoaderException.ERROR_MODULE_MISSING, moduleName, moduleHome));
			}
			return (T) Class.forName(moduleClass).newInstance();
		} catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
			throw new ModuleLoaderException(format(ModuleLoaderException.ERROR_LOADING_MODULE, moduleName), e);
		}
	}
}