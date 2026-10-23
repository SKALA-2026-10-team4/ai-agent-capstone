package com.capstone4.orderagent.harness;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/harness/SkillLoader.java 의 SKILL.md 해석과 검증, 후보 목록,
 * flow 정적 검증, 실행 중 입력 검증의 현재 규칙을 고정하는 특성 테스트다. 같은 규칙을 service, frontend, skillops-agent 가 복제하므로
 * 이 테스트의 기대값을 바꿀 때는 SkillLoader 클래스 주석에 적힌 복제본도 함께 맞춘다.
 */
class SkillLoaderContractTest {
    final SkillLoader loader=new SkillLoader();

    /** 단계 Skill 본문을 front matter 와 함께 만든다. */
    static String step(String name,String inputs,String outputs,String dependsOn){
        return "---\nname: "+name+"\ndescription: "+name+" 설명\ninputs: "+inputs+"\noutputs: "+outputs
            +"\ndepends_on: "+dependsOn+"\nowner: 주문운영팀\nversion: 1\n---\n# "+name+"\n\n본문입니다.\n";
    }
    /** flow Skill 본문을 front matter 와 함께 만든다. */
    static String flow(String steps){
        return "---\nname: order-flow\ndescription: 흐름\nsteps: "+steps+"\nowner: 주문운영팀\nversion: 2\n---\n# 흐름\n\n순서대로 처리합니다.\n";
    }
    /** 경로별 문자열을 snapshot 바이트 묶음으로 바꾼다. */
    static SortedMap<String,byte[]> files(Map<String,String> texts){
        var files=new TreeMap<String,byte[]>();
        texts.forEach((path,text)->files.put(path,text.getBytes(StandardCharsets.UTF_8)));
        return files;
    }
    /** 이름 하나의 Skill 파일만 담은 묶음을 만든다. */
    static SortedMap<String,byte[]> one(String name,String text){return files(Map.of("skills/"+name+"/SKILL.md",text));}

