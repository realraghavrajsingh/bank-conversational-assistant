package io.lakeside.mockbank;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import io.lakeside.mockbank.store.BankStore;

@SpringBootTest(properties = "spring.config.name=mock-bank")
@AutoConfigureMockMvc
class BankApiTest {

    private static final String KEY = "dev-bank-service-key";

    @Autowired
    MockMvc mvc;
    @Autowired
    BankStore store;

    @BeforeEach
    void reset() {
        store.reset();
    }

    private static MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder b) {
        return b.header("X-Service-Key", KEY);
    }

    @Test
    void requiresServiceKey() throws Exception {
        mvc.perform(get("/v1/customers/CUST-1001/accounts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/customers/CUST-1001/accounts").header("X-Service-Key", "wrong")).andExpect(status().isUnauthorized());
        mvc.perform(get("/health")).andExpect(status().isOk());
    }

    @Test
    void balancesAreDerivedFromTransactions() throws Exception {
        mvc.perform(authed(get("/v1/customers/CUST-1001/accounts/ACC-1001-SB/balance")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.masked_number").value("XXXX4521"))
                .andExpect(jsonPath("$.ledger_balance").value("89145.05"))
                .andExpect(jsonPath("$.pending_holds").value("1240.00"))
                .andExpect(jsonPath("$.available_balance").value("87905.05"));
    }

    @Test
    void crossCustomerAccessIsNotFound() throws Exception {
        mvc.perform(authed(get("/v1/customers/CUST-1001/accounts/ACC-1002-SB/balance"))).andExpect(status().isNotFound());
        mvc.perform(authed(get("/v1/customers/CUST-1001/transactions/TXN-92002"))).andExpect(status().isNotFound());
        mvc.perform(authed(get("/v1/customers/CUST-1001/transactions").param("account_id", "ACC-1002-SB")))
                .andExpect(status().isNotFound());
    }

    @Test
    void searchMatchesDecodedMerchantNamesAndReturnsTotals() throws Exception {
        mvc.perform(authed(get("/v1/customers/CUST-1001/transactions").param("q", "razorpay").param("direction", "debit")))
                .andExpect(jsonPath("$.transactions[*].transaction_id", contains("TXN-90004")));
        mvc.perform(authed(get("/v1/customers/CUST-1001/transactions").param("q", "uber")))
                .andExpect(jsonPath("$.count").value(2))
                .andExpect(jsonPath("$.total_amount").value("472.00"));
    }

    @Test
    void transactionDetailCarriesExplanation() throws Exception {
        mvc.perform(authed(get("/v1/customers/CUST-1001/transactions/TXN-90010")))
                .andExpect(jsonPath("$.transaction_id").value("TXN-90010"))
                .andExpect(jsonPath("$.amount").value("500.00"))
                .andExpect(jsonPath("$.explanation").exists())
                .andExpect(jsonPath("$.merchant_details.category").value("Fees"));
    }

    @Test
    void chequeBookRequestIsIdempotentAndSingleOpenPerAccount() throws Exception {
        String body = "{\"account_id\":\"ACC-1001-SB\",\"leaves\":25}";
        mvc.perform(chequeBook("CUST-1001", body, "key-0000001"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.service_request_id").value("SR-700301"))
                .andExpect(jsonPath("$.replayed").value(false));
        mvc.perform(chequeBook("CUST-1001", body, "key-0000001"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.service_request_id").value("SR-700301"))
                .andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(chequeBook("CUST-1001", body, "key-0000002"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("open_request_exists"))
                .andExpect(jsonPath("$.detail.service_request_id").value("SR-700301"));
    }

    @Test
    void ineligibleAccountAndExistingRequestAreRejected() throws Exception {
        mvc.perform(chequeBook("CUST-1003", "{\"account_id\":\"ACC-1003-BS\",\"leaves\":25}", "key-0000003"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("not_eligible"));
        mvc.perform(chequeBook("CUST-1002", "{\"account_id\":\"ACC-1002-SB\",\"leaves\":25}", "key-0000004"))
                .andExpect(status().isConflict());
        mvc.perform(authed(get("/v1/customers/CUST-1002/accounts/ACC-1002-SB/cheque-book/eligibility")))
                .andExpect(jsonPath("$.eligible").value(false))
                .andExpect(jsonPath("$.existing_open_request.service_request_id").value("SR-700245"))
                .andExpect(jsonPath("$.delivery_address_masked").value("22, ****, Bengaluru - 560***"));
    }

    private MockHttpServletRequestBuilder chequeBook(String customer, String body, String key) {
        return authed(post("/v1/customers/" + customer + "/service-requests/cheque-book"))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }
}
