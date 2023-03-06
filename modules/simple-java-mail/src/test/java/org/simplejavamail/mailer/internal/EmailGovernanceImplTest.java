package org.simplejavamail.mailer.internal;

import lombok.val;
import org.junit.Test;
import testutil.EmailHelper;

import java.io.IOException;

public class EmailGovernanceImplTest {

    @Test
    public void produceEmailApplyingDefaultsAndOverrides() throws IOException {
        val builder = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true);
        System.out.println(builder.buildEmail());
    }
}