package com.capstone4.skillops.evaluation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static com.capstone4.skillops.evaluation.EvaluationTypes.*;
import static com.capstone4.skillops.evaluation.EvaluationFixtures.*;

@WebMvcTest(EvaluationController.class)
@Import(EvaluationConfiguration.class)
class EvaluationControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean EvaluationService service;
    /** 새 API는 기존 permitAll 규칙에 노출되지 않는다. */
    @Test void anonymousReadRequiresLogin() throws Exception {
        mvc.perform(get("/api/evaluations/job")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }
    /** 읽기 권한만 있는 사용자는 평가를 시작할 수 없다. */
    @Test void ownerCannotSubmitEvaluation() throws Exception {
        mvc.perform(post("/api/evaluations").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OWNER")))
                .header("Idempotency-Key","key").contentType("application/json").content(EvaluationJson.write(submit(Runner.CONTRACT_ONLY))))
                .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
    /** REST 접수는 실행 완료를 기다리지 않고 같은 서비스로 전달한다. */
    @Test void operatorGets202WithDurableIdentifiers() throws Exception {
        when(service.submit(any(),eq("key"),anyString())).thenReturn(new Accepted("job","attempt",Status.QUEUED,"/api/evaluations/job"));
        mvc.perform(post("/api/evaluations").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR")))
                .header("Idempotency-Key","key").contentType("application/json").content(EvaluationJson.write(submit(Runner.CONTRACT_ONLY))))
                .andExpect(status().isAccepted()).andExpect(jsonPath("evaluation_id").value("job"))
                .andExpect(jsonPath("attempt_id").value("attempt")).andExpect(jsonPath("status").value("QUEUED"));
    }
    /** 예산 기능 미지원은 성공 접수가 아니다. */
    @Test void unsupportedBudgetControlIsStructured() throws Exception {
        when(service.submit(any(),anyString(),anyString())).thenThrow(new EvaluationException(422,"BUDGET_ENFORCEMENT_UNSUPPORTED","미지원"));
        mvc.perform(post("/api/evaluations").with(jwt().authorities(new SimpleGrantedAuthority("ROLE_OPERATOR")))
                .header("Idempotency-Key","key").contentType("application/json").content(EvaluationJson.write(submit(Runner.CONTRACT_ONLY))))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("code").value("BUDGET_ENFORCEMENT_UNSUPPORTED"));
    }
}
