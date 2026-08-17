module org.simplejavamail.providerneutral.consumer {
    requires org.simplejavamail;
    opens org.simplejavamail.providerneutral to jakarta.mail;
}
