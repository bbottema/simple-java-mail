module org.simplejavamail.mailprovider.angus {
    requires jakarta.mail;
    requires org.eclipse.angus.mail;
    requires org.simplejavamail.core;
    requires org.slf4j;

    exports org.simplejavamail.internal.mailprovider.angus to jakarta.mail;

    provides org.simplejavamail.api.mailer.spi.MailTransportAdapter
            with org.simplejavamail.internal.mailprovider.angus.AngusMailTransportAdapter;
    provides org.simplejavamail.api.mailer.spi.MailTransportLifecycleAdapter
            with org.simplejavamail.internal.mailprovider.angus.AngusMailTransportLifecycleAdapter;
}
