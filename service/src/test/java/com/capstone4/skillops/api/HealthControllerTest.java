package com.capstone4.skillops.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * service/src/main/java/com/capstone4/skillops/api/HealthController.java 의 GET /api/health 응답을 보호한다.
 * 이 slice 에도 service/src/main/java/com/capstone4/skillops/auth/SecurityConfig.java 가 자동으로 걸린다. 그래서 토큰 없이 200 과
 * 고정 본문을 받는지 보면, 이 경로가 인증 없이 열려 있는지도 함께 본다.
 */
@WebMvcTest(HealthController.class)
class HealthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @Test
    void health_returns_ok() throws Exception {
        mockMvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.service").value("skillops-service"));
    }
}
