package dev.kairos.testsupport;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.junit.platform.commons.support.AnnotationSupport;

/**
 * JUnit extension behind {@link KairosIntegrationTest}: starts the selected containers before the test class and
 * resolves {@link KairosPostgres}, {@link KairosKafka} and {@link KairosRedis} parameters.
 *
 * <p>The selection comes from the {@link KairosIntegrationTest} annotation on the test class or, for a {@code @Nested}
 * class, on the nearest enclosing class that has one. If the extension is registered without the annotation, every
 * container is selected.
 *
 * <p>Stateless and thread-safe.
 */
public final class KairosIntegrationTestExtension implements BeforeAllCallback, ParameterResolver {

    /** Creates the extension; JUnit instantiates it through {@link KairosIntegrationTest}. */
    public KairosIntegrationTestExtension() {}

    @Override
    public void beforeAll(ExtensionContext context) {
        KairosContainers.start(selection(context));
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        return containerFor(parameterContext.getParameter().getType()).isPresent();
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext) {
        Class<?> type = parameterContext.getParameter().getType();
        KairosContainer container = containerFor(type)
                .orElseThrow(() -> new ParameterResolutionException("Unsupported parameter type " + type.getName()));
        if (!selection(extensionContext).contains(container)) {
            throw new ParameterResolutionException(type.getSimpleName() + " is not selected; set "
                    + container.name().toLowerCase(Locale.ROOT) + " = true on @KairosIntegrationTest");
        }
        return container.instance();
    }

    private static Optional<KairosContainer> containerFor(Class<?> type) {
        if (type == KairosPostgres.class) {
            return Optional.of(KairosContainer.POSTGRES);
        }
        if (type == KairosKafka.class) {
            return Optional.of(KairosContainer.KAFKA);
        }
        if (type == KairosRedis.class) {
            return Optional.of(KairosContainer.REDIS);
        }
        return Optional.empty();
    }

    private static Set<KairosContainer> selection(ExtensionContext context) {
        for (Optional<Class<?>> current = context.getTestClass();
                current.isPresent();
                current = Optional.ofNullable(current.get().getEnclosingClass())) {
            Optional<KairosIntegrationTest> annotation =
                    AnnotationSupport.findAnnotation(current.get(), KairosIntegrationTest.class);
            if (annotation.isPresent()) {
                return KairosContainers.selection(annotation.get());
            }
        }
        return EnumSet.allOf(KairosContainer.class);
    }
}
