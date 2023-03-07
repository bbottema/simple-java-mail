package org.simplejavamail.mailer.internal;

import lombok.val;
import org.junit.Test;
import org.simplejavamail.api.email.EmailAssert;
import org.simplejavamail.api.email.Recipient;
import testutil.ConfigLoaderTestHelper;
import testutil.EmailHelper;

public class EmailGovernanceImplTest {

    @Test
    public void produceEmailApplyingDefaultsAndOverrides_DispositionNotificationTo() {
        ConfigLoaderTestHelper.clearConfigProperties();

        val defaults = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .clearDispositionNotificationTo()
                .clearReplyTo()
                .withDispositionNotificationTo("disposition.notificationTo@domain.com")
                .buildEmail();
        val overrides1 = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .clearDispositionNotificationTo()
                .clearReplyTo()
                .withDispositionNotificationTo("disposition.notificationTo.override@domain.com")
                .buildEmail();
        val overrides2 = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .clearDispositionNotificationTo()
                .clearReplyTo()
                .withReplyTo("replyto@domain.com")
                .withDispositionNotificationTo()
                .buildEmail();
        val userEmail = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .clearDispositionNotificationTo()
                .clearReplyTo()
                .from("from@domain.com")
                .withDispositionNotificationTo()
                .buildEmail();

        EmailAssert.assertThat(new EmailGovernanceImpl(null, null, null, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseDispositionNotificationTo(true)
                .hasDispositionNotificationTo(new Recipient(null, "from@domain.com", null));

        EmailAssert.assertThat(new EmailGovernanceImpl(null, defaults, null, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseDispositionNotificationTo(true)
                .hasDispositionNotificationTo(new Recipient(null, "disposition.notificationTo@domain.com", null));

        EmailAssert.assertThat(new EmailGovernanceImpl(null, defaults, overrides1, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseReturnReceiptTo(true)
                .hasDispositionNotificationTo(new Recipient(null, "disposition.notificationTo.override@domain.com", null));

        EmailAssert.assertThat(new EmailGovernanceImpl(null, defaults, overrides2, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseReturnReceiptTo(true)
                .hasDispositionNotificationTo(new Recipient(null, "disposition.notificationTo@domain.com", null));

        EmailAssert.assertThat(new EmailGovernanceImpl(null, null, overrides2, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseDispositionNotificationTo(true)
                .hasDispositionNotificationTo(new Recipient(null, "replyto@domain.com", null));
    }

    @Test
    public void produceEmailApplyingDefaultsAndOverrides_ReturnReceiptTo() {
        ConfigLoaderTestHelper.clearConfigProperties();

        val defaults = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .clearReturnReceiptTo()
                .clearReplyTo()
                .withReturnReceiptTo("return.receiptTo@domain.com")
                .buildEmail();
        val overrides1 = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .clearReturnReceiptTo()
                .clearReplyTo()
                .withReturnReceiptTo("return.receiptTo.override@domain.com")
                .buildEmail();
        val overrides2 = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .clearReturnReceiptTo()
                .clearReplyTo()
                .withReplyTo("replyto@domain.com")
                .withReturnReceiptTo()
                .buildEmail();
        val userEmail = EmailHelper.createDummyEmailBuilder(true, false, true, true, false, true)
                .clearReturnReceiptTo()
                .clearReplyTo()
                .from("from@domain.com")
                .withReturnReceiptTo()
                .buildEmail();

        EmailAssert.assertThat(new EmailGovernanceImpl(null, null, null, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseReturnReceiptTo(true)
                .hasReturnReceiptTo(new Recipient(null, "from@domain.com", null));

        EmailAssert.assertThat(new EmailGovernanceImpl(null, defaults, null, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseReturnReceiptTo(true)
                .hasReturnReceiptTo(new Recipient(null, "return.receiptTo@domain.com", null));

        EmailAssert.assertThat(new EmailGovernanceImpl(null, defaults, overrides1, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseReturnReceiptTo(true)
                .hasReturnReceiptTo(new Recipient(null, "return.receiptTo.override@domain.com", null));

        EmailAssert.assertThat(new EmailGovernanceImpl(null, defaults, overrides2, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseReturnReceiptTo(true)
                .hasReturnReceiptTo(new Recipient(null, "return.receiptTo@domain.com", null));

        EmailAssert.assertThat(new EmailGovernanceImpl(null, null, overrides2, null).produceEmailApplyingDefaultsAndOverrides(userEmail))
                .hasUseReturnReceiptTo(true)
                .hasReturnReceiptTo(new Recipient(null, "replyto@domain.com", null));
    }
}