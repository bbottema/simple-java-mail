module org.simplejavamail.mailprovider.angus {
    requires jakarta.mail;
    requires org.eclipse.angus.mail;
    requires org.simplejavamail.core;

    provides org.simplejavamail.api.mailer.spi.MailTransportAdapter
            with org.simplejavamail.internal.mailprovider.angus.AngusMailTransportAdapter;
}
