# Released DKIM configuration fixture

`dkim-config-9.1.8.base64` contains an `Object[]` serialized with the published Maven Central
`org.simplejavamail:core-module:9.1.8` artifact on Java 11. It does not use the current checkout's classes.
The artifact's SHA-256 is `fc213dca40bf4b81b39e864806818c85aa7e4f6a2fce0c919342be7052cd85f8`.

The array contains a configured DKIM value, an external reference to its mutable exclusion Set, a configuration with an empty
Set and a configuration with absent exclusions. All three configurations use the same other preferences. The key bytes are
synthetic metadata, not a usable private key. Historical entries include `From`, null, blank and whitespace-bearing values;
restoring ownership must not introduce signing-policy validation or normalize those values.

The fixture is produced with this standalone program compiled and run against the released JAR:

```java
import org.simplejavamail.api.email.config.DkimConfig;
import java.io.ByteArrayOutputStream;
import java.io.ObjectOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;

public final class GenerateLegacyDkimFixture {
    public static void main(String[] args) throws Exception {
        Set<String> exclusions = new HashSet<>(Arrays.asList("Subject", " Date ", null, "From", ""));
        DkimConfig configured = configured(exclusions);
        Object[] graph = {configured, exclusions, configured(new HashSet<>()), configured(null)};
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ObjectOutputStream output = new ObjectOutputStream(bytes)) {
            output.writeObject(graph);
        }
        Files.writeString(Path.of(args[0]), Base64.getEncoder().encodeToString(bytes.toByteArray()) + "\n",
                StandardCharsets.US_ASCII);
    }

    private static DkimConfig configured(Set<String> exclusions) {
        return DkimConfig.builder().dkimPrivateKeyData(new byte[]{1, 2, 3})
                .dkimSigningDomain("legacy.example.org").dkimSelector("legacy-selector")
                .useLengthParam(true).headerCanonicalization(DkimConfig.Canonicalization.SIMPLE)
                .bodyCanonicalization(DkimConfig.Canonicalization.RELAXED).signingAlgorithm("legacy-algorithm")
                .excludedHeadersFromDkimDefaultSigningList(exclusions).build();
    }
}
```
