package com.gym.integration.cucumber;

import io.cucumber.java.AfterAll;
import io.cucumber.java.BeforeAll;

/**
 * Suite-level (not per-scenario) lifecycle: boots both real applications plus the shared
 * Testcontainers once before any scenario runs, and tears everything down once after the last
 * scenario. Cucumber only allows {@code @BeforeAll}/{@code @AfterAll} on static methods, which is
 * exactly the shape {@link IntegrationTestEnvironment} already exposes.
 */
public class Hooks {

    @BeforeAll
    public static void startEnvironment() {
        IntegrationTestEnvironment.start();
    }

    @AfterAll
    public static void stopEnvironment() {
        IntegrationTestEnvironment.stop();
    }
}
