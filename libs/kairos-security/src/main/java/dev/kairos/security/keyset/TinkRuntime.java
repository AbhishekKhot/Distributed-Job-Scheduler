package dev.kairos.security.keyset;

import com.google.crypto.tink.Configuration;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.mac.MacConfig;
import com.google.crypto.tink.signature.SignatureConfig;
import java.security.GeneralSecurityException;

/** Registers the Tink key types Kairos uses (AEAD, MAC, signatures) once per JVM. */
final class TinkRuntime {

    private static final Configuration CONFIGURATION = register();

    private TinkRuntime() {}

    /** Returns the configuration primitives are created with. */
    static Configuration configuration() {
        return CONFIGURATION;
    }

    private static Configuration register() {
        try {
            AeadConfig.register();
            MacConfig.register();
            SignatureConfig.register();
            return RegistryConfiguration.get();
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
