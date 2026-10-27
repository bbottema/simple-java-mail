package org.simplejavamail.mailer.internal;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import testutil.configcontracts.ConfigurationContractCoverage;
import testutil.configcontracts.ConfigurationContractEvidence;
import testutil.configcontracts.ConfigurationContractEvidence.Entry;
import testutil.configcontracts.ConfigurationContractVerifier;
import testutil.configcontracts.ConfigurationContractVerifier.Obligation;
import testutil.configcontracts.ConfigurationContractVerifier.Proof;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Verify phase acceptance: only successful current-run evidence can satisfy the independent supported contract inventory. */
public final class ConfigurationContractGate {
    private static final Pattern PARAMETER_INDEX = Pattern.compile("\\[test-template-invocation:#(\\d+)\\]");

    private ConfigurationContractGate() { }

    public static void main(final String[] arguments) throws Exception {
        if (arguments.length != 3) { throw new IllegalArgumentException("Expected repository root, Maven run identifier and library/full scope"); }
        final Path root = Path.of(arguments[0]).toAbsolutePath().normalize();
        final String run = arguments[1];
        if (!run.matches("run-[0-9]+")) { throw new IllegalArgumentException("A gate requires the current Maven session's run-<millis> identifier"); }
        final String scope = arguments[2];
        final List<Obligation> required = ConfigurationContractInventory.required(scope);
        final Path inventory = root.resolve("modules/simple-java-mail/src/test/resources");
        final String fingerprint = ConfigurationContractEvidence.fingerprint(inventory);
        verify(root, run, scope, required, fingerprint);
    }

    static ConfigurationContractVerifier.Result verify(final Path root, final String run, final String scope,
            final List<Obligation> required, final String fingerprint) throws Exception {
        if (!run.matches("run-[0-9]+")) {
            throw new IllegalArgumentException("A gate requires the current Maven session's run-<millis> identifier");
        }
        final Path report = root.resolve("modules/simple-java-mail/target/configuration-contract-coverage").resolve(run);
        Files.createDirectories(report);
        writeStatus(report, scope, run, "PENDING", null);
        try {
            final List<Proof> proofs = captureProofs(root, run, required, fingerprint);
            writeReport(report, scope, run, fingerprint, required, proofs);
            final ConfigurationContractVerifier.Result result = ConfigurationContractVerifier.verify(required, proofs, fingerprint);
            writeStatus(report, scope, run, "PASSED", result);
            System.out.println("Configuration contracts (" + scope + "): " + result.getCoveredCount() + "/" + result.getRequiredCount()
                    + " (100%); zero missing supported contracts. Report: " + report.resolve(scope + ".tsv"));
            return result;
        } catch (Exception failure) {
            writeStatus(report, scope, run, "FAILED", null);
            throw failure;
        }
    }

    private static List<Proof> captureProofs(final Path root, final String run, final List<Obligation> required,
            final String fingerprint) throws Exception {
        final Set<String> modules = required.stream().map(Obligation::getModule).collect(Collectors.toCollection(LinkedHashSet::new));
        final List<Proof> proofs = new ArrayList<>();
        for (String module : modules) {
            final Path directory = root.resolve("modules").resolve(module).resolve("target");
            final Map<String, List<TestOutcome>> outcomes = outcomes(directory.resolve("surefire-reports"), run);
            for (Entry entry : ConfigurationContractEvidence.read(directory.resolve("configuration-contracts").resolve(run))) {
                proofs.add(new Proof(module, entry, successful(entry, outcomes)));
            }
            for (Obligation obligation : required) {
                if (obligation.getModule().equals(module) && obligation.getKind().equals("test")) {
                    final List<TestOutcome> cases = outcomes.getOrDefault(obligation.getId(), List.of());
                    if (!cases.isEmpty() && cases.stream().allMatch(test -> test.success)) {
                        proofs.add(new Proof(module, new Entry("test", obligation.getId(), obligation.getId(),
                                "surefire:" + obligation.getId(), fingerprint), true));
                    }
                }
            }
        }
        return proofs;
    }

    static boolean successful(final Entry proof, final Map<String, List<TestOutcome>> outcomes) {
        final Matcher parameter = PARAMETER_INDEX.matcher(proof.getInvocationId());
        final String suffix = parameter.find() ? "[" + parameter.group(1) + "]" : null;
        final List<TestOutcome> candidates = outcomes.getOrDefault(proof.getTest(), List.of());
        return candidates.stream().anyMatch(test -> test.success && (suffix == null || test.name.endsWith(suffix)));
    }

