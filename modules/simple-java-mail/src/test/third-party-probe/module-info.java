module org.simplejavamail.thirdpartyprobe.fixture {
    requires org.simplejavamail;
    uses org.simplejavamail.api.mailer.spi.SmtpConnectionProbeAdapter;
    provides org.simplejavamail.api.mailer.spi.SmtpConnectionProbeAdapter
            with org.simplejavamail.thirdpartyprobe.PartialProbeAdapter;
    provides jakarta.mail.util.StreamProvider with org.simplejavamail.thirdpartyprobe.FixtureStreamProvider;
    opens org.simplejavamail.thirdpartyprobe to jakarta.mail;
}
