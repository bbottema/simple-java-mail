package org.simplejavamail.mailer.internal;

import org.simplejavamail.api.SimpleJavaMail;
import org.simplejavamail.api.converter.ConfiguredEmailConverter;
import org.simplejavamail.api.email.EmailProperty;
import org.simplejavamail.api.email.EmailStartingBuilder;
import org.simplejavamail.api.email.ExactEmailBuilder;
import org.simplejavamail.api.mailer.config.TransportStrategy;
import org.simplejavamail.config.ConfigLoader;
import testutil.configcontracts.ConfigurationContractCoverage;
import testutil.configcontracts.ConfigurationContractVerifier.Obligation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** Independent required sets. Successful test claims never define the denominator. */
final class ConfigurationContractInventory {
    static final String MAIN = "simple-java-mail";
    private static final List<Class<?>> EXTRA_ROOTS = List.of(ConfigLoader.class, SimpleJavaMail.class,
            EmailStartingBuilder.class, ExactEmailBuilder.class, ConfiguredEmailConverter.class);

    private ConfigurationContractInventory() { }

    static List<Obligation> required(final String scope) throws Exception {
        if (!scope.equals("library") && !scope.equals("full")) { throw new IllegalArgumentException("Unknown contract gate scope: " + scope); }
        verifyMembership();
        final List<Obligation> result = new ArrayList<>();
        final Map<String, String> api = apiOwners();
        for (Map.Entry<String, String> route : api.entrySet()) {
            result.add(new Obligation("api", route.getKey(), route.getValue().equals("STANDALONE_BATCH") ? "batch-module" : MAIN));
        }
        for (ConfigLoader.Property property : ConfigLoader.Property.values()) { result.add(new Obligation("property", property.key(), MAIN)); }
        for (String alias : ConnectionCoverageCatalogue.ACCESS.keySet()) { result.add(new Obligation("alias", alias, MAIN)); }
        for (EmailProperty property : EmailProperty.values()) { result.add(new Obligation("field", "email." + property.name(), MAIN)); }
        for (String[] row : resourceRows("/configuration-contract-obligations.tsv", 4)) {
            if (!row[3].equals("library") && !row[3].equals("full")) {
                throw new IllegalStateException("Unknown obligation scope: " + row[3]);
            }
            if (scope.equals("full") || row[3].equals("library")) { result.add(new Obligation(row[0], row[1], row[2])); }
        }
        if (scope.equals("full")) { verifyCliMembership(result); }
        return result;
    }

    /** CLI discovery is loaded only by the full Java 17+ reactor; the library gate remains Java 11 compatible. */
    private static void verifyCliMembership(final List<Obligation> required) throws Exception {
        final String cliPackage = "org.simplejavamail.internal.clisupport.";
        final Class<?> roots = Class.forName(cliPackage + "CliBuilderApi");
        final Class<?> mapper = Class.forName(cliPackage + "BuilderApiToPicocliCommandsMapper");
        final Method generate = mapper.getDeclaredMethod("generateOptionsFromBuilderApi", Class[].class);
        generate.setAccessible(true);
        final Iterable<?> options = (Iterable<?>) generate.invoke(null, roots.getMethod("roots").invoke(null));
        final Set<String> actual = new TreeSet<>();
        for (Object option : options) {
            final Method name = option.getClass().getMethod("getName");
            final Method source = option.getClass().getMethod("getSourceMethod");
            name.setAccessible(true);
            source.setAccessible(true);
            actual.add(name.invoke(option) + "|" + ConfigurationContractCoverage.signature((Method) source.invoke(option)));
        }
        final Set<String> classified = new TreeSet<>();
        for (Obligation obligation : required) {
            if (obligation.getKind().equals("cli")) { classified.add(obligation.getId()); }
        }
        requireSame("CLI input routes", actual, classified);
    }

    static Map<String, String> apiOwners() throws IOException {
        final Map<String, String> result = new LinkedHashMap<>();
        ConfigurationAdoptionCatalogue.API_OWNERS.forEach((route, owner) -> result.put(route, owner.name()));
        for (String[] row : resourceRows("/configuration-extra-api-routes.tsv", 2)) {
            ConfigurationAdoptionCatalogue.Owner.valueOf(row[1]);
            if (result.put(row[0], row[1]) != null) { throw new IllegalStateException("Duplicate setup route: " + row[0]); }
        }
        return result;
    }

    static void verifyMembership() throws Exception {
        if (!ConfigurationAdoptionCatalogue.PROPERTIES.keySet().equals(EnumSet.allOf(ConfigLoader.Property.class))) {
            throw new IllegalStateException("A source property has no explicit configuration owner");
        }
        final Set<String> actual = new LinkedHashSet<>();
        for (TransportStrategy protocol : List.of(TransportStrategy.SMTP, TransportStrategy.SMTPS)) {
            actual.addAll(ConnectionPropertyValues.supportedNames(protocol));
        }
        requireSame("raw alias access", actual, ConnectionCoverageCatalogue.ACCESS.keySet());
        requireSame("raw alias grammar", actual, ConnectionCoverageCatalogue.GRAMMAR.keySet());
        requireSame("public setup routes", discoveredRoutes(), apiOwners().keySet());
    }

    /** Follow setup return types and configuration parameters, including newly introduced interfaces outside the old list. */
    static Set<String> discoveredRoutes() throws ClassNotFoundException {
        final Deque<Class<?>> pending = new ArrayDeque<>(EXTRA_ROOTS);
        for (String name : ConfigurationAdoptionCatalogue.API_SIGNATURES.keySet()) { pending.add(Class.forName(name)); }
        return discoverFrom(pending);
    }

