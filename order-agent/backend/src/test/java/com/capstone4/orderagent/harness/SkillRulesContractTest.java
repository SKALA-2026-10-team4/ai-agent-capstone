package com.capstone4.orderagent.harness;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 의 실행 규칙(rules) 정적 검사가
 * service 의 apply_patch 와 같은 공용 사례 docs/contracts/examples/skill-rules-cases.json 대로 판정하는지 확인한다.
 * metadata_errors 가 있는 Skill 은 load 가 INVALID_SKILL_FORMAT 으로, flow_errors 가 있는 사례는 validateFlow 가
 * CONTRACT_VIOLATION(details.reason RULE_KEY_NOT_INPUT)으로 거절해야 하고, 나머지는 모두 통과해야 한다.
 * 규칙이 없는 Skill 의 결정 질문 JSON 과 레코드 값이 그대로인지도 함께 본다.
 */
class SkillRulesContractTest {
    /** 사례 파일의 오류 줄 하나다. metadata_errors 는 skill, field 를, flow_errors 는 skill, code, reason 을 쓴다. */
    record Error(String skill, String field, String code, String reason) {}
    /** 사례 하나다. skills 는 Skill 이름과 SKILL.md 원문의 짝이다. */
    record Case(String id, Map<String,String> skills, List<Error> metadataErrors, List<Error> flowErrors) {
        /** 매개변수 테스트 이름에는 긴 원문 대신 사례 id 만 표시한다. */
        @Override public String toString(){return id;}
    }
    /** 사례 파일 전체다. */
    record Contract(String description, int version, String keyRule, List<Case> cases) {}

    final SkillLoader loader=new SkillLoader();

