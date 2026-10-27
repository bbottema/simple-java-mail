package org.simplejavamail.api.contractfixture;

/** Discovery-only public shapes model setup APIs that become reachable after a future API addition. */
public final class SetupDiscoveryFixture {
    private SetupDiscoveryFixture() { }

    public interface Root {
        AddedSetup setup();
        Root withPolicy(Policy policy);
    }

    public interface AddedSetup {
        AddedSetup withValue(String value);
        AddedSetup withValue(String value, boolean replace);
        AddedSetup clearValue();
        String getValue();
        boolean isConfigured();
        AddedSetup buildCopy();
        static AddedSetup create() { return null; }
    }

    public static final class Policy {
        private Policy() { }
        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            public Builder withOption(final String value) { return this; }
            public String getOption() { return null; }
            public Policy build() { return new Policy(); }
        }
    }

    public interface InheritedRoot {
        ChildSetup setup();
    }

    public interface ParentSetup {
        ParentSetup withParent(String value);
    }

    public interface ChildSetup extends ParentSetup {
        ChildSetup withChild(String value);
    }

    public interface CopyRoot {
        CopyRoot withCopy(CopyValue value);
    }

    public static final class CopyValue {
        public Builder toBuilder() { return new Builder(); }
        public static Builder builder() { return new Builder(); }
        public static Builder builder(final CopyValue existing) { return new Builder(); }

        public static final class Builder {
            public Builder withValue(final String value) { return this; }
            public CopyValue build() { return new CopyValue(); }
        }
    }
}
