package org.simplejavamail.internal.clisupport;

import org.simplejavamail.internal.clisupport.annotation.CliCommand;
import org.simplejavamail.internal.clisupport.annotation.CliOption;
import org.simplejavamail.internal.clisupport.annotation.CliOptionDelegate;
import org.simplejavamail.internal.clisupport.annotation.CliOptionValue;
import org.simplejavamail.internal.clisupport.annotation.CliSupported;
import org.simplejavamail.internal.clisupport.model.CliOptionData;
import org.simplejavamail.internal.clisupport.model.CliOptionValueData;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static java.lang.String.format;
import static java.util.Arrays.asList;

final class BuilderApiToCliCommandsMapper {
	
	private BuilderApiToCliCommandsMapper() {
	}
	
	 static TreeSet<CliOptionData> generateCommandsAndSubcommands(Class<?>[] relevantBuilderRootApi, Map<Class<?>, Collection<CliOptionData>> optionsMappedForClass) {
		TreeSet<CliOptionData> cliCommands = new TreeSet<>();
		for (Class<?> apiRoot : relevantBuilderRootApi) {
			cliCommands.addAll(generateCommandsAndSubcommands(apiRoot, optionsMappedForClass));
		}
		return cliCommands;
	}
	
	private static Collection<CliOptionData> generateCommandsAndSubcommands(Class<?> apiNode, Map<Class<?>, Collection<CliOptionData>> optionsList) {
		List<CliOptionData> cliOptions = new ArrayList<>();
		
		for (Method m : apiNode.getMethods()) {
			if (m.isAnnotationPresent(CliOption.class)) {
				cliOptions.add(new CliOptionData(
						determineCliCommandName(apiNode, m),
						determineCliCommandDescriptions(m),
						getArgumentsForCliOption(m),
						determineApplicableRootCommands(apiNode, m),
						m));
			}
		}
		
		return cliOptions;
	}
	
	private static Collection<CliCommand> determineApplicableRootCommands(Class<?> apiNode, Method m) {
		CliSupported cliSupportedApiNode = apiNode.getAnnotation(CliSupported.class);
		CliOption cliOption = m.getAnnotation(CliOption.class);
		return cliOption.applicableRootCommands().length > 0
				? asList(cliOption.applicableRootCommands())
				: asList(cliSupportedApiNode.applicableRootCommands());
	}
	
	@Nonnull
	private static List<String> determineCliCommandDescriptions(Method m) {
		List<String> declaredDescriptions = new ArrayList<>(asList(m.getAnnotation(CliOption.class).description()));
		
		if (m.isAnnotationPresent(CliOptionDelegate.class)) {
			CliOptionDelegate delegate = m.getAnnotation(CliOptionDelegate.class);
			CliSupported apiNode = delegate.delegateClass().getAnnotation(CliSupported.class);
			declaredDescriptions.add(format("\n@|underline INCLUDED FROM |@@|underline,cyan --%s:%s|@:", apiNode.paramPrefix(), delegate.delegateMethod()));
			declaredDescriptions.addAll(determineCliCommandDescriptions(findDeferredMethod(delegate)));
		}
		
		return declaredDescriptions;
	}
	
	private static Method findDeferredMethod(CliOptionDelegate cliOptionDelegate) {
		try {
			return cliOptionDelegate.delegateClass().getMethod(cliOptionDelegate.delegateMethod(), cliOptionDelegate.delegateParameters());
		} catch (NoSuchMethodException e) {
			throw new AssertionError("@CliOptionDelegate configured incorrectly, method not found for: " + cliOptionDelegate);
		}
	}
	
	@Nonnull
	private static String determineCliCommandName(Class<?> apiNode, Method m) {
		String cliCommandPrefix = apiNode.getAnnotation(CliSupported.class).paramPrefix();
		String cliCommandNameOverride = m.getAnnotation(CliOption.class).nameOverride();
		String effectiveCommandName = cliCommandNameOverride.isEmpty() ? m.getName() : cliCommandNameOverride;
		return "--" + (!cliCommandPrefix.isEmpty() ? cliCommandPrefix + ":" : "") + effectiveCommandName;
	}
	
	@Nonnull
	private static List<CliOptionValueData> getArgumentsForCliOption(Method m) {
		final List<CliOptionValueData> cliParams = new ArrayList<>();
		final Annotation[][] annotations = m.getParameterAnnotations();
		final Class<?>[] parameterTypes = m.getParameterTypes();
		for (int i = 0; i < parameterTypes.length; i++) {
			Class<?> p = parameterTypes[i];
			CliOptionValue pa = findCliParamAnnotation(annotations[i], CliOptionValue.class, m.getName());
			cliParams.add(new CliOptionValueData(p, determineCliParamName(pa, p), pa.helpLabel(), pa.description(), pa.required(), pa.example()));
		}
		return cliParams;
	}
	
	@SuppressWarnings({"unchecked", "SameParameterValue"})
	private static <T extends Annotation> T findCliParamAnnotation(@Nonnull Annotation[] a, @Nonnull Class<T> annotationToFind, @Nonnull String methodName) {
		for (Annotation annotation : a) {
			if (annotationToFind.isAssignableFrom(annotation.getClass())) {
				return (T) annotation;
			}
		}
		throw new AssertionError(format("CliOption for method %s missing @CliOptionValue annotation for method param", methodName));
	}
	
	@Nonnull
	private static String determineCliParamName(CliOptionValue cliOptionValueAnnotation, Class<?> cliParamType) {
		return !cliOptionValueAnnotation.name().isEmpty() ? cliOptionValueAnnotation.name() : cliParamType.getSimpleName();
	}
}
