package dev.kairos.mocktarget.management;

import dev.kairos.mocktarget.behavior.application.CallTracker;
import dev.kairos.mocktarget.config.DevelopmentTls;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextConfiguration;
import org.springframework.boot.actuate.autoconfigure.web.ManagementContextType;
import org.springframework.context.annotation.Bean;

/** Loaded only in the separate management context, never scanned into the target listener. */
@ManagementContextConfiguration(value = ManagementContextType.CHILD, proxyBeanMethods = false)
public class ManagementConfiguration {
    @Bean
    public TargetManagementController targetManagementController(CallTracker tracker, DevelopmentTls tls) {
        return new TargetManagementController(tracker, tls.caPem());
    }
}
