package org.simplejavamail.mailer.failover;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** Reuses the same wire contracts with the batch and pool implementations absent from the child JVM. */
class FailoverAuthenticationSetupDirectTest {
    @Test
    @Timeout(180)
    void authenticationSetupContractsWithoutTheBatchModule() throws Exception {
        new FailoverDirectClasspathTest().directSendingHonorsTheSameContracts("FailoverAuthenticationSetupTest");
    }
}
