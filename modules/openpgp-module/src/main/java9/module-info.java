module org.simplejavamail.openpgp {
    requires jakarta.mail;
    requires org.bouncycastle.pg;
    requires org.pgpainless.core;
    requires kotlin.stdlib;
    requires org.simplejavamail.core;

    exports org.simplejavamail.internal.openpgpsupport to org.simplejavamail;
}
