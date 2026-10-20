package dev.kairos.testsupport.spring;

import dev.kairos.testsupport.KairosContainers;
import dev.kairos.testsupport.KairosIntegrationTest;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.test.context.ContextConfigurationAttributes;
import org.springframework.test.context.ContextCustomizer;
import org.springframework.test.context.ContextCustomizerFactory;
import org.springframework.test.context.TestContextAnnotationUtils;

/**
 * Adds the Kairos container properties to the application context of every Spring test class annotated with
 * {@link KairosIntegrationTest}.
 *
 * <p>The annotation is looked up on the test class, its superclasses and, for {@code @Nested} classes, the enclosing
 * classes. Classes without it are left untouched.
 *
 * <p>Stateless and thread-safe. Instantiated by Spring through {@code META-INF/spring.factories}.
 */
public final class KairosContainersContextCustomizerFactory implements ContextCustomizerFactory {

    /** Creates the factory; Spring instantiates it through {@code META-INF/spring.factories}. */
    public KairosContainersContextCustomizerFactory() {}

    @Override
    public @Nullable ContextCustomizer createContextCustomizer(
            Class<?> testClass, List<ContextConfigurationAttributes> configAttributes) {
        KairosIntegrationTest annotation =
                TestContextAnnotationUtils.findMergedAnnotation(testClass, KairosIntegrationTest.class);
        if (annotation == null) {
            return null;
        }
        return new KairosContainersContextCustomizer(KairosContainers.selection(annotation));
    }
}
