package io.lakeside.assistant.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "assistant.demo-login-enabled=false")
@AutoConfigureMockMvc
@Import(ApiTestSupport.ScriptedModel.class)
class DemoLoginDisabledTest {

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        ApiTestSupport.bankProperties(registry);
    }

    @Autowired
    MockMvc mvc;

    @Test
    void simulatedSignInCanBeSwitchedOff() throws Exception {
        mvc.perform(post("/api/session").contentType(MediaType.APPLICATION_JSON).content("{\"customer_id\":\"CUST-1001\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/demo/customers")).andExpect(status().isNotFound());
    }
}