    /** 공용 사례마다 형식 오류는 load 에서, 조건 키 오류는 validateFlow 에서 같은 Skill 로 나는지 비교한다. */
    @ParameterizedTest(name="{index}: {0}")
    @MethodSource("cases")
    void matchesSharedRulesContract(Case testCase){
        var files=new TreeMap<String,byte[]>();
        testCase.skills().forEach((id,text)->files.put(SkillLoader.skillPath(id),text.getBytes(StandardCharsets.UTF_8)));
        var broken=testCase.metadataErrors().stream().map(Error::skill).toList();
        for(String id:testCase.skills().keySet()){
            if(broken.contains(id))assertThatThrownBy(()->loader.load(files,id)).as(testCase.id()+" "+id)
                .isInstanceOf(AgentException.class).hasMessage("INVALID_SKILL_FORMAT");
            else assertThat(loader.load(files,id).id()).as(testCase.id()+" "+id).isEqualTo(id);
        }
        if(!broken.isEmpty())return;
        var flow=loader.load(files,"order-flow");
        if(testCase.flowErrors().isEmpty()){
            assertThat(loader.validateFlow(files,flow)).as(testCase.id()).hasSize(flow.steps().size());
            return;
        }
        var expected=testCase.flowErrors().getFirst();
        assertThatThrownBy(()->loader.validateFlow(files,flow)).as(testCase.id()).isInstanceOfSatisfying(AgentException.class,error->{
            assertThat(error.category()).isEqualTo(expected.code());
            assertThat(error.details()).containsEntry("reason",expected.reason()).containsEntry("skill_id",expected.skill())
                .containsEntry("phase","static_validation");
        });
    }
    /** 규칙은 위에서부터 순서대로 읽히고 when 원문, then, reason, 읽은 조건을 담는다. */
    @Test void rulesAreParsedInOrder(){
        var files=files(caseById("valid-two-rules"));
        var payment=loader.load(files,"payment");
        assertThat(payment.rules()).extracting(SkillLoader.Rule::when,SkillLoader.Rule::then,SkillLoader.Rule::reason).containsExactly(
            tuple("order.total >= 100000","hold","HIGH_AMOUNT"),tuple("order.payment_method == 'card'","reject","NO_CARD"));
        assertThat(payment.rules().getFirst().condition()).isEqualTo(RuleCondition.parse("order.total >= 100000"));
        assertThat(loader.load(files,"order-intake").rules()).isEmpty();
    }
    /** 선언하지 않은 조건 키 오류는 빈 missing_inputs 와 함께 선언하지 않은 키 목록을 rule_keys 와 message 에 남긴다. */
    @Test void undeclaredRuleKeyDetailsNameTheKeys(){
        var files=files(caseById("key-not-input"));
        var failure=(AgentException)catchThrowable(()->loader.validateFlow(files,loader.load(files,"order-flow")));
        assertThat(failure.details()).isEqualTo(Map.of("skill_id","payment","phase","static_validation","reason","RULE_KEY_NOT_INPUT",
            "missing_inputs",List.of(),"missing_dependencies",List.of(),"rule_keys",List.of("order.total"),
            "message","실행 규칙 조건의 값 이름이 Skill의 선언 입력이 아닙니다: order.total"));
    }
    /**
     * 따옴표 없는 YAML 1.1 불리언 낱말은 reason 모양(대문자·숫자·밑줄)과 맞아도 Boolean 으로 읽혀 형식 오류다. 사례 파일의 NO, ON 밖의 낱말도 같고,
     * 따옴표로 감싼 같은 낱말과 불리언이 아닌 비슷한 낱말(Y, NOPE)은 글자라 받는다.
     */
    @Test void yamlBooleanWordsAreNotRuleReasons(){
        var base=caseById("invalid-reason-yaml-bool-no");
        for(String word:List.of("YES","NO","ON","OFF","TRUE","FALSE")){
            var files=files(base);files.put(SkillLoader.skillPath("payment"),payment(base,"reason: "+word));
            assertThatThrownBy(()->loader.load(files,"payment")).as(word).isInstanceOf(AgentException.class).hasMessage("INVALID_SKILL_FORMAT");
        }
        for(String reason:List.of("\"NO\"","'OFF'","Y","NOPE")){
            var files=files(base);files.put(SkillLoader.skillPath("payment"),payment(base,"reason: "+reason));
            assertThat(loader.load(files,"payment").rules().getFirst().reason()).as(reason).isEqualTo(reason.replaceAll("[\"']",""));
        }
    }
    /** 사례의 payment 원문에서 reason 줄을 바꾼 바이트를 만든다. */
    static byte[] payment(Case testCase,String reasonLine){
        return testCase.skills().get("payment").replace("reason: NO",reasonLine).getBytes(StandardCharsets.UTF_8);
    }
    /** flow Skill 의 빈 rules 목록과 null 은 규칙이 없는 것이라 허용한다. 규칙이 하나라도 있으면 형식 오류다. */
    @Test void flowSkillMayCarryOnlyAnEmptyRulesList(){
        String flow=caseById("valid-hold").skills().get("order-flow");
        for(String empty:List.of("rules: []\n","rules:\n")){
            var files=new TreeMap<String,byte[]>();
            files.put(SkillLoader.skillPath("order-flow"),flow.replace("steps: [order-intake, payment]\n","steps: [order-intake, payment]\n"+empty).getBytes(StandardCharsets.UTF_8));
            assertThat(loader.load(files,"order-flow").rules()).as(empty).isEmpty();
        }
    }
    /** 규칙이 있어도 결정 질문 JSON 에는 rules 가 실리지 않아, openai 모드의 사용자 메시지는 규칙이 없는 Skill 과 같은 모양이다. */
    @Test void decisionQuestionJsonLeavesRulesOut(){
        var payment=loader.load(files(caseById("valid-hold")),"payment");
        assertThat(payment.rules()).hasSize(1);
        var withoutRules=new SkillLoader.Skill(payment.id(),payment.description(),payment.version(),payment.inputs(),payment.outputs(),
            payment.dependsOn(),payment.steps(),payment.body(),payment.path(),payment.hash(),payment.owner());
        var definition=Map.<String,Object>of("name","payment.charge","description","d");
        String json=Json.write(DecisionProvider.Question.tool(payment,Map.of("order.id","o-1"),definition));
        assertThat(json).doesNotContain("rules").doesNotContain("PREPAID_ONLY")
            .isEqualTo(Json.write(DecisionProvider.Question.tool(withoutRules,Map.of("order.id","o-1"),definition)));
        assertThat(withoutRules.rules()).isEmpty();
    }

    /** 사례 파일을 읽어 사례 목록을 제공한다. */
    static Stream<Case> cases(){return contract().cases().stream();}
    /** 저장소의 공용 rules 사례 파일을 snake_case 키로 읽는다. */
    static Contract contract(){
        return JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()
            .readValue(Path.of("../../docs/contracts/examples/skill-rules-cases.json").toFile(),Contract.class);
    }
    /** id 로 사례 하나를 찾는다. */
    static Case caseById(String id){return cases().filter(c->c.id().equals(id)).findFirst().orElseThrow();}
    /** 사례의 Skill 원문을 snapshot 파일 맵으로 바꾼다. */
    static SortedMap<String,byte[]> files(Case testCase){
        var files=new TreeMap<String,byte[]>();
        testCase.skills().forEach((id,text)->files.put(SkillLoader.skillPath(id),text.getBytes(StandardCharsets.UTF_8)));
        return files;
    }
}
