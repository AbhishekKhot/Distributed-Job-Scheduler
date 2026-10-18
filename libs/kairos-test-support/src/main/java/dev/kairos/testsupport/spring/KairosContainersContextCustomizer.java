package dev.kairos.testsupport.spring;

import dev.kairos.testsupport.KairosContainer;
import dev.kairos.testsupport.KairosContainers;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.MergedContextConfiguration;

/**
 * Starts the selected containers and puts their connection details first in the test context's environment.
 *
 * <p>Being a record, it is equal to any other customizer with the same selection, which is what the Spring test
 * context cache needs: test classes that select the same containers share one application context.
 *
 * @param containers the containers whose properties are registered
 */
record KairosContainersContextCustomizer(Set<KairosContainer> containers) implements ContextCustomizer {

    /** Name of the property source added to the environment. */
    static final String PROPERTY_SOURCE_NAME = "kairosTestContainers";

    KairosContainersContextCustomizer {
        containers = Set.copyOf(containers);
    }

    @Override
    public void customizeContext(ConfigurableApplicationContext context, MergedContextConfiguration mergedConfig) {
        Map<String, Object> properties = new LinkedHashMap<>(KairosContainers.springProperties(containers));
        context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, properties));
    }
}