    static Map<String, List<TestOutcome>> outcomes(final Path directory, final String run) throws Exception {
        final Map<String, List<TestOutcome>> outcomes = new LinkedHashMap<>();
        if (!Files.isDirectory(directory)) { return outcomes; }
        final List<Path> reports;
        try (Stream<Path> paths = Files.list(directory)) {
            reports = paths.filter(path -> path.getFileName().toString().startsWith("TEST-")
                    && path.getFileName().toString().endsWith(".xml")).sorted().collect(Collectors.toList());
        }
        final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        for (Path report : reports) {
            final Element suite = factory.newDocumentBuilder().parse(report.toFile()).getDocumentElement();
            if (!run.equals(property(suite, ConfigurationContractCoverage.RUN_PROPERTY))) { continue; }
            if (suite.getElementsByTagName("failure").getLength() != 0 || suite.getElementsByTagName("error").getLength() != 0) {
                throw new IllegalStateException("Current contract run contains a failed test suite: " + report.getFileName());
            }
            final NodeList cases = suite.getElementsByTagName("testcase");
            for (int index = 0; index < cases.getLength(); index++) {
                final Element test = (Element) cases.item(index);
                final String name = test.getAttribute("name");
                final String method = name.contains("(") ? name.substring(0, name.indexOf('('))
                        : name.contains("[") ? name.substring(0, name.indexOf('[')) : name;
                final boolean success = test.getElementsByTagName("failure").getLength() == 0
                        && test.getElementsByTagName("error").getLength() == 0 && test.getElementsByTagName("skipped").getLength() == 0;
                outcomes.computeIfAbsent(test.getAttribute("classname") + "#" + method, ignored -> new ArrayList<>())
                        .add(new TestOutcome(name, success));
            }
        }
        return outcomes;
    }

    private static String property(final Element suite, final String key) {
        final NodeList properties = suite.getElementsByTagName("property");
        for (int index = 0; index < properties.getLength(); index++) {
            final Element property = (Element) properties.item(index);
            if (property.getAttribute("name").equals(key)) { return property.getAttribute("value"); }
        }
        return null;
    }

    private static void writeReport(final Path directory, final String scope, final String run, final String fingerprint,
            final Collection<Obligation> required, final Collection<Proof> proofs) throws IOException {
        Files.createDirectories(directory);
        final Map<String, List<Proof>> indexed = new LinkedHashMap<>();
        for (Proof proof : proofs) {
            if (proof.isCurrentSuccessfulTest() && fingerprint.equals(proof.getEntry().getInventoryFingerprint())) {
                indexed.computeIfAbsent(key(proof.getEntry().getKind(), proof.getEntry().getId(), proof.getModule()), ignored -> new ArrayList<>()).add(proof);
            }
        }
        final List<String> rows = new ArrayList<>();
        rows.add("# scope=" + scope + " run=" + run + " inventory=" + fingerprint);
        rows.add("kind\tid\tmodule\tstatus\twitnesses");
        final Map<String, Integer> counts = new TreeMap<>();
        for (Obligation obligation : required) {
            final List<Proof> witnesses = indexed.getOrDefault(key(obligation.getKind(), obligation.getId(), obligation.getModule()), List.of());
            rows.add(obligation.getKind() + "\t" + obligation.getId() + "\t" + obligation.getModule() + "\t"
                    + (witnesses.isEmpty() ? "MISSING" : "COVERED") + "\t"
                    + witnesses.stream().map(proof -> proof.getEntry().getTest()).distinct().sorted().collect(Collectors.joining(",")));
            counts.merge(obligation.getKind(), 1, Integer::sum);
        }
        Files.write(directory.resolve(scope + ".tsv"), rows, StandardCharsets.UTF_8);
        Files.writeString(directory.resolve(scope + "-summary.txt"), "Scope: " + scope + "\nRun: " + run + "\nRequired by kind: " + counts
                + "\nMissing: " + rows.stream().filter(row -> row.contains("\tMISSING\t")).count() + "\n", StandardCharsets.UTF_8);
    }

    private static String key(final String kind, final String id, final String module) { return kind + "\t" + id + "\t" + module; }

    private static void writeStatus(final Path directory, final String scope, final String run, final String state,
            final ConfigurationContractVerifier.Result result) throws IOException {
        final String coverage = result == null ? "Not accepted" : result.getCoveredCount() + "/" + result.getRequiredCount() + " (100%)";
        Files.writeString(directory.resolve(scope + "-status.txt"), "Scope: " + scope + "\nRun: " + run + "\nState: " + state
                + "\nCoverage: " + coverage + "\n", StandardCharsets.UTF_8);
    }

    static final class TestOutcome {
        final String name;
        final boolean success;
        TestOutcome(final String name, final boolean success) { this.name = name; this.success = success; }
    }
}
