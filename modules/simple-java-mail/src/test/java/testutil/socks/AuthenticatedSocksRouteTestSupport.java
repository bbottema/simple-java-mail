package testutil.socks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** The opt-in build lane chooses expectations independently of the production capability detector. */
public final class AuthenticatedSocksRouteTestSupport {
    private AuthenticatedSocksRouteTestSupport() { }

    public static boolean expectsDirectRoute() {
        final String route = System.getProperty("sjm.test.authenticatedSocksRoute", "bridge");
        assertThat(route).as("explicit authenticated SOCKS test lane").isIn("bridge", "direct");
        return route.equals("direct");
    }

    public static void requireDirectRoute() {
        assumeTrue(expectsDirectRoute(), "Direct-only witness requires the angus-snapshot-development profile");
    }

    public static void requireReleasedBridge() {
        assumeTrue(!expectsDirectRoute(), "Released Angus bridge witness belongs to the default dependency lane");
    }
}
