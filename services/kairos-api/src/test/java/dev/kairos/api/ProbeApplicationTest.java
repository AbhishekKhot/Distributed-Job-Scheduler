package dev.kairos.api;

import org.springframework.test.context.TestPropertySource;

/** Base of the application tests that also load the test-only {@link ProbeController}. */
@TestPropertySource(properties = "kairos.test.probe-controller=true")
public abstract class ProbeApplicationTest extends ApiApplicationTest {}
