package org.simplejavamail.api.email;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.SimpleJavaMail;
import testutil.ConfigLoaderTestHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class EmailHeaderCollectionInputTest {

    private final SimpleJavaMail mail = SimpleJavaMail.withConfig(ConfigLoaderTestHelper.emptyConfig());

    @Test
    void declaredListMapKeepsHeaderSemanticsAndDetachesCallerCollections() throws Exception {
        final List<String> values = new ArrayList<>(Arrays.asList("first", "second", "first", null, ""));
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("x-route", values);
        final EmailPopulatingBuilder builder = message().withHeader("X-Route", "existing").withHeaders(headers);

        assertThat(values).containsExactly("first", "second", "first", null, "");
        values.clear();
        headers.clear();
        final Email email = builder.buildEmail();
        builder.withHeader("X-Route", "later");

        assertThat(email.getHeaders()).containsOnlyKeys("X-Route");
        assertThat(email.getHeaders().get("X-Route")).containsExactly("existing", "first", "second", null, "");
        assertThat(mail.converter().fromEmail(email).toMimeMessage().getHeader("X-Route"))
            .containsExactly("existing", "first", "second", "", "");
    }

    @Test
    void declaredSetMapRetainsItsIterationOrder() {
        final Set<Integer> values = new LinkedHashSet<>(Arrays.asList(30, 10, 20));
        final Map<String, Set<Integer>> headers = new LinkedHashMap<>();
        headers.put("X-Numbers", values);

        final Email email = message().withHeaders(headers).buildEmail();

        assertThat(values).containsExactly(30, 10, 20);
        assertThat(email.getHeaders().get("X-Numbers")).containsExactly("30", "10", "20");
    }

    @Test
    void explicitTypeWitnessAcceptsCollectionsOfSubtypeValues() {
        final Map<String, List<Integer>> headers = Map.of("X-Numbers", List.of(1, 2));

        final Email email = message().<Number>withHeaders(headers).buildEmail();

        assertThat(email.getHeaders().get("X-Numbers")).containsExactly("1", "2");
    }

    @Test
    void wildcardCollectionsRemainUsableWithoutRetyping() {
        final Map<String, ? extends Collection<? extends Number>> headers =
            Map.of("X-Numbers", List.of(7, 8L));

        final Email email = message().withHeaders(headers).buildEmail();

        assertThat(email.getHeaders().get("X-Numbers")).containsExactly("7", "8");
    }

    @Test
    void previousExactCollectionTypeAndTypeWitnessRemainUsable() {
        final Map<String, Collection<Integer>> headers = Map.of("X-Numbers", List.of(4, 5));

        final Email email = message().<Integer>withHeaders(headers).buildEmail();

        assertThat(email.getHeaders().get("X-Numbers")).containsExactly("4", "5");
    }

    private EmailPopulatingBuilder message() {
        return mail.emailBuilder().startingBlank().from("sender@example.org").to("recipient@example.org")
            .withPlainText("body");
    }
}
