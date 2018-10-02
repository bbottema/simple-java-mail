package org.simplejavamail.internal.clisupport.therapijavadoc;

import com.github.therapi.runtimejavadoc.Comment;
import com.github.therapi.runtimejavadoc.CommentElement;
import com.github.therapi.runtimejavadoc.CommentFormatter;
import com.github.therapi.runtimejavadoc.CommentText;
import com.github.therapi.runtimejavadoc.InlineLink;
import com.github.therapi.runtimejavadoc.Link;
import com.github.therapi.runtimejavadoc.ParamJavadoc;
import com.github.therapi.runtimejavadoc.RuntimeJavadoc;
import org.bbottema.javareflection.ClassUtils;
import org.bbottema.javareflection.MethodUtils;
import org.simplejavamail.email.EmailBuilder;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.github.therapi.runtimejavadoc.Comment.nullToEmpty;
import static java.lang.String.format;
import static java.util.regex.Pattern.compile;
import static java.util.regex.Pattern.quote;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;

public final class TherapiJavadocHelper {
	
	private static final CommentFormatter COMMENT_FOR_CLI_FORMATTER = new CommentForCliFormatter();
	private static final String KEY_DELEGATES_TO = "Delegates to";
	private static final Pattern PATTERN_DELEGATES_TO = compile("(?i)" + quote(KEY_DELEGATES_TO));
	
	private static final Map<List<CommentElement>, Method> CACHED_METHOD_DELEGATES = new HashMap<>();
	
	private TherapiJavadocHelper() {
	}
	
	/**
	 * @return The Method referred to by the first {@code @link} if it occurs in the original javadoc.
	 */
	@Nullable
	public static Method getTryFindMethodDelegate(@Nullable final Comment comment) {
		final List<CommentElement> commentElements = nullToEmpty(comment).getElements();
		
		if (CACHED_METHOD_DELEGATES.containsKey(commentElements)) {
			return CACHED_METHOD_DELEGATES.get(commentElements);
		}
		
		CommentElement lastEleTextSaysDelegatedTo = null;
		for (CommentElement ele : commentElements) {
			if (ele instanceof CommentText) {
				if (PATTERN_DELEGATES_TO.matcher(((CommentText) ele).getValue()).find()) {
					lastEleTextSaysDelegatedTo = ele;
				}
			} else {
				if (ele instanceof InlineLink && lastEleTextSaysDelegatedTo != null) {
					return addDelegateToCache(commentElements, findMethodForLink(((InlineLink) ele).getLink(), true));
				}
				
				lastEleTextSaysDelegatedTo = null;
			}
		}
		
		return null;
	}
	
	private static Method addDelegateToCache(List<CommentElement> commentElements, Method methodForLink) {
		CACHED_METHOD_DELEGATES.put(commentElements, methodForLink);
		return methodForLink;
	}
	
	@Nullable
	static Method findMethodForLink(Link link, boolean failOnMissing) {
		if (link.getReferencedMemberName() != null) {
			final Class<?> aClass = findClass(link);
			assumeTrue(aClass != null, "Class not found for @link: " + link);
			final Set<Method> matchingMethods = MethodUtils.findMatchingMethods(aClass, aClass, link.getReferencedMemberName(), link.getParams());
			assumeTrue(!failOnMissing || !matchingMethods.isEmpty(), format("Method %s not found on %s for @link: %s", link.getReferencedMemberName(), aClass, link));
			assumeTrue(!failOnMissing || matchingMethods.size() == 1, format("Multiple methods on %s match given @link's signature: %s", aClass, link));
			return matchingMethods.isEmpty() ? null : matchingMethods.iterator().next();
		}
		return null;
	}

	private static Class<?> findClass(Link link) {
		Class<?> aClass = null;
		if (link.getReferencedClassName().endsWith(EmailBuilder.EmailBuilderInstance.class.getSimpleName())) {
			aClass = EmailBuilder.EmailBuilderInstance.class;
		}
		if (aClass == null) {
			aClass = ClassUtils.locateClass(link.getReferencedClassName(), "org.simplejavamail", null);
		}
		if (aClass == null) {
			aClass = ClassUtils.locateClass(link.getReferencedClassName(), null, null);
		}
		return aClass;
	}

	public static String getJavadoc(Method m) {
		return COMMENT_FOR_CLI_FORMATTER.format(RuntimeJavadoc.getJavadoc(m).getComment());
	}
	
	public static List<DocumentedMethodParam> getParamDescriptions(Method m) {
		List<ParamJavadoc> params = RuntimeJavadoc.getJavadoc(m).getParams();
		if (m.getParameterTypes().length != params.size()) {
			throw new AssertionError("Number of documented parameters doesn't match with Method's actual parameters: " + m);
		}
		List<DocumentedMethodParam> paramDescriptions = new ArrayList<>();
		for (ParamJavadoc param : params) {
			paramDescriptions.add(new DocumentedMethodParam(param.getName(), COMMENT_FOR_CLI_FORMATTER.format(param.getComment())));
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