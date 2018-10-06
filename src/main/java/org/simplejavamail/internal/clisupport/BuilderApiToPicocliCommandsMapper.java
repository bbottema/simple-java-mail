package org.simplejavamail.internal.clisupport;

import org.bbottema.javareflection.BeanUtils;
import org.bbottema.javareflection.BeanUtils.Visibility;
import org.bbottema.javareflection.ClassUtils;
import org.bbottema.javareflection.MethodUtils;
import org.bbottema.javareflection.model.LookupMode;
import org.bbottema.javareflection.model.MethodModifier;
import org.bbottema.javareflection.valueconverter.ValueConversionHelper;
import org.simplejavamail.internal.clisupport.annotation.CliExcludeApi;
import org.simplejavamail.internal.clisupport.annotation.CliOptionNameOverride;
import org.simplejavamail.internal.clisupport.annotation.CliSupportedBuilderApi;
import org.simplejavamail.internal.clisupport.model.CliCommandType;
import org.simplejavamail.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.internal.clisupport.model.CliDeclaredOptionValue;
import org.simplejavamail.internal.clisupport.therapijavadoc.TherapiJavadocHelper;
import org.simplejavamail.internal.clisupport.therapijavadoc.TherapiJavadocHelper.DocumentedMethodParam;
import org.simplejavamail.internal.clisupport.valueinterpreters.StringToMimeMessageFunction;
import org.simplejavamail.internal.util.StringUtil;
import org.simplejavamail.internal.util.StringUtil.StringFormatter;
import org.slf4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.mail.internet.MimeMessage;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.lang.String.format;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static java.util.EnumSet.allOf;
import static java.util.EnumSet.of;
import static org.bbottema.javareflection.TypeUtils.containsAnnotation;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;
import static org.simplejavamail.internal.util.Preconditions.checkNonEmptyArgument;
import static org.simplejavamail.internal.util.StringUtil.nStrings;
import static org.simplejavamail.internal.util.StringUtil.replaceNestedTokens;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * FIXME:
 * <pre>
 *  Method not CLI compatible: EmailPopulatingBuilder.withAttachment([class java.lang.String, class [B, class java.lang.String])
 *  Method not CLI compatible: EmailPopulatingBuilder.withEmbeddedImage([class java.lang.String, interface javax.activation.DataSource])
 *  Method not CLI compatible: EmailPopulatingBuilder.signWithDomainKey([class java.io.InputStream, class java.lang.String, class java.lang.String])
 *  Method not CLI compatible: MailerRegularBuilder.withTransportStrategy([class org.simplejavamail.mailer.config.TransportStrategy])
 * </pre>
 */
public final class BuilderApiToPicocliCommandsMapper {

	private static final Logger LOGGER = getLogger(BuilderApiToPicocliCommandsMapper.class);
	private static final Map<Class<?>, String> TYPE_LABELS = new HashMap<>();
	
	static {
		TYPE_LABELS.put(boolean.class, "BOOL");
		TYPE_LABELS.put(Boolean.class, "BOOL");
		TYPE_LABELS.put(String.class, "TEXT");
		TYPE_LABELS.put(Object.class, "TEXT");
		TYPE_LABELS.put(int.class, "NUM");
		TYPE_LABELS.put(Integer.class, "NUM");
		TYPE_LABELS.put(MimeMessage.class, "FILE");
		
		ValueConversionHelper.registerValueConverter(new StringToMimeMessageFunction());
	}
	
	private BuilderApiToPicocliCommandsMapper() {
	}
	
	@Nonnull
	static List<CliDeclaredOptionSpec> generateOptionsFromBuilderApi(Class<?>[] relevantBuilderRootApi) {
		List<CliDeclaredOptionSpec> cliCommands = new ArrayList<>();
		Set<Class<?>> processedApiNodes = new HashSet<>();
		for (Class<?> apiRoot : relevantBuilderRootApi) {
			cliCommands.addAll(generateOptionsFromBuilderApiChain(apiRoot, processedApiNodes));
		}
		Collections.sort(cliCommands);
		return cliCommands;
	}
	
	@Nonnull
	private static Collection<CliDeclaredOptionSpec> generateOptionsFromBuilderApiChain(Class<?> apiNode, Set<Class<?>> processedApiNodes) {
		List<CliDeclaredOptionSpec> cliOptions = new ArrayList<>();

		Class<?> apiNodeChainClass = apiNode;
		while (apiNodeChainClass.getPackage().getName().contains("org.simplejavamail")) {
			cliOptions.addAll(generateOptionsFromBuilderApi(apiNodeChainClass, processedApiNodes));
			apiNodeChainClass = apiNodeChainClass.getSuperclass();
		}

		return cliOptions;
	}
	
	@Nonnull
	private static Collection<CliDeclaredOptionSpec> generateOptionsFromBuilderApi(Class<?> apiNode, Set<Class<?>> processedApiNodes) {
		List<CliDeclaredOptionSpec> cliOptions = new ArrayList<>();

		processedApiNodes.add(apiNode);

		for (Method m : ClassUtils.collectMethods(apiNode, apiNode, of(MethodModifier.PUBLIC))) {
			if (methodIsCliCompatible(m)) {
				final String optionName = determineCliOptionName(apiNode, m);
				LOGGER.debug("option {} found for {}.{}({})", optionName, apiNode.getSimpleName(), m.getName(), m.getParameterTypes());
				
				// assertion check
				for (CliDeclaredOptionSpec knownOption : cliOptions) {
					if (knownOption.getName().equals(optionName)) {
						String msg = "@CliOptionNameOverride needed one of the following two methods:\n\t%s\n\t%s\n\t----------";
						throw new AssertionError(format(msg, knownOption.getSourceMethod(), m));
					}
				}
				
				cliOptions.add(new CliDeclaredOptionSpec(
						optionName,
						determineCliOptionDescriptions(m, 0),
						getArgumentsForCliOption(m),
						determineApplicableRootCommands(apiNode, m),
						m));
				Class<?> potentialNestedApiNode = m.getReturnType();
				if (potentialNestedApiNode.isAnnotationPresent(CliSupportedBuilderApi.class) && !processedApiNodes.contains(potentialNestedApiNode)) {
					cliOptions.addAll(generateOptionsFromBuilderApiChain(potentialNestedApiNode, processedApiNodes));
				}
			} else {
				LOGGER.debug("Method not CLI compatible: {}.{}({})", apiNode.getSimpleName(), m.getName(), Arrays.toString(m.getParameterTypes()));
			}
		}
		
		return cliOptions;
	}

	public static boolean methodIsCliCompatible(Method m) {
		if (!m.getDeclaringClass().isAnnotationPresent(CliSupportedBuilderApi.class) ||
				m.isAnnotationPresent(CliExcludeApi.class) ||
				BeanUtils.isBeanMethod(m, m.getDeclaringClass(), allOf(Visibility.class)) ||
				MethodUtils.methodHasCollectionParameter(m)) {
			return false;
		}
		@SuppressWarnings("unchecked")
		Class<String>[] stringParameters = new Class[m.getParameterTypes().length];
		Arrays.fill(stringParameters, String.class);
		return MethodUtils.isMethodCompatible(m, allOf(LookupMode.class), stringParameters);
	}
	
	@Nonnull
	private static Collection<CliCommandType> determineApplicableRootCommands(Class<?> apiNode, Method m) {
		CliSupportedBuilderApi cliSupportedBuilderApi = apiNode.getAnnotation(CliSupportedBuilderApi.class);
		return asList(cliSupportedBuilderApi.applicableRootCommands());
	}
	
	@Nonnull
	private static List<String> determineCliOptionDescriptions(Method m, int nestingDepth) {
		final String NESTED_DESCRIPTION_INDENT_STR = "  ";
		
		final List<String> declaredDescriptions = new ArrayList<>(singletonList(TherapiJavadocHelper.getJavadoc(m, nestingDepth)));
		
//		final List<String> declaredDescriptions = m.isAnnotationPresent(CliOption.class)
//				? indentDescriptions(asList(m.getAnnotation(CliOption.class).description()), nestingDepth, NESTED_DESCRIPTION_INDENT_STR)
//				: new ArrayList<String>();
//
//		if (declaredDescriptions.isEmpty() && m.isAnnotationPresent(CliOptionDescription.class)) {
//			declaredDescriptions.addAll(indentDescriptions(asList(m.getAnnotation(CliOptionDescription.class).value()), nestingDepth, NESTED_DESCRIPTION_INDENT_STR));
//		}
		
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
			
			declaredDescriptions.addAll(determineCliOptionDescriptions(deferredMethod, nestingDepth + 1));
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
	
	@Nonnull
	static List<String> colorizeDescriptions(List<String> descriptions) {
		final StringFormatter TOKEN_REPLACER = StringFormatter.formatterForPattern("@|cyan %s|@");
		
		List<String> colorizedDescriptions = new ArrayList<>();
		for (String description : descriptions) {
			String colorized = StringUtil.replaceNestedTokens(description, 0, "@|", "|@", "--[\\w:]*", TOKEN_REPLACER);
			colorizedDescriptions.add(colorized);
		}
		return colorizedDescriptions;
	}
	
	@Nonnull
	public static String determineCliOptionName(Class<?> apiNode, Method m) {
		String methodName = m.isAnnotationPresent(CliOptionNameOverride.class)
				? m.getAnnotation(CliOptionNameOverride.class).value()
				: m.getName();

		final String cliCommandPrefix = apiNode.getAnnotation(CliSupportedBuilderApi.class).builderApiType().getParamPrefix();
		assumeTrue(!cliCommandPrefix.isEmpty(), "Option prefix missing from API class");
		return format("--%s:%s", cliCommandPrefix, methodName);
	}
	
	@Nonnull
	private static List<CliDeclaredOptionValue> getArgumentsForCliOption(Method m) {
		final Annotation[][] annotations = m.getParameterAnnotations();
		final Class<?>[] declaredParameters = m.getParameterTypes();
		final List<DocumentedMethodParam> documentedParameters = TherapiJavadocHelper.getParamDescriptions(m);

		final List<CliDeclaredOptionValue> cliParams = new ArrayList<>();
		
		for (int i = 0; i < declaredParameters.length; i++) {
			final Class<?> p = declaredParameters[i];
			final DocumentedMethodParam dP = documentedParameters.get(i);
			final boolean required = containsAnnotation(asList(annotations[i]), Nullable.class);
			// FIXME extract examples from javadoc
			cliParams.add(new CliDeclaredOptionValue(p, dP.getName(), determineTypeLabel(p), dP.getJavadoc(), required, new String[0]));
		}
		
		return cliParams;
	}
	
	@Nonnull
	private static String determineTypeLabel(Class<?> type) {
		return checkNonEmptyArgument(TYPE_LABELS.get(type), "Missing type label for type " + type);
	}
}
