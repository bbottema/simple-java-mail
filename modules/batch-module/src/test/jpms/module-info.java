module org.simplejavamail.batch.jpms.consumer {
	requires jakarta.mail;
	requires org.bbottema.genericobjectpool;
	requires org.bbottema.clusteredobjectpool;
	requires org.simplejavamail.smtpconnectionpool;
	requires org.simplejavamail.batch;
}
