package org.simplejavamail.mailer.failover;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class BatchSourceCancellationDirectTest {
    @Test
    @Timeout(180)
    void cancellationContractsWithoutTheBatchModule() throws Exception {
        new FailoverDirectClasspathTest().directSendingHonorsTheSameContracts("BatchSourceCancellationTest");
    }
}
