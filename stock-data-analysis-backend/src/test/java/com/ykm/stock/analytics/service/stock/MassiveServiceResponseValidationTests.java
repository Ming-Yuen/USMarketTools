package com.ykm.stock.analytics.service.stock;

import com.ykm.stock.analytics.config.MassiveSubscription;
import com.ykm.stock.analytics.model.dto.massive.response.MassiveTickerDetails;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;


import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class MassiveServiceResponseValidationTests {

    /** Scenario：主檔仍是全市場bulk分頁，不新增逐股Details或原始證據儲存。 */
    @Test
    void currentMasterUsesCompleteBulkPagination() {
        Fixture f = new Fixture();
        f.http.expect(queryParam("market", "stocks")).andExpect(queryParam("active", "true"))
                .andExpect(header("Authorization", "Bearer test-only-key"))
                .andExpect(request -> assertThat(request.getURI().getQuery()).doesNotContain("date=", "ticker="))
                .andRespond(withSuccess("""
                        {"status":"OK","count":1,"results":[{"ticker":"AAA","composite_figi":"F1"}],
                         "next_url":"https://api.massive.com/v3/reference/tickers?cursor=NEXT"}
                        """, MediaType.APPLICATION_JSON));
        f.http.expect(queryParam("cursor", "NEXT")).andRespond(withSuccess("""
                {"status":"OK","count":1,"results":[{"ticker":"BBB","composite_figi":"F2"}]}
                """, MediaType.APPLICATION_JSON));
        assertThat(f.service.getAllStockTickers()).extracting(MassiveTickerDetails::getTicker).containsExactly("AAA", "BBB");
        f.http.verify();
    }

    /** Scenario：缺少分頁results不是空市場，必須失敗，不讓主檔批次誤停用股票。 */
    @Test
    void incompleteMasterIsRejected() {
        Fixture f = new Fixture();
        f.http.expect(queryParam("market", "stocks")).andRespond(withSuccess("{\"status\":\"OK\"}", MediaType.APPLICATION_JSON));
        assertThatThrownBy(f.service::getAllStockTickers).hasMessage("MASSIVE_STOCK_MASTER_INCOMPLETE");
    }

    @Test
    void unexpectedPaginationHostIsNotGivenCredentials() {
        Fixture f = new Fixture();
        f.http.expect(queryParam("market", "stocks")).andRespond(withSuccess("""
                {"status":"OK","results":[{"ticker":"AAA"}],"next_url":"https://not-massive.example/tickers"}
                """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(f.service::getAllStockTickers).hasMessage("MASSIVE_UNEXPECTED_PAGINATION_HOST");
        f.http.verify();
    }

    private static final class Fixture {
        final MassiveService service = new MassiveService("https://api.massive.com", "test-only-key", MassiveSubscription.STARTER);
        final RestClient.Builder builder = RestClient.builder();
        final MockRestServiceServer http = MockRestServiceServer.bindTo(builder).build();
        Fixture() { ReflectionTestUtils.setField(service, "restClient", builder.build()); }
    }
}