    /** 정상 단계 Skill 은 선언 값, 앞뒤 공백을 지운 본문, 경로, 원본 바이트 해시로 해석된다. CRLF 파일도 본문은 같고 해시는 원본 바이트를 따른다. */
    @Test void stepSkillFieldsAreParsedFromFrontMatter(){
        String text=step("payment","[order.id, order.total]","[payment.id]","[inventory-check]");
        var skill=loader.load(one("payment",text),"payment");
        assertThat(skill).isEqualTo(new SkillLoader.Skill("payment","payment 설명",1,List.of("order.id","order.total"),
            List.of("payment.id"),List.of("inventory-check"),List.of(),"# payment\n\n본문입니다.","skills/payment/SKILL.md",
            Json.hash(text.getBytes(StandardCharsets.UTF_8)),"주문운영팀"));
        String crlf=text.replace("\n","\r\n");
        var normalized=loader.load(one("payment",crlf),"payment");
        assertThat(normalized.body()).isEqualTo("# payment\n\n본문입니다.");
        assertThat(normalized.hash()).isEqualTo(Json.hash(crlf.getBytes(StandardCharsets.UTF_8)));
    }
    /** flow Skill 은 inputs, outputs, depends_on 이 없어도 되고 각각 빈 목록이 된다. */
    @Test void flowSkillMayOmitStepContracts(){
        var skill=loader.load(one("order-flow",flow("[order-intake, payment]")),"order-flow");
        assertThat(skill.steps()).containsExactly("order-intake","payment");
        assertThat(skill.inputs()).isEmpty();assertThat(skill.outputs()).isEmpty();assertThat(skill.dependsOn()).isEmpty();
        assertThat(skill.version()).isEqualTo(2);
    }
    /** 이름 규칙 검사가 파일 조회보다 먼저이고, 파일이 없으면 UNKNOWN_SKILL 이다. */
    @Test void namesAreValidatedBeforeLookup(){
        for(String bad:Arrays.asList(null,"","Payment","1abc","a--b","a-","-a","a_b","a/b","../payment","a".repeat(65)))
            assertThatThrownBy(()->loader.load(new TreeMap<>(),bad)).as(String.valueOf(bad)).hasMessage("INVALID_SKILL_NAME");
        assertThatThrownBy(()->SkillLoader.validateName("a".repeat(65))).hasMessage("INVALID_SKILL_NAME");
        SkillLoader.validateName("a".repeat(64));
        SkillLoader.validateName("a1-b2");
        assertThatThrownBy(()->loader.load(new TreeMap<>(),"payment")).isInstanceOf(AgentException.class).hasMessage("UNKNOWN_SKILL");
        assertThatThrownBy(()->loader.load(one("payment",step("payment","[a]","[b]","[]")),"notify")).hasMessage("UNKNOWN_SKILL");
    }
    /** front matter, 필수 값, 본문, 목록 형식 가운데 하나라도 어긋나면 모두 INVALID_SKILL_FORMAT 이다. */
    @Test void malformedSkillsAreInvalidFormat(){
        String valid=step("payment","[order.id]","[payment.id]","[]");
        var broken=new LinkedHashMap<String,String>();
        broken.put("no front matter","# payment");
        broken.put("unclosed front matter","---\nname: payment\n");
        broken.put("name mismatch",valid.replace("name: payment","name: notify"));
        broken.put("blank description",valid.replace("description: payment 설명","description: \" \""));
        broken.put("version zero",valid.replace("version: 1","version: 0"));
        broken.put("version text",valid.replace("version: 1","version: \"1\""));
        broken.put("missing owner",valid.replace("owner: 주문운영팀\n",""));
        broken.put("empty body",valid.substring(0,valid.indexOf("---\n",4)+4)+"  \n");
        broken.put("inputs missing",valid.replace("inputs: [order.id]\n",""));
        broken.put("inputs not a list",valid.replace("inputs: [order.id]","inputs: order.id"));
        broken.put("blank list item",valid.replace("inputs: [order.id]","inputs: [order.id, \" \"]"));
        broken.put("non string item",valid.replace("inputs: [order.id]","inputs: [order.id, 3]"));
        broken.put("yaml list root","---\n- a\n---\nbody");
        broken.put("duplicate key",valid.replace("version: 1","version: 1\nversion: 2"));
        broken.put("alias",valid.replace("inputs: [order.id]","inputs: &x [order.id]").replace("outputs: [payment.id]","outputs: *x"));
        broken.forEach((reason,text)->assertThatThrownBy(()->loader.load(one("payment",text),"payment")).as(reason)
            .isInstanceOf(AgentException.class).hasMessage("INVALID_SKILL_FORMAT"));
        assertThatThrownBy(()->loader.load(files(Map.of()),"payment")).hasMessage("UNKNOWN_SKILL");
        var invalidUtf8=new TreeMap<String,byte[]>();invalidUtf8.put("skills/payment/SKILL.md",new byte[]{(byte)0xc3,(byte)0x28});
        assertThatThrownBy(()->loader.load(invalidUtf8,"payment")).hasMessage("INVALID_SKILL_FORMAT");
    }
    /** 후보는 skills 폴더 바로 아래 소문자로 시작하는 이름 폴더의 SKILL.md 만 경로 순서로 담고, 하나라도 깨지면 전체가 실패한다. */
    @Test void candidatesListOnlyTopLevelSkillFilesInPathOrder(){
        var texts=new LinkedHashMap<String,String>();
        texts.put("skills/payment/SKILL.md",step("payment","[order.id]","[payment.id]","[]"));
        texts.put("skills/notify/SKILL.md",step("notify","[order.id]","[notify.sent]","[]"));
        texts.put("skills/9lives/SKILL.md","무시되는 파일");
        texts.put("skills/notify/resources/SKILL.md","무시되는 파일");
        texts.put("skills/notify/policy.md","무시되는 파일");
        var candidates=loader.candidates(files(texts));
        assertThat(candidates).containsExactly(
            Map.of("skill_id","notify","name","notify","description","notify 설명","version",1),
            Map.of("skill_id","payment","name","payment","description","payment 설명","version",1));
        texts.put("skills/broken/SKILL.md","front matter 없음");
        assertThatThrownBy(()->loader.candidates(files(texts))).hasMessage("INVALID_SKILL_FORMAT");
    }
    /** flow 는 비어 있거나 중복되거나 12개를 넘으면 INVALID_FLOW 이고, 그 검사는 단계 로드보다 먼저다. */
    @Test void flowShapeIsCheckedBeforeLoadingSteps(){
        var names=new ArrayList<String>();for(int i=1;i<=13;i++)names.add("step-"+i);
        assertThatThrownBy(()->loader.validateFlow(new TreeMap<>(),flowSkill(names))).hasMessage("INVALID_FLOW");
        assertThatThrownBy(()->loader.validateFlow(new TreeMap<>(),flowSkill(names.subList(0,12)))).hasMessage("UNKNOWN_SKILL");
        assertThatThrownBy(()->loader.validateFlow(new TreeMap<>(),flowSkill(List.of()))).hasMessage("INVALID_FLOW");
        assertThatThrownBy(()->loader.validateFlow(new TreeMap<>(),flowSkill(List.of("a","b","a")))).hasMessage("INVALID_FLOW");
    }
    /** 정적 검증은 order.total 을 뺀 다섯 초기 입력 키와 앞 단계 출력으로 입력과 선행 단계를 확인한다. */
    @Test void staticValidationStartsFromTheFiveInitialKeys(){
        var texts=new LinkedHashMap<String,String>();
        texts.put("skills/intake/SKILL.md",step("intake","[order.id, order.items, order.customer, order.payment_method, order.shipping]","[order.total]","[]"));
        texts.put("skills/pay/SKILL.md",step("pay","[order.id, order.total]","[payment.id]","[intake]"));
        var skills=loader.validateFlow(files(texts),flowSkill(List.of("intake","pay")));
        assertThat(skills).extracting(SkillLoader.Skill::id).containsExactly("intake","pay");
        var failure=(AgentException)catchThrowable(()->loader.validateFlow(files(texts),flowSkill(List.of("pay","intake"))));
        assertThat(failure.category()).isEqualTo("CONTRACT_VIOLATION");
        assertThat(failure.details()).isEqualTo(Map.of("skill_id","pay","phase","static_validation","missing_inputs",List.of("order.total"),
            "missing_dependencies",List.of("intake"),"message","Skill에 필요한 입력 또는 선행 단계가 충족되지 않았습니다."));
    }
    /** 단계 자리에 flow Skill 이 오면 NESTED_FLOW_NOT_SUPPORTED 계약 위반이다. */
    @Test void nestedFlowStepIsAContractViolation(){
        var texts=Map.of("skills/order-flow/SKILL.md",flow("[payment]"));
        var failure=(AgentException)catchThrowable(()->loader.validateFlow(files(texts),flowSkill(List.of("order-flow"))));
        assertThat(failure.category()).isEqualTo("CONTRACT_VIOLATION");
        assertThat(failure.details()).isEqualTo(Map.of("skill_id","order-flow","phase","static_validation","reason","NESTED_FLOW_NOT_SUPPORTED",
            "missing_inputs",List.of(),"missing_dependencies",List.of(),"message","단계 Skill에 중첩 flow를 사용할 수 없습니다."));
    }
    /** 실행 중 검증은 주어진 키와 완료 단계로만 판단하고 같은 상세 모양을 쓴다. */
    @Test void runtimeValidationUsesTheGivenPhaseAndKeys(){
        var skill=new SkillLoader.Skill("notify","알림",1,List.of("order.id","shipping.id"),List.of("notify.sent"),
            List.of("shipping-reserve"),List.of(),"본문","skills/notify/SKILL.md","hash","팀");
        loader.validateInputs(skill,Set.of("order.id","shipping.id"),Set.of("shipping-reserve"),"runtime_validation");
        var failure=(AgentException)catchThrowable(()->loader.validateInputs(skill,Set.of("order.id"),Set.of(),"runtime_validation"));
        assertThat(failure.details()).isEqualTo(Map.of("skill_id","notify","phase","runtime_validation","missing_inputs",List.of("shipping.id"),
            "missing_dependencies",List.of("shipping-reserve"),"message","Skill에 필요한 입력 또는 선행 단계가 충족되지 않았습니다."));
    }

    /** 지정한 단계 목록을 가진 flow Skill 레코드를 만든다. */
    static SkillLoader.Skill flowSkill(List<String> steps){
        return new SkillLoader.Skill("order-flow","흐름",1,List.of(),List.of(),List.of(),steps,"본문","skills/order-flow/SKILL.md","hash","팀");
    }
}
