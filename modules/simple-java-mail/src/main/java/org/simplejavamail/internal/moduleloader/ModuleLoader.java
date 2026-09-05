package org.simplejavamail.internal.moduleloader;

import org.simplejavamail.internal.modules.AuthenticatedSocksModule;
import org.simplejavamail.internal.modules.BatchModule;
import org.simplejavamail.internal.modules.DKIMModule;
import org.simplejavamail.internal.modules.OutlookModule;
import org.simplejavamail.internal.modules.SMIMEModule;
import org.simplejavamail.internal.util.MiscUtil;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.lang.String.format;

public class ModuleLoader {

	private static final boolean BATCH_SUPPORT_CLASS_AVAILABLE = MiscUtil.classAvailable("org.simplejavamail.internal.batchsupport.BatchSupport");
	private static final boolean SMIME_SUPPORT_CLASS_AVAILABLE = MiscUtil.classAvailable("org.simplejavamail.internal.smimesupport.SMIMESupport");
	private static final boolean DKIM_SUPPORT_CLASS_AVAILABLE = MiscUtil.classAvailable("org.simplejavamail.internal.dkimsupport.DKIMSigner");

	private static final ConcurrentMap<Class<?>, Object> LOADED_MODULES = new ConcurrentHashMap<>();

	// used from junit tests
	private static final Set<Class> FORCED_DISABLED_MODULES = new HashSet<>();
	private static final Set<Class> FORCED_RECHECK_MODULES = new HashSet<>();

	public static AuthenticatedSocksModule loadAuthenticatedSocksModule() {
		return loadModule(
					AuthenticatedSocksModule.class,
					"Authenticated Socks",
					"org.simplejavamail.internal.authenticatedsockssupport.AuthenticatedSocksHelper",
					"https://github.com/bbottema/simple-java-mail/tree/develop/modules/authenticated-socks-module");
	}
	
	public static DKIMModule loadDKIMModule() {
		return loadModule(
					DKIMModule.class,
					"DKIM",
					"org.simplejavamail.internal.dkimsupport.DKIMSigner",
					"https://github.com/bbottema/simple-java-mail/tree/develop/modules/dkim-module");
	}

	public static OutlookModule loadOutlookModule() {
		return loadModule(
					OutlookModule.class,
					"Outlook",
					"org.simplejavamail.internal.outlooksupport.converter.OutlookEmailConverter",
					"https://github.com/bbottema/simple-java-mail/tree/develop/modules/outlook-module"
			);
	}

	public static SMIMEModule loadSmimeModule() {
		return loadModule(
					SMIMEModule.class,
					"S/MIME",
					"org.simplejavamail.internal.smimesupport.SMIMESupport",
					"https://github.com/bbottema/simple-java-mail/tree/develop/modules/smime-module"
			);
	}

	public static BatchModule loadBatchModule() {
		if (FORCED_DISABLED_MODULES.contains(BatchModule.class)) {
			throw new IllegalStateException("BatchModule forcefully disabled");
		}
		return loadModule(
					BatchModule.class,
					"Batch",
					"org.simplejavamail.internal.batchsupport.BatchSupport",
					"https://github.com/bbottema/simple-java-mail/tree/develop/modules/batch-module"
			);
	}

	public static boolean batchModuleAvailable() {
		return !FORCED_DISABLED_MODULES.contains(BatchModule.class) &&
				((FORCED_RECHECK_MODULES.contains(BatchModule.class) &&
						MiscUtil.classAvailable("org.simplejavamail.internal.batchsupport.BatchSupport")) ||
						BATCH_SUPPORT_CLASS_AVAILABLE);
	}

	public static boolean smimeModuleAvailable() {
		return !FORCED_DISABLED_MODULES.contains(SMIMEModule.class) &&
				((FORCED_RECHECK_MODULES.contains(SMIMEModule.class) &&
						MiscUtil.classAvailable("org.simplejavamail.internal.smimesupport.SMIMESupport")) ||
						SMIME_SUPPORT_CLASS_AVAILABLE);
	}

	public static boolean dkimModuleAvailable() {
		return !FORCED_DISABLED_MODULES.contains(DKIMModule.class) &&
				((FORCED_RECHECK_MODULES.contains(DKIMModule.class) &&
						MiscUtil.classAvailable("org.simplejavamail.internal.dkimsupport.DKIMSigner")) ||
						DKIM_SUPPORT_CLASS_AVAILABLE);
	}

	private static <T> T loadModule(Class<T> moduleClass, String moduleName, String moduleImplClassName, String moduleHome) {
		return moduleClass.cast(LOADED_MODULES.computeIfAbsent(moduleClass,
				ignored -> instantiateModule(moduleClass, moduleName, moduleImplClassName, moduleHome)));
	}

	private static Object instantiateModule(Class<?> moduleClass, String moduleName, String moduleImplClassName, String moduleHome) {
		try {
			if (FORCED_DISABLED_MODULES.contains(moduleClass)) {
				throw new IllegalAccessException("Module is forcefully disabled");
			}
			if (!MiscUtil.classAvailable(moduleImplClassName)) {
				throw new ModuleLoaderException(format(ModuleLoaderException.ERROR_MODULE_MISSING, moduleName, moduleHome));
			}
			return Class.forName(moduleImplClassName).newInstance();
		} catch (ClassNotFoundException | IllegalAccessException | InstantiationException e) {
			throw new ModuleLoaderException(format(ModuleLoaderException.ERROR_LOADING_MODULE, moduleName), e);
		}
	}

	// used from junit tests (using reflection, because it's invisible in the core-module)
	@SuppressWarnings("unused")
	public static void _forceDisableBatchModule() {
		FORCED_DISABLED_MODULES.add(BatchModule.class);
	}

	// used from junit tests (using reflection, because it's invisible in the core-module)
	@SuppressWarnings("unused")
	public static void _forceRecheckModule() {
		FORCED_RECHECK_MODULES.add(BatchModule.class);
		FORCED_RECHECK_MODULES.add(SMIMEModule.class);
	}
}
