/**
 * Spring TestContext integration for {@link dev.kairos.testsupport.KairosIntegrationTest}.
 *
 * <p>{@link dev.kairos.testsupport.spring.KairosContainersContextCustomizerFactory} is registered in
 * {@code META-INF/spring.factories}, so Spring picks it up for every test class without any annotation beyond
 * {@code @KairosIntegrationTest}. Nothing in this package is loaded unless the test uses the Spring TestContext
 * framework.
 */
@NullMarked
package dev.kairos.testsupport.spring;

import org.jspecify.annotations.NullMarked;
