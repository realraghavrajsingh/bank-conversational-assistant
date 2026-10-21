package io.lakeside.assistant.config;

import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.Locale;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.lakeside.assistant.agent.Agent;
import io.lakeside.assistant.bank.BankClient;
import io.lakeside.assistant.llm.GeminiClient;
import io.lakeside.assistant.llm.LlmClient;
import io.lakeside.assistant.llm.OpenAiCompatClient;
import io.lakeside.assistant.session.SessionStore;
import io.lakeside.assistant.tools.BankingTools;

import jakarta.validation.Validator;

/** Wires the components explicitly so each one stays a plain, unit-testable class. */
@Configuration
public class AssistantConfig {

    @Bean
    Clock clock(AssistantProperties props) {
        return Clock.system(ZoneId.of(props.timezone()));
    }

    @Bean
    BankClient bankClient(AssistantProperties props, ObjectMapper mapper) {
        RestClient http = restClient(props.bank().baseUrl(), props.bank().timeout())
                .mutate()
                .defaultHeader(BankClient.SERVICE_KEY_HEADER, props.bank().serviceKey())
                .build();
        return new BankClient(http, mapper);
    }

    @Bean
    LlmClient llmClient(AssistantProperties props, ObjectMapper mapper) {
        AssistantProperties.Llm llm = props.llm();
        return switch (llm.provider().toLowerCase(Locale.ROOT)) {
            case "gemini" -> new GeminiClient(restClient(llm.gemini().baseUrl(), llm.timeout()), mapper,
                    llm.gemini().model(), llm.gemini().apiKey());
            case "openai" -> new OpenAiCompatClient(restClient(llm.openai().baseUrl(), llm.timeout()), mapper,
                    llm.openai().model(), llm.openai().apiKey());
            default -> throw new IllegalStateException("Unknown assistant.llm.provider: " + llm.provider());
        };
    }

    @Bean
    BankingTools bankingTools(ObjectMapper mapper, Validator validator, BankClient bank, AssistantProperties props,
                              Clock clock) {
        return new BankingTools(mapper, validator, bank, props.pendingActionTtl(), clock);
    }

    @Bean
    Agent agent(LlmClient llm, BankingTools tools, ObjectMapper mapper, AssistantProperties props, Clock clock) {
        return new Agent(llm, tools, mapper, props.maxAgentSteps(), props.historyMaxMessages(), clock);
    }

    @Bean
    SessionStore sessionStore(AssistantProperties props, Clock clock) {
        return new SessionStore(props.sessionTtl(), clock);
    }

    static RestClient restClient(String baseUrl, Duration readTimeout) {
        HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5));
        ProxySelector proxy = ProxySelector.getDefault();
        if (proxy != null) {
            builder.proxy(proxy); // honour JVM / corporate proxy settings
        }
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(builder.build());
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().requestFactory(factory).baseUrl(baseUrl).build();
    }
}
