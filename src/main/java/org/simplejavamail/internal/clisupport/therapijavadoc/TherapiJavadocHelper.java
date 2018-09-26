package org.simplejavamail.internal.clisupport.therapijavadoc;

import com.github.therapi.runtimejavadoc.Comment;
import com.github.therapi.runtimejavadoc.CommentElement;
import com.github.therapi.runtimejavadoc.CommentText;
import com.github.therapi.runtimejavadoc.InlineLink;
import com.github.therapi.runtimejavadoc.Link;
import org.bbottema.javareflection.ClassUtils;
import org.bbottema.javareflection.MethodUtils;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.Set;
import java.util.regex.Pattern;

import static java.lang.String.format;
import static java.util.regex.Pattern.compile;
import static java.util.regex.Pattern.quote;
import static org.simplejavamail.internal.util.Preconditions.assumeTrue;

public final class TherapiJavadocHelper {
	
	private static final String KEY_DELEGATES_TO = "Delegates to";
	private static final Pattern PATTERN_DELEGATES_TO = compile("(?i)" + quote(KEY_DELEGATES_TO));
	
	private TherapiJavadocHelper() {
	}
	
	@Nullable
	public static Method getMethodDelegate(Comment comment) {
		if (comment == null) {
			return null;
		}
		CommentElement lastEleTextSaysDelegatedTo = null;
		for (CommentElement ele : comment.getElements()) {
			if (ele instanceof CommentText) {
				if (PATTERN_DELEGATES_TO.matcher(((CommentText) ele).getValue()).find()) {
					lastEleTextSaysDelegatedTo = ele;
				}
			} else {
				if (ele instanceof InlineLink && lastEleTextSaysDelegatedTo != null) {
					return findMethodForLink(((InlineLink) ele).getLink());
				}
				
				lastEleTextSaysDelegatedTo = null;
			}
		}
		
		return null;
	}
	
	private static Method findMethodForLink(Link link) {
		Class<?> aClass = ClassUtils.locateClass(link.getReferencedClassName(), "org.simplejavamail", null);
		assumeTrue(aClass != null, "Class not found for @link: " + link);
		Set<Method> matchingMethods = MethodUtils.findMatchingMethods(aClass, link.getReferencedMemberName(), link.getParams());
		assumeTrue(!matchingMethods.isEmpty(), format("Method not found on %s for @link: %s", aClass, link));
		assumeTrue(matchingMethods.size() == 1, format("Multiple methods on %s match given @link's signature: %s", aClass, link));
		return matchingMethods.iterator().next();
	}
}