    static Set<String> discoverFrom(final Iterable<Class<?>> roots) {
        final Deque<Class<?>> pending = new ArrayDeque<>();
        roots.forEach(pending::add);
        final Set<Class<?>> visited = new LinkedHashSet<>();
        final Set<String> routes = new TreeSet<>();
        while (!pending.isEmpty()) {
            final Class<?> type = pending.removeFirst();
            if (!visited.add(type)) { continue; }
            if (type.getEnclosingClass() != null && looksLikeSetup(type.getEnclosingClass())) { pending.add(type.getEnclosingClass()); }
            for (Class<?> inherited : type.getInterfaces()) { addInputType(inherited, pending); }
            if (type.getSuperclass() != null) { addInputType(type.getSuperclass(), pending); }
            for (Method method : type.getDeclaredMethods()) {
                if (!isSetupRoute(type, method)) { continue; }
                routes.add(ConfigurationContractCoverage.signature(method));
                addInputType(method.getReturnType(), pending);
                for (Class<?> argument : method.getParameterTypes()) { addInputType(argument, pending); }
            }
            for (Class<?> nested : type.getDeclaredClasses()) {
                if (looksLikeSetup(nested)) { pending.add(nested); }
            }
        }
        return routes;
    }

    private static boolean isSetupRoute(final Class<?> type, final Method method) {
        if (!Modifier.isPublic(method.getModifiers()) || method.isSynthetic() || method.isBridge()) { return false; }
        if (type == ConfigLoader.class) { return method.getName().equals("builder") || method.getName().equals("load") || method.getName().startsWith("with"); }
        if (type == SimpleJavaMail.class) { return !method.getName().equals("getConfig"); }
        if (type == ConfiguredEmailConverter.class) { return method.getReturnType().getEnclosingClass() == ConfiguredEmailConverter.class; }
        if (Modifier.isStatic(method.getModifiers())) { return isStateCopyFactory(type, method); }
        if (isLookup(method) || method.getName().startsWith("build")) { return false; }
        return ConfigurationAdoptionCatalogue.isChoiceMethod(method) || looksLikeSetup(method.getReturnType());
    }

    private static void addInputType(Class<?> type, final Deque<Class<?>> pending) {
        while (type.isArray()) { type = type.getComponentType(); }
        if (!supportedType(type)) { return; }
        if (looksLikeSetup(type)) { pending.add(type); }
        for (Class<?> nested : type.getDeclaredClasses()) { if (looksLikeSetup(nested)) { pending.add(nested); } }
    }

    private static boolean supportedType(final Class<?> type) {
        return Modifier.isPublic(type.getModifiers()) && type.getName().startsWith("org.simplejavamail.") && !type.getName().contains(".internal.");
    }

    private static boolean looksLikeSetup(final Class<?> type) {
        if (!supportedType(type)) { return false; }
        if (EXTRA_ROOTS.contains(type)) { return true; }
        if (type.getEnclosingClass() == ConfiguredEmailConverter.class) { return true; }
        return Arrays.stream(type.getMethods()).anyMatch(method -> Modifier.isPublic(method.getModifiers())
                && !method.isBridge() && !method.isSynthetic() && (isCopyToBuilder(method) || isStateCopyFactory(type, method)
                || !Modifier.isStatic(method.getModifiers()) && !isLookup(method) && !method.getName().startsWith("build")
                && type.isAssignableFrom(method.getReturnType())));
    }

    private static boolean isCopyToBuilder(final Method method) {
        return method.getName().equals("toBuilder") && method.getParameterCount() == 0 && !Modifier.isStatic(method.getModifiers())
                && supportedType(method.getReturnType()) && method.getReturnType().getSimpleName().endsWith("Builder");
    }

    private static boolean isStateCopyFactory(final Class<?> owner, final Method method) {
        return Modifier.isStatic(method.getModifiers()) && method.getParameterCount() != 0 && method.getName().equals("builder")
                && supportedType(method.getReturnType()) && method.getReturnType().getEnclosingClass() == owner;
    }

    private static boolean isLookup(final Method method) { return method.getParameterCount() == 0 && method.getName().matches("(?:get|is|has)[A-Z].*"); }

    static void requireSame(final String label, final Set<String> actual, final Set<String> classified) {
        final Set<String> missing = new TreeSet<>(actual); missing.removeAll(classified);
        final Set<String> obsolete = new TreeSet<>(classified); obsolete.removeAll(actual);
        if (!missing.isEmpty() || !obsolete.isEmpty()) {
            throw new IllegalStateException(label + " membership mismatch; unclassified=" + missing + "; obsolete=" + obsolete);
        }
    }

    private static List<String[]> resourceRows(final String name, final int columns) throws IOException {
        final InputStream resource = ConfigurationContractInventory.class.getResourceAsStream(name);
        if (resource == null) { throw new IllegalStateException("Missing contract inventory " + name); }
        final List<String[]> result = new ArrayList<>();
        try (BufferedReader input = new BufferedReader(new InputStreamReader(resource, StandardCharsets.UTF_8))) {
            for (String line; (line = input.readLine()) != null;) {
                if (line.isBlank() || line.startsWith("#")) { continue; }
                final String[] row = line.split("\t", -1);
                if (row.length != columns || Arrays.stream(row).anyMatch(String::isBlank)) { throw new IllegalStateException("Invalid row in " + name + ": " + line); }
                result.add(row);
            }
        }
        return result;
    }

    public static void main(final String[] arguments) throws Exception {
        for (String route : discoveredRoutes()) { System.out.println(route); }
    }
}
