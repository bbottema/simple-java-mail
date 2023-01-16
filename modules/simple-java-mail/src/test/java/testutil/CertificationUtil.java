package testutil;

import org.bouncycastle.asn1.x500.RDN;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x500.style.BCStyle;
import org.bouncycastle.asn1.x500.style.IETFUtils;
import org.bouncycastle.cms.SignerInformationVerifier;
import org.bouncycastle.cms.jcajce.JcaSimpleSignerInfoVerifierBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.OperatorCreationException;
import org.jetbrains.annotations.NotNull;

import java.security.cert.X509Certificate;

public class CertificationUtil {
	@NotNull
	public static String extractSignedBy(final X509Certificate certificate)
			throws OperatorCreationException {
		JcaSimpleSignerInfoVerifierBuilder builder = new JcaSimpleSignerInfoVerifierBuilder();
		builder.setProvider(BouncyCastleProvider.PROVIDER_NAME);
		SignerInformationVerifier verifier = builder.build(certificate);
		X500Name x500name = verifier.getAssociatedCertificate().getSubject();
		final RDN[] subject = x500name.getRDNs(BCStyle.CN);
		final RDN[] org = x500name.getRDNs(BCStyle.O);
		RDN cn = subject.length > 0 ? subject[0] : org[0];
		return IETFUtils.valueToString(cn.getFirst().getValue());
	}
}
