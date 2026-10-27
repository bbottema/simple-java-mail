package org.simplejavamail.mailer.internal;

import org.junit.jupiter.api.Test;
import org.simplejavamail.api.contractfixture.SetupDiscoveryFixture;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConfigurationContractInventoryTest {
    @Test
    void aNewReachableSetupInterfaceAndEveryOverloadBecomeRequiredRoutes() {
        assertThat(ConfigurationContractInventory.discoverFrom(List.of(SetupDiscoveryFixture.Root.class)))
                .containsExactlyInAnyOrderElementsOf(expectedRoutes());
    }

    @Test
    void aConfigurationParameterMakesItsNestedValueBuilderReachable() {
        assertThat(ConfigurationContractInventory.discoverFrom(List.of(SetupDiscoveryFixture.Root.class)))
                .contains(policyBuilderRoute()).noneMatch(route -> route.contains("#getOption(") || route.contains("#build(")
                        || route.contains("#create(") || route.contains("#getValue(") || route.contains("#isConfigured(") || route.contains("#buildCopy("));
    }

    @Test
    void aNewReachableChildInterfaceCannotHideItsParentsSetupMethods() {
        final String root = SetupDiscoveryFixture.InheritedRoot.class.getName();
        final String child = SetupDiscoveryFixture.ChildSetup.class.getName();
        final String parent = SetupDiscoveryFixture.ParentSetup.class.getName();
        assertThat(ConfigurationContractInventory.discoverFrom(List.of(SetupDiscoveryFixture.InheritedRoot.class)))
                .containsExactlyInAnyOrder(root + "#setup():" + child, child + "#withChild(java.lang.String):" + child,
                        parent + "#withParent(java.lang.String):" + parent);
    }

    @Test
    void immutableConfigurationHoldersExposeTheirStateCarryingCopyRoute() {
        final String holder = SetupDiscoveryFixture.CopyValue.class.getName();
        final String builder = SetupDiscoveryFixture.CopyValue.Builder.class.getName();
        assertThat(ConfigurationContractInventory.discoverFrom(List.of(SetupDiscoveryFixture.CopyRoot.class)))
                .contains(holder + "#toBuilder():" + builder, builder + "#withValue(java.lang.String):" + builder);
    }

    @Test
    void staticCopyFactoriesCarryInputWhileNeutralBuilderFactoriesStayOutsideTheChoiceInventory() {
        final String holder = SetupDiscoveryFixture.CopyValue.class.getName();
        final String builder = SetupDiscoveryFixture.CopyValue.Builder.class.getName();
        assertThat(ConfigurationContractInventory.discoverFrom(List.of(SetupDiscoveryFixture.CopyRoot.class)))
                .contains(holder + "#builder(" + holder + "):" + builder)
                .doesNotContain(holder + "#builder():" + builder);
    }

    @Test
    void removingOneOverloadFromClassificationFailsExactMembership() {
        final Set<String> classified = expectedRoutes();
        classified.remove(overloadRoute());
        final Set<String> discovered = ConfigurationContractInventory.discoverFrom(List.of(SetupDiscoveryFixture.Root.class));
        assertThatThrownBy(() -> ConfigurationContractInventory.requireSame("fixture setup", discovered, classified))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unclassified=").hasMessageContaining(overloadRoute());
    }

    @Test
    void aChangedErasedSignatureReportsBothTheNewAndObsoleteRoute() {
        final Set<String> classified = expectedRoutes();
        classified.remove(overloadRoute());
        final String previous = SetupDiscoveryFixture.AddedSetup.class.getName() + "#withValue(java.lang.String,int):"
                + SetupDiscoveryFixture.AddedSetup.class.getName();
        classified.add(previous);
        final Set<String> discovered = ConfigurationContractInventory.discoverFrom(List.of(SetupDiscoveryFixture.Root.class));
        assertThatThrownBy(() -> ConfigurationContractInventory.requireSame("fixture setup", discovered, classified))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("unclassified=").hasMessageContaining(overloadRoute())
                .hasMessageContaining("obsolete=").hasMessageContaining(previous);
    }

    private static Set<String> expectedRoutes() {
        final String root = SetupDiscoveryFixture.Root.class.getName();
        final String added = SetupDiscoveryFixture.AddedSetup.class.getName();
        return new LinkedHashSet<>(List.of(root + "#setup():" + added,
                root + "#withPolicy(" + SetupDiscoveryFixture.Policy.class.getName() + "):" + root,
                added + "#withValue(java.lang.String):" + added, overloadRoute(), added + "#clearValue():" + added, policyBuilderRoute()));
    }

    private static String overloadRoute() {
        final String added = SetupDiscoveryFixture.AddedSetup.class.getName();
        return added + "#withValue(java.lang.String,boolean):" + added;
    }

    private static String policyBuilderRoute() {
        final String builder = SetupDiscoveryFixture.Policy.Builder.class.getName();
        return builder + "#withOption(java.lang.String):" + builder;
    }
}
