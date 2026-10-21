package io.lakeside.mockbank;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Bean;

/**
 * Mock core-banking service. Stands in for the bank's systems of record (accounts,
 * transaction enrichment, service request management). All data is synthetic.
 */
@SpringBootApplication
public class MockBankApplication {

    public static void main(String[] args) {
        builder().run(args);
    }

    /** Uses its own config file name so it can share a classpath with the assistant in tests. */
    public static SpringApplicationBuilder builder() {
        return new SpringApplicationBuilder(MockBankApplication.class).properties("spring.config.name=mock-bank");
    }

    @Bean
    Clock clock(@Value("${bank.timezone:Asia/Kolkata}") String timezone) {
        return Clock.system(ZoneId.of(timezone));
    }
}
