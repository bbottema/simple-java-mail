package org.simplejavamail.internal.clisupport;

import com.github.therapi.runtimejavadoc.MethodJavadoc;
import com.github.therapi.runtimejavadoc.RuntimeJavadoc;
import org.bbottema.javareflection.BeanUtils;
import org.bbottema.javareflection.BeanUtils.Visibility;
import org.bbottema.javareflection.MethodUtils;
import org.bbottema.javareflection.model.LookupMode;
import org.simplejavamail.internal.clisupport.annotation.CliExcludeApi;
import org.simplejavamail.internal.clisupport.annotation.CliOption;
import org.simplejavamail.internal.clisupport.annotation.CliOptionDescription;
import org.simplejavamail.internal.clisupport.annotation.CliOptionDescriptionDelegate;
import org.simplejavamail.internal.clisupport.annotation.CliOptionValue;
import org.simplejavamail.internal.clisupport.annotation.CliSupportedBuilderApi;
import org.simplejavamail.internal.clisupport.model.CliCommandType;
import org.simplejavamail.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.internal.clisupport.model.CliDeclaredOptionValue;
import org.simplejavamail.internal.clisupport.therapijavadoc.TherapiJavadocHelper;
import org.simplejavamail.internal.util.StringUtil.StringFormatter;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.EnumSet.allOf;
import static org.simplejavamail.internal.util.StringUtil.nStrings;
import static org.simplejavamail.internal.util.StringUtil.replaceNestedTokens;

final class BuilderApiToPicocliCommandsMapper {

	private BuilderApiToPicocliCommandsMapper() {
	}
	
	 static List<CliDeclaredOptionSpec> generateOptionsFromBuilderApi(Class<?>[] relevantBuilderRootApi) {
		 List<CliDeclaredOptionSpec> cliCommands = new ArrayList<>();
		Set<Class<?>> processedApiNodes = new HashSet<>();
		for (Class<?> apiRoot : relevantBuilderRootApi) {
			cliCommands.addAll(generateOptionsFromBuilderApi(apiRoot, processedApiNodes));
		}
		 Collections.sort(cliCommands);
		return cliCommands;
	}
	
	private static Collection<CliDeclaredOptionSpec> generateOptionsFromBuilderApi(Class<?> apiNode, Set<Class<?>> processedApiNodes) {
		List<CliDeclaredOptionSpec> cliOptions = new ArrayList<>();
		
		for (Method m : apiNode.getMethods()) { // note: only public methods are returned
			if (methodIsCliCompatible(m)) {
				// inspect method and use therapi-javadoc instead of our own annotations
				System.out.println(determineCliCommandName2(m));
			}
			if (m.isAnnotationPresent(CliOption.class)) {
				cliOptions.add(new CliDeclaredOptionSpec(
						determineCliCommandName(apiNode, m),
						determineCliCommandDescriptions(m, 0),
						getArgumentsForCliOption(m),
						determineApplicableRootCommands(apiNode, m),
						m));
				Class<?> potentialNestedApiNode = m.getReturnType();
				if (potentialNestedApiNode.isAnnotationPresent(CliSupportedBuilderApi.class) && !processedApiNodes.contains(potentialNestedApiNode)) {
					processedApiNodes.add(potentialNestedApiNode);
					cliOptions.addAll(generateOptionsFromBuilderApi(potentialNestedApiNode, processedApiNodes));
				}
			}
		}
		
		return cliOptions;
	}
	
	private static boolean methodIsCliCompatible(Method m) {
		if (!m.getDeclaringClass().isAnnotationPresent(CliSupportedBuilderApi.class) ||
				m.isAnnotationPresent(CliExcludeApi.class) ||
				BeanUtils.isBeanMethod(m, m.getDeclaringClass(), allOf(Visibility.class))) {
			return false;
		}
		@SuppressWarnings("unchecked")
		Class<String>[] stringParameters = new Class[m.getParameterTypes().length];
		Arrays.fill(stringParameters, String.class);
		return MethodUtils.isMethodCompatible(m, allOf(LookupMode.class), stringParameters);
	}
	
	private static Collection<CliCommandType> determineApplicableRootCommands(Class<?> apiNode, Method m) {
		CliSupportedBuilderApi cliSupportedBuilderApi = apiNode.getAnnotation(CliSupportedBuilderApi.class);
		CliOption cliOption = m.getAnnotation(CliOption.class);
		return cliOption.applicableRootCommands().length > 0
				? asList(cliOption.applicableRootCommands())
				: asList(cliSupportedBuilderApi.applicableRootCommands());
	}
	
	@Nonnull
	private static List<String> determineCliCommandDescriptions(Method m, int nestingDepth) {
		final String NESTED_DESCRIPTION_INDENT_STR = "  ";
		
		final List<String> declaredDescriptions = m.isAnnotationPresent(CliOption.class)
				? indentDescriptions(asList(m.getAnnotation(CliOption.class).description()), nestingDepth, NESTED_DESCRIPTION_INDENT_STR)
				: new ArrayList<String>();
		
		if (declaredDescriptions.isEmpty() && m.isAnnotationPresent(CliOptionDescription.class)) {
			declaredDescriptions.addAll(indentDescriptions(asList(m.getAnnotation(CliOptionDescription.class).value()), nestingDepth, NESTED_DESCRIPTION_INDENT_STR));
		}
		
		// check nested descriptions
		if (m.isAnnotationPresent(CliOptionDescriptionDelegate.class)) {
			CliOptionDescriptionDelegate delegate = m.getAnnotation(CliOptionDescriptionDelegate.class);
			CliSupportedBuilderApi apiNode = delegate.delegateClass().getAnnotation(CliSupportedBuilderApi.class);
			final Method deferredMethod = findDeferredMethod(delegate);
			
			final String INCLUSION_HEADER_PATTERN = deferredMethod.isAnnotationPresent(CliOption.class)
					? "\n%s@|underline INCLUDED FROM |@@|underline,cyan --%s:%s|@:"
					: "\n%s@|underline INCLUDED FROM JAVA BUILDER API [%s:%s(%s)]|@:";
			
			declaredDescriptions.add(format(INCLUSION_HEADER_PATTERN,
					nStrings(nestingDepth + 1, NESTED_DESCRIPTION_INDENT_STR),
					apiNode.builderApiType().getParamPrefix(),
					delegate.delegateMethod(),
					describeMethodParameterTypes(deferredMethod)));
			
			declaredDescriptions.addAll(determineCliCommandDescriptions(deferredMethod, nestingDepth + 1));
		}
		
		if (declaredDescriptions.isEmpty()) {
			throw new AssertionError("CliParam annotations missing description for method " + m);
		}
		
		return colorizeDescriptions(declaredDescriptions);
	}
	
