package org.simplejavamail.internal.clisupport.therapijavadoc;

import com.github.therapi.runtimejavadoc.Link;
import com.github.therapi.runtimejavadoc.ParamJavadoc;
import com.github.therapi.runtimejavadoc.RuntimeJavadoc;
import com.github.therapi.runtimejavadoc.Value;
import org.bbottema.javareflection.ClassUtils;
import org.bbottema.javareflection.MethodUtils;
import org.simplejavamail.email.EmailBuilder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static java.lang.String.format;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;

public final class TherapiJavadocHelper {
	
//	private static final CommentFormatter COMMENT_FOR_CLI_FORMATTER = new CommentForCliFormatter();
//	private static final Pattern PATTERN_DELEGATES_TO = compile("(?i)Delegates to:?");
//
//	private static final Map<List<CommentElement>, Set<Method>> CACHED_METHOD_DELEGATES = new HashMap<>();
	
	private TherapiJavadocHelper() {
	}
	
//	/**
//	 * @return The Method referred to by the first {@code @link} if it occurs in the original javadoc.
//	 */
//	@Nonnull
//	public static Set<Method> getTryFindMethodDelegate(@Nullable final Comment comment) {
//		final List<CommentElement> commentElements = nullToEmpty(comment).getElements();
//
//		if (CACHED_METHOD_DELEGATES.containsKey(commentElements)) {
//			return CACHED_METHOD_DELEGATES.get(commentElements);
//		}
//
//		final Set<Method> methodDelegates = new HashSet<>();
//
//		boolean shouldProcessNextInlineLink = false;
//		for (CommentElement ele : commentElements) {
//			if (ele instanceof CommentText) {
//				if (PATTERN_DELEGATES_TO.matcher(((CommentText) ele).getValue()).find()) {
//					shouldProcessNextInlineLink = true;
//				}
//			} else if (shouldProcessNextInlineLink && ele instanceof InlineLink) {
//				methodDelegates.add(findMethodForLink(((InlineLink) ele).getLink(), true));
//			} else {
//				shouldProcessNextInlineLink = false;
//			}
//		}
//
//		return addDelegatesToCache(commentElements, methodDelegates);
//	}
	
//	private static Set<Method> addDelegatesToCache(List<CommentElement> commentElements, Set<Method> methodDelegates) {
//		CACHED_METHOD_DELEGATES.put(commentElements, methodDelegates);
//		return methodDelegates;
//	}
	
	@Nullable
	static Method findMethodForLink(Link link, boolean failOnMissing) {
		if (link.getReferencedMemberName() != null) {
			final Class<?> aClass = findClass(link.getReferencedClassName());
			if (aClass != null) { // else assume the class is irrelevant to CLI
				final Set<Method> matchingMethods = MethodUtils.findMatchingMethods(aClass, aClass, link.getReferencedMemberName(), link.getParams());
				assumeTrue(!failOnMissing || !matchingMethods.isEmpty(), format("Method %s not found on %s for @link: %s", link.getReferencedMemberName(), aClass, link));
				assumeTrue(!failOnMissing || matchingMethods.size() == 1, format("Multiple methods on %s match given @link's signature: %s", aClass, link));
				return matchingMethods.isEmpty() ? null : matchingMethods.iterator().next();
			}
		}
		return null;
	}

	@Nullable
	static Object resolveFieldForValue(Value value) {
		final Class<?> aClass = findClass(value.getReferencedClassName());
		if (aClass != null) { // else assume the class is irrelevant to CLI
			return ClassUtils.solveFieldValue(aClass, value.getReferencedMemberName());
		}
		return null;
	}

	private static Class<?> findClass(String referencedClassName) {
		Class<?> aClass = null;
		if (referencedClassName.endsWith(EmailBuilder.EmailBuilderInstance.class.getSimpleName())) {
			aClass = EmailBuilder.EmailBuilderInstance.class;
		}
		if (aClass == null) {
			aClass = ClassUtils.locateClass(referencedClassName, "org.simplejavamail", null);
		}
		if (aClass == null) {
			aClass = ClassUtils.locateClass(referencedClassName, null, null);
		}
		return aClass;
	}

	@Nonnull
	public static String getJavadoc(Method m, int nestingDepth) {
		return new JavadocForCliFormatter(nestingDepth)
				.format(RuntimeJavadoc.getJavadoc(m).getComment());
	}
	
	public static List<DocumentedMethodParam> getParamDescriptions(Method m) {
		List<ParamJavadoc> params = RuntimeJavadoc.getJavadoc(m).getParams();
		if (m.getParameterTypes().length != params.size()) {
			throw new AssertionError("Number of documented parameters doesn't match with Method's actual parameters: " + m);
		}
		List<DocumentedMethodParam> paramDescriptions = new ArrayList<>();
		for (ParamJavadoc param : params) {
			paramDescriptions.add(new DocumentedMethodParam(param.getName(), new JavadocForCliFormatter().format(param.getComment())));
		}
		return paramDescriptions;
	}
	
	public static class DocumentedMethodParam {
		@Nonnull private final String name;
		@Nonnull private final String javadoc;
		
		DocumentedMethodParam(@Nonnull String name, @Nonnull String javadoc) {
			this.name = name;
			this.javadoc = javadoc;
		}
		@Nonnull public String getName() { return name; }
		@Nonnull public String getJavadoc() { return javadoc; }
	}
}