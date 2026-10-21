package com.capstone4.orderagent.harness;

import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

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
