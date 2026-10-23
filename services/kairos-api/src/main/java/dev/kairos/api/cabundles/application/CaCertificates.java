package dev.kairos.api.cabundles.application;

import dev.kairos.domain.error.ErrorCode;
import dev.kairos.domain.error.FieldError;
import dev.kairos.domain.error.ValidationFailedException;
import java.io.ByteArrayInputStream;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Strict, bounded PEM parsing: certificate blocks only, current CA certificates, at most ten. */
public final class CaCertificates {
    public static final String INVALID = "The PEM must contain one or more valid CA certificates.";
    private static final String BEGIN = "-----BEGIN CERTIFICATE-----";
    private static final String END = "-----END CERTIFICATE-----";

    private CaCertificates() {}

    public static void validate(@Nullable String pem, Instant now) {
        if (pem == null || pem.isEmpty() || pem.length() > 65536 || pem.chars().anyMatch(c -> c > 127)) throw invalid();
        int offset = 0;
        int count = 0;
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            while (offset < pem.length()) {
                while (offset < pem.length() && Character.isWhitespace(pem.charAt(offset))) offset++;
                if (offset == pem.length()) break;
                if (++count > 10 || !pem.startsWith(BEGIN, offset)) throw invalid();
                int end = pem.indexOf(END, offset + BEGIN.length());
                if (end < 0) throw invalid();
                String body = pem.substring(offset + BEGIN.length(), end).replaceAll("[\\r\\n\\t ]", "");
                byte[] der = Base64.getDecoder().decode(body);
                ByteArrayInputStream input = new ByteArrayInputStream(der);
                X509Certificate certificate = (X509Certificate) factory.generateCertificate(input);
                if (input.available() != 0 || certificate.getBasicConstraints() < 0) throw invalid();
                certificate.checkValidity(Date.from(now));
                boolean[] usage = certificate.getKeyUsage();
                if (usage != null && (usage.length <= 5 || !usage[5])) throw invalid();
                offset = end + END.length();
            }
            if (count == 0) throw invalid();
        } catch (CertificateException | IllegalArgumentException failure) {
            // Parser messages can contain supplied text; report only the fixed LLD detail.
            throw invalid();
        }
    }

    private static ValidationFailedException invalid() {
        return new ValidationFailedException(
                ErrorCode.VALIDATION, INVALID, List.of(new FieldError("pem", "invalid-certificate")));
    }
}
