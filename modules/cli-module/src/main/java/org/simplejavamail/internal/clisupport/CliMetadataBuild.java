package org.simplejavamail.internal.clisupport;

import org.simplejavamail.api.internal.clisupport.model.CliDeclaredOptionSpec;
import org.simplejavamail.internal.clisupport.serialization.CliMetadataCache;
import org.simplejavamail.internal.clisupport.serialization.JavadocCacheSerialization;
import org.simplejavamail.internal.clisupport.serialization.SerializationUtil;
import org.simplejavamail.internal.clisupport.therapijavadoc.TherapiJavadocHelper;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Maven entry point. Its explicit arguments define the persistent cache and normal resource output. */
public final class CliMetadataBuild {
    private CliMetadataBuild() {
    }

    public static void main(final String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("Expected module directory, output directory, cache directory and release flag.");
        final Path module = Path.of(args[0]).toAbsolutePath().normalize();
        final String key = CliMetadataInputs.key(CliMetadataInputs.classpath(), List.of(module.resolve("pom.xml"), module.resolve("../../pom.xml").normalize()));
        final boolean generated = new CliMetadataStore(Path.of(args[2]), Path.of(args[1]))
                .ensure(key, Boolean.parseBoolean(args[3]), CliMetadataBuild::generate);
        System.out.println("CLI metadata " + (generated ? "generated" : "reused") + " for inputs " + key);
    }

    static CliGeneratedMetadata generate() {
        try (TherapiJavadocHelper.GenerationScope scope = TherapiJavadocHelper.beginGeneration()) {
            final List<CliDeclaredOptionSpec> options = BuilderApiToPicocliCommandsMapper.generateOptionsFromBuilderApi(CliBuilderApi.roots());
            if (options.isEmpty()) throw new IllegalStateException("No CLI options were generated.");
            // Construct every command once so all runtime help references are included without repeatedly parsing hundreds of help requests.
            CliCommandLineProducer.configurePicoCli(options, 150);
            final byte[] fingerprint = CliSourceApiFingerprint.calculate();
            final byte[] javadoc = scope.serializedJavadoc();
            final Map<String, ?> restored = JavadocCacheSerialization.deserialize(
                    CliMetadataCache.unwrap(CliMetadataCache.Kind.THERAPI_JAVADOC, fingerprint, javadoc));
            for (CliDeclaredOptionSpec option : options) {
                if (!restored.containsKey(option.getSourceMethod().toString())) {
                    throw new IllegalStateException("Generated Javadoc is missing: " + option.getName());
                }
            }
            final byte[] encodedOptions = CliMetadataCache.wrap(CliMetadataCache.Kind.CLI_OPTIONS, fingerprint, SerializationUtil.serialize(options));
            final List<?> decoded = SerializationUtil.deserialize(CliMetadataCache.unwrap(CliMetadataCache.Kind.CLI_OPTIONS, fingerprint, encodedOptions));
            if (decoded.size() != options.size()) throw new IllegalStateException("Generated command metadata is incomplete.");
            return CliGeneratedMetadata.builder().options(encodedOptions).javadoc(javadoc).build();
        }
    }
}
