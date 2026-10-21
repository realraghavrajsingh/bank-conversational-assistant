package io.lakeside.assistant.support;

import org.springframework.context.ConfigurableApplicationContext;

import io.lakeside.mockbank.MockBankApplication;
import io.lakeside.mockbank.store.BankStore;

/**
 * Runs the real mock core-banking service in-process on a random port, once per test JVM,
 * so assistant tests exercise genuine HTTP calls, auth and customer scoping.
 */
public final class MockBankServer {

    public static final String SERVICE_KEY = "test-service-key";
    private static ConfigurableApplicationContext context;

    private MockBankServer() {
    }

    public static synchronized String baseUrl() {
        return "http://localhost:" + context().getEnvironment().getProperty("local.server.port");
    }

    /** Restores seed data (drops cheque book requests created by earlier tests). */
    public static synchronized void reset() {
        context().getBean(BankStore.class).reset();
    }

    private static ConfigurableApplicationContext context() {
        if (context == null) {
            context = MockBankApplication.builder()
                    .run("--server.port=0", "--bank.service-key=" + SERVICE_KEY, "--spring.main.banner-mode=off");
        }
        return context;
    }
}
