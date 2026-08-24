package org.simplejavamail.internal.clisupport;

import org.simplejavamail.api.internal.clisupport.model.Cli;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fingerprints the builder API surface from which CLI options are generated.
 * Traversal includes reachable builder-node types, public method signatures, and CLI annotations in deterministic
 * order, allowing a cached option model to be rejected when its source contract changes across builds or JDKs.
 */
public final class CliSourceApiFingerprint {
	private CliSourceApiFingerprint() {
	}

	public static byte[] calculate() {
		return hashDescriptors(describeApiNodes(collectApiNodes()));
	}

	private static List<String> describeApiNodes(final Set<Class<?>> apiNodes) {
		final List<String> descriptors = new ArrayList<>();
		apiNodes.stream().sorted(Comparator.comparing(Class::getName)).forEach(type -> {
			final Cli.BuilderApiNode node = type.getAnnotation(Cli.BuilderApiNode.class);
			descriptors.add("type|" + type.getName() + "|" + (node == null ? "-" : node.builderApiType().name()));
			final Method[] methods = type.getDeclaredMethods();
			java.util.Arrays.sort(methods, Comparator.comparing(Method::toGenericString));
			for (final Method method : methods) {
				if (Modifier.isPublic(method.getModifiers())) {
					descriptors.add(describeMethod(method));
				}
			}
		});
		return descriptors;
	}

	private static byte[] hashDescriptors(final List<String> descriptors) {
		try {
			final MessageDigest digest = MessageDigest.getInstance("SHA-256");
			for (final String descriptor : descriptors) {
				digest.update(descriptor.getBytes(StandardCharsets.UTF_8));
				digest.update((byte) '\n');
			}
			return digest.digest();
		} catch (GeneralSecurityException e) {
			throw new IllegalStateException("SHA-256 unavailable", e);
		}
	}

	private static Set<Class<?>> collectApiNodes() {
		final ArrayDeque<Class<?>> pending = new ArrayDeque<>(List.of(CliBuilderApi.roots()));
		final Set<Class<?>> visited = new HashSet<>();
		while (!pending.isEmpty()) {
			final Class<?> type = pending.removeFirst();
			if (type == null || !isSimpleJavaMailType(type) || !visited.add(type)) {
				continue;
			}
			for (final Class<?> interfaceType : type.getInterfaces()) {
				pending.add(interfaceType);
			}
			if (type.getSuperclass() != null) {
				pending.add(type.getSuperclass());
			}
			for (final Method method : type.getDeclaredMethods()) {
				if (method.getReturnType().isAnnotationPresent(Cli.BuilderApiNode.class)) {
					pending.add(method.getReturnType());
				}
			}
		}
		return visited;
	}

	private static boolean isSimpleJavaMailType(final Class<?> type) {
		return type.getPackageName().startsWith("org.simplejavamail");
	}

	private static String describeMethod(final Method method) {
		final StringBuilder descriptor = new StringBuilder("method|").append(method.toGenericString());
		final Cli.ExcludeApi excluded = method.getAnnotation(Cli.ExcludeApi.class);
		final Cli.OptionNameOverride override = method.getAnnotation(Cli.OptionNameOverride.class);
		descriptor.append("|exclude=").append(excluded == null ? "-" : excluded.reason());
		descriptor.append("|name=").append(override == null ? "-" : override.value());
		for (final Annotation[] parameterAnnotations : method.getParameterAnnotations()) {
			boolean optional = false;
			for (final Annotation annotation : parameterAnnotations) {
				optional |= annotation.annotationType() == Cli.Optional.class;
			}
			descriptor.append("|optional=").append(optional);
		}
		return descriptor.toString();
	}
}
