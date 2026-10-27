package org.simplejavamail.mailer.internal;

import lombok.AccessLevel;
import lombok.Getter;
import org.jetbrains.annotations.Nullable;
import org.simplejavamail.config.ConfigLoader.Property;
import org.simplejavamail.internal.moduleloader.ModuleLoader;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.simplejavamail.internal.config.ConfigurationSnapshot.locksOf;

/** Only complete node and relationship validation can produce the immutable input accepted by Mailer construction. */
@Getter(AccessLevel.PACKAGE)
final class ValidatedMailerPlan {
    private final ResolvedMailerConfiguration configuration;
    private final ValidatedConnectionPlan connection;
    @Nullable private final ValidatedMailerPlan namedChild;

    private ValidatedMailerPlan(final ResolvedMailerConfiguration configuration, final ValidatedConnectionPlan connection,
            @Nullable final ValidatedMailerPlan namedChild) {
        this.configuration = configuration;
        this.connection = connection;
        this.namedChild = namedChild;
    }

    static ValidatedMailerPlan validate(final ResolvedMailerConfiguration configuration) {
        validateRelationships(configuration, new ArrayList<>());
        return validateNodes(configuration);
    }

    private static ValidatedMailerPlan validateNodes(final ResolvedMailerConfiguration node) {
        node.getInputs().getRequirements().validate();
        node.getOperational().validateExecution();
        if (node.getInputs().isRegularMailer() && node.getSessionSettings().getCustomMailer() == null) {
            MailerImpl.validateMandatoryStartTls(node.getInputs().getOwnedTransportStrategy(), node.getConnection().inputs.copyAdditionalProperties());
            MailerImpl.validateImplicitTlsOAuth2Protocol(node.getInputs().getOwnedTransportStrategy(), node.getConnection().inputs.copyAdditionalProperties());
        }
        final MailerOperationalSettings operational = node.getOperational();
        if (ModuleLoader.batchModuleAvailable()) {
            ModuleLoader.loadBatchModule().validateConnectionPoolConfiguration(operational.poolDefaults(),
                    operational.getConnectionPoolClusterConfigs().get(operational.getClusterKey()));
        }
        final ValidatedConnectionPlan connection = ValidatedConnectionPlan.validateResolved(node.getConnection(), node.getSessionSettings(), node.getInputs());
        final ValidatedMailerPlan child = node.getNamedChild() == null ? null : validateNodes(node.getNamedChild());
        return new ValidatedMailerPlan(node, connection, child);
    }

    private static void validateRelationships(final ResolvedMailerConfiguration node, final List<ResolvedMailerConfiguration> ancestors) {
        final CapturedMailerInputs inputs = node.getInputs();
        final var config = inputs.getConnection().getConfig();
        ConfiguredFailover.verifyPolicyLocks(locksOf(config), node.getOperational().getFailoverPolicy());
        for (ResolvedMailerConfiguration ancestor : ancestors) {
            LockedEmailConfiguration.verifyCompatibleLocks(ancestor.getInputs().getConnection().getConfig(), config);
            verifySharedAllowance(ancestor.getOperational(), node.getOperational());
        }
        verifyLockedRelationship(node);
        ancestors.add(node);
        if (node.getNamedChild() != null) { validateRelationships(node.getNamedChild(), ancestors); }
        for (MailerImpl borrowed = inputs.getBorrowedFailover(); borrowed != null; borrowed = borrowed.failoverMailer()) {
            for (ResolvedMailerConfiguration ancestor : ancestors) {
                final var preceding = ancestor.getInputs().getConnection().getConfig();
                LockedEmailConfiguration.verifyCompatibleLocks(preceding, borrowed.definition().config);
                if (borrowed.getOperationalConfig().getCustomMailer() != null) {
                    LockedEmailConfiguration.verifyCustomMailerCompatibility(preceding);
                }
            }
        }
        ancestors.remove(ancestors.size() - 1);
    }

    private static void verifyLockedRelationship(final ResolvedMailerConfiguration node) {
        final CapturedMailerInputs inputs = node.getInputs();
        final var locks = locksOf(inputs.getConnection().getConfig());
        if (!locks.contains(Property.FAILOVER_MAILER)) { return; }
        final FactoryMailerRuntime.Definition permitted = inputs.getPermittedFailoverDefinition();
        if (permitted != null && (node.getNamedChild() != null && node.getNamedChild().getInputs().getDefinition() == permitted
                || inputs.getBorrowedFailover() != null && inputs.getBorrowedFailover().definition() == permitted)) { return; }
        throw locks.conflict(Property.FAILOVER_MAILER, "This factory fixes the permitted failover definition and its nested route. "
                + "Keep the captured named relationship, or ask the configuration owner to supply a compatible failover route; "
                + "an unrelated Mailer with the same name or host is not that definition.");
    }

    private static void verifySharedAllowance(final MailerOperationalSettings first, final MailerOperationalSettings next) {
        if (first.getRateLimitGroup() != null && first.getRateLimitGroup().equals(next.getRateLimitGroup())
                && (!Objects.equals(first.getMessageRateLimit(), next.getMessageRateLimit())
                || !Objects.equals(first.getRecipientRateLimit(), next.getRecipientRateLimit())
                || first.isRateLimitBurstsAllowed() != next.isRateLimitBurstsAllowed())) {
            throw new IllegalArgumentException("Mailers sharing a rate-limit group must use the same message limit, recipient limit and burst setting."
                    + " Match those settings or use different group names for independent allowances.");
        }
    }
}
