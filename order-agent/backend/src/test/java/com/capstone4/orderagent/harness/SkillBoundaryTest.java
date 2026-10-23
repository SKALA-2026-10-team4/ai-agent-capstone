package com.capstone4.orderagent.harness;

import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 와
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/ExecutionControl.java 의 경계 규칙을 보호한다.
 * 상위 폴더를 가리키는 이름, front matter 없는 파일, 중복 메타데이터 키를 거절하는지, 모든 API 시도가 한 예산을 나눠 쓰고
 * 관측 토큰이 상한을 넘으면 멈추는지, 실행 중 계약 위반이 빠진 입력과 선행 단계를 details 에 담는지 확인한다.
 */
class SkillBoundaryTest {
    @Test void loaderRejectsTraversalInvalidYamlAndDuplicateMetadata(){
        var loader=new SkillLoader();
        assertThatThrownBy(()->loader.load(new TreeMap<>(),"../payment")).hasMessage("INVALID_SKILL_NAME");
        var files=new TreeMap<String,byte[]>();
        files.put("skills/payment/SKILL.md","not frontmatter".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(()->loader.load(files,"payment")).hasMessage("INVALID_SKILL_FORMAT");
        files.put("skills/payment/SKILL.md","---\nname: payment\nname: other\n---\nbody".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(()->loader.load(files,"payment")).hasMessage("INVALID_SKILL_FORMAT");
    }
    @Test void allAttemptsShareBudgetAndObservedTokensDoNotBypassIt(){
        var budget=new ExecutionControl(10000,1,100);
        budget.attempt();assertThatThrownBy(budget::attempt).hasMessage("BUDGET_EXHAUSTED");
        assertThatThrownBy(()->budget.observe(90L,11L)).hasMessage("TOKEN_LIMIT_EXCEEDED");
    }
    @Test void runtimeContractIncludesMissingInputsAndDependencies(){
        var skill=new SkillLoader.Skill("payment","결제",1,List.of("order.id","inventory.reserved"),
            List.of("payment.id"),List.of("inventory-check"),List.of(),"본문","skills/payment/SKILL.md","hash","재무팀");
        var loader=new SkillLoader();
        try{
            loader.validateInputs(skill,Set.of("order.id"),Set.of(),"runtime_validation");
            fail("Contract validation must fail");
        }catch(AgentException e){
            assertThat(e.category()).isEqualTo("CONTRACT_VIOLATION");
            assertThat(e.details().get("skill_id")).isEqualTo("payment");
            assertThat(e.details().get("missing_inputs")).isEqualTo(List.of("inventory.reserved"));
            assertThat(e.details().get("missing_dependencies")).isEqualTo(List.of("inventory-check"));
            assertThat(e.details().get("phase")).isEqualTo("runtime_validation");
        }
    }

}
