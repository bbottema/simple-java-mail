package org.simplejavamail.internal.smimesupport;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.cms.CMSAlgorithm;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

/**
 * This class is used to resolve the algorithm name to the corresponding ASN1ObjectIdentifier.
 * <p>This is used to support the algorithm names in the S/MIME configuration, and is needed because
 * the Bouncy Castle CMSAlgorithm class does not provide a method to resolve the algorithm name to
 * (and it's not an enum, either)</p>
 */
class CMSAlgorithmResolver {

    private static final Map<String, ASN1ObjectIdentifier> algorithmMap = new HashMap<>();

    static {
        Field[] fields = CMSAlgorithm.class.getDeclaredFields();
        for (Field field : fields) {
            if (field.getType().equals(ASN1ObjectIdentifier.class)) {
                try {
                    algorithmMap.put(field.getName(), (ASN1ObjectIdentifier) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new RuntimeException("Failed to access CMSAlgorithm field: " + field.getName(), e);
                }
            }
        }
    }

    @Nullable
    static ASN1ObjectIdentifier resolve(String algorithmName) {
        return algorithmMap.get(algorithmName);
    }
}
