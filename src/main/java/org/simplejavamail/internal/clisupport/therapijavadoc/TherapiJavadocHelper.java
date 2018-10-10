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

public final class TherapiJavadocHelper {
	
	private TherapiJavadocHelper() {
	}
	
	@Nullable
	static Method findMethodForLink(Link link) {
		if (link.getReferencedMemberName() != null) {
			final Class<?> aClass = findClass(link.getReferencedClassName());
			if (aClass != null) { // else assume the class is irrelevant to CLI
				final Set<Method> matchingMethods = MethodUtils.findMatchingMethods(aClass, aClass, link.getReferencedMemberName(), link.getParams());
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