module org.simplejavamail.dkimprobe {
    requires org.simplejavamail;
    requires org.simplejavamail.core;
    requires org.simplejavamail.dkim;
    requires jakarta.mail;
    requires org.bouncycastle.provider;
    provides jakarta.mail.util.StreamProvider with org.simplejavamail.dkimprobe.FinalizedStreamProvider;
}
