module org.simplejavamail.managedangus.consumer {
    requires org.simplejavamail;
    uses org.simplejavamail.api.mailer.spi.MailTransportLifecycleAdapter;
    uses org.simplejavamail.api.mailer.spi.SmtpConnectionProbeAdapter;
    exports org.simplejavamail.managedangus.factories to org.simplejavamail.mailprovider.angus;
}
