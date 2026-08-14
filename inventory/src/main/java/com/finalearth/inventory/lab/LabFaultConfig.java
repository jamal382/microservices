package com.finalearth.inventory.lab;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the fault-injection machinery, and only when it is explicitly switched on.
 *
 * <p>The flag defaults to false so that forgetting to remove this package from a real
 * deployment is merely embarrassing rather than an outage. {@code docker-compose.yml}
 * turns it on for the two inventory replicas.
 */
@Configuration
@ConditionalOnProperty(name = "lab.fault-injection.enabled", havingValue = "true")
public class LabFaultConfig {

    @Bean
    public FaultState faultState() {
        return new FaultState();
    }

    @Bean
    public FaultInjectionFilter faultInjectionFilter(FaultState faultState) {
        return new FaultInjectionFilter(faultState);
    }

    @Bean
    public FaultController faultController(FaultState faultState) {
        return new FaultController(faultState);
    }
}
