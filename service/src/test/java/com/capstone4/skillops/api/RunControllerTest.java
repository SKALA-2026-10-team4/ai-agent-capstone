package com.capstone4.skillops.api;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.capstone4.skillops.auth.AuthProperties;
import com.capstone4.skillops.auth.SecurityConfig;
import com.capstone4.skillops.auth.TokenService;
import com.capstone4.skillops.scenario.RunLedger;
import com.capstone4.skillops.scenario.Scenario;
import com.capstone4.skillops.scenario.ScenarioCatalog;

/**
 * 실행 번호 이름 조회(service/src/main/java/com/capstone4/skillops/api/RunController.java)를 보호한다.
 * 로그인하지 않으면 401 이고, 장부에 있는 번호만 요청 순서대로 시험 주문 번호, 이름, 통과 여부를 담는지, 목록에 없는 시험 주문(f-…)은
 * 이름이 null 인지, 모양이 틀린 번호는 오류 없이 빠지는지 본다.
 */
@WebMvcTest(RunController.class)
@Import({ SecurityConfig.class, TokenService.class })
class RunControllerTest {

    static final String PASSED = "r_" + "a".repeat(32);
    static final String FAILED = "r_" + "b".repeat(32);
    static final String REPLAY = "r_" + "c".repeat(32);
    static final String UNKNOWN = "r_" + "d".repeat(32);

    @Autowired
    MockMvc mockMvc;

    @Autowired
    TokenService tokens;

    @Autowired
    AuthProperties properties;

    @MockitoBean
    RunLedger ledger;

    @MockitoBean
    ScenarioCatalog catalog;

    /** 설정의 데모 계정으로 토큰을 발급해 Authorization 헤더 값을 돌려준다. */
    private String bearer(String username) {
        return "Bearer " + tokens.issue(properties.users().stream().filter(u -> u.username().equals(username))
                .findFirst().orElseThrow()).token();
    }

    @Test
    void requiresLogin() throws Exception {
        mockMvc.perform(get("/api/runs").param("ids", PASSED)).andExpect(status().isUnauthorized());
    }

    @Test
    void namesKnownRunsInRequestOrder() throws Exception {
        given(ledger.find(PASSED)).willReturn(Optional.of(new RunLedger.Run(PASSED, "g-01", true, false, null, null)));
        given(ledger.find(FAILED)).willReturn(Optional.of(new RunLedger.Run(FAILED, "g-05", false, true, null, null)));
        given(ledger.find(REPLAY)).willReturn(Optional.of(new RunLedger.Run(REPLAY, "f-123", false, false, null, null)));
        given(ledger.find(UNKNOWN)).willReturn(Optional.empty());
        given(catalog.ids()).willReturn(Set.of("g-01", "g-05"));
        given(catalog.get("g-01")).willReturn(new Scenario("g-01", "랜턴 정상 주문", null, null, null, null));
        given(catalog.get("g-05")).willReturn(new Scenario("g-05", "미국 배송 주문", null, null, null, null));

        mockMvc.perform(get("/api/runs").param("ids", FAILED + "," + UNKNOWN + "," + PASSED + "," + REPLAY + "," + FAILED)
                .header("Authorization", bearer("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].run_id").value(FAILED))
                .andExpect(jsonPath("$.items[0].scenario_id").value("g-05"))
                .andExpect(jsonPath("$.items[0].scenario_name").value("미국 배송 주문"))
                .andExpect(jsonPath("$.items[0].passed").value(false))
                .andExpect(jsonPath("$.items[1].scenario_id").value("g-01"))
                .andExpect(jsonPath("$.items[1].passed").value(true))
                .andExpect(jsonPath("$.items[2].scenario_id").value("f-123"))
                .andExpect(jsonPath("$.items[2].scenario_name").doesNotExist());
    }

    @Test
    void skipsMalformedIds() throws Exception {
        given(ledger.find(PASSED)).willReturn(Optional.of(new RunLedger.Run(PASSED, "g-01", true, false, null, null)));
        given(catalog.ids()).willReturn(Set.of());

        mockMvc.perform(get("/api/runs").param("ids", "not-a-run," + PASSED).header("Authorization", bearer("owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].run_id").value(PASSED));
    }
}