	private static List<String> indentDescriptions(List<String> descriptions, int indents, @SuppressWarnings("SameParameterValue") String indentStr) {
		List<String> indentedDescriptions = new ArrayList<>();
		for (String description : descriptions) {
			indentedDescriptions.add(nStrings(indents, indentStr) + description);
		}
		return indentedDescriptions;
	}
	
	static List<String> colorizeDescriptions(List<String> descriptions) {
		final StringFormatter TOKEN_REPLACER = StringFormatter.formatterForPattern("@|cyan %s|@");
		
		List<String> colorizedDescriptions = new ArrayList<>();
		for (String description : descriptions) {
			String colorized = replaceNestedTokens(description, 0, "@|", "|@", "--[\\w:]*", TOKEN_REPLACER);
			colorizedDescriptions.add(colorized);
		}
		return colorizedDescriptions;
	}
	
	private static String describeMethodParameterTypes(Method deferredMethod) {
		final StringBuilder result = new StringBuilder();
		for (Class<?> parameterType : deferredMethod.getParameterTypes()) {
			result.append((result.length() == 0) ? "" : ", ").append(parameterType.getSimpleName());
		}
		return result.toString();
	}
	
	private static Method findDeferredMethod(CliOptionDescriptionDelegate cliOptionDescriptionDelegate) {
		try {
			return cliOptionDescriptionDelegate.delegateClass().getMethod(cliOptionDescriptionDelegate.delegateMethod(), cliOptionDescriptionDelegate.delegateParameters());
		} catch (NoSuchMethodException e) {
			throw new AssertionError("@CliOptionDescriptionDelegate configured incorrectly, method not found for: " + cliOptionDescriptionDelegate);
		}
	}
	
	private static String determineCliCommandName2(Method m) {
		final MethodJavadoc methodDoc = RuntimeJavadoc.getJavadoc(m);
		final Method methodDelegate = TherapiJavadocHelper.getTryFindMethodDelegate(methodDoc.getComment());
		String cliCommandName = m.getName();
		if (methodDelegate != null && m.getName().equals(methodDelegate.getName())) {
			cliCommandName += Math.random(); // FIXME name resolution
		}
		final String cliCommandPrefix = m.getDeclaringClass().getAnnotation(CliSupportedBuilderApi.class).builderApiType().getParamPrefix();
		return "--" + (!cliCommandPrefix.isEmpty() ? cliCommandPrefix + ":" : "") + cliCommandName;
	}
	
	@Deprecated
	private static String determineCliCommandName(Class<?> apiNode, Method m) {
		String cliCommandPrefix = apiNode.getAnnotation(CliSupportedBuilderApi.class).builderApiType().getParamPrefix();
		String cliCommandNameOverride = m.getAnnotation(CliOption.class).nameOverride();
		String effectiveCommandName = cliCommandNameOverride.isEmpty() ? m.getName() : cliCommandNameOverride;
		return "--" + (!cliCommandPrefix.isEmpty() ? cliCommandPrefix + ":" : "") + effectiveCommandName;
	}
	
	private static List<CliDeclaredOptionValue> getArgumentsForCliOption(Method m) {
		final List<CliDeclaredOptionValue> cliParams = new ArrayList<>();
		final Annotation[][] annotations = m.getParameterAnnotations();
		final Class<?>[] parameterTypes = m.getParameterTypes();
		for (int i = 0; i < parameterTypes.length; i++) {
			Class<?> p = parameterTypes[i];
			CliOptionValue pa = findCliParamAnnotation(annotations[i], CliOptionValue.class, m);
			cliParams.add(new CliDeclaredOptionValue(p, determineCliParamName(pa, p), pa.helpLabel(), pa.description(), pa.required(), pa.example()));
		}
		return cliParams;
	}
	
	@SuppressWarnings({"unchecked", "SameParameterValue"})
	private static <T extends Annotation> T findCliParamAnnotation(@Nonnull Annotation[] a, @Nonnull Class<T> annotationToFind, @Nonnull Method m) {
		for (Annotation annotation : a) {
			if (annotationToFind.isAssignableFrom(annotation.getClass())) {
				return (T) annotation;
			}
		}
		throw new AssertionError(format("CliOption for method \"%s\" missing @CliOptionValue annotation for method param: \n\t %s", m.getName(), m));
	}
	
	private static String determineCliParamName(CliOptionValue cliOptionValueAnnotation, Class<?> cliParamType) {
		return !cliOptionValueAnnotation.name().isEmpty() ? cliOptionValueAnnotation.name() : cliParamType.getSimpleName();
	}
}
