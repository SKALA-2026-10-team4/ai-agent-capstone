package com.capstone4.orderagent.harness;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * snapshot 바이트에서 SKILL.md 를 읽어 Skill 레코드로 만들고 검증한다. 후보 목록, flow 정적 검증, 실행 중 입력 검증도 맡는다.
 * 실행 경로(OrderHarness)와 조회 경로(order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillQueryService.java)가
 * 같은 규칙을 쓰도록 한곳에 둔다. 파일 시스템이 아니라 고정된 snapshot 의 파일 맵만 읽으므로 실행 중 파일이 바뀌어도 결과가 흔들리지 않는다.
 * 이 규칙은 다른 모듈이 복제한다. service/src/main/java/com/capstone4/skillops/snapshot/Frontmatter.java 는 YAML 안전 옵션을,
 * service/src/main/java/com/capstone4/skillops/snapshot/DependencyAnalyzer.java 와 frontend/src/utils/flowGraph.js 는 validateFlow 규칙을,
 * frontend/src/utils/frontmatter.js 와 frontend/src/utils/skillSet.js 는 front matter 경계와 필수 값을,
 * skillops-agent/skillops_agent/order_agent.py 는 Skill 이름 규칙을 따라 한다. 규칙을 바꾸면 이 파일들도 함께 맞춘다.
 * order-agent/backend/src/test/java/com/capstone4/orderagent/harness 의 SkillLoaderContractTest 와 SkillBoundaryTest 가 현재 규칙을 고정한다.
 */
@Component
public class SkillLoader {
    // TODO(A 연동): 공용 Registry 나 로더가 생기면 그 Skill 정의 타입과 검증 결과로 연결한다.
    // 연결하더라도 알 수 없는 이름의 원문, 파일 해시, 실제 로드 관찰을 남기는 계약은 지킨다.
    /**
     * SKILL.md 하나를 해석한 결과다. steps 가 있으면 flow Skill 이고 inputs, outputs, depends_on 을 생략할 수 있다.
     * steps 가 없으면 단계 Skill 이고 세 목록이 모두 있어야 한다. body 는 front matter 뒤 본문의 앞뒤 공백을 지운 값이고,
     * path 는 snapshot 안의 경로, hash 는 원본 바이트의 SHA-256 이다.
     * openai 모드에서는 이 레코드가 결정 질문에 그대로 실려 모델이 본문을 읽는다.
     */
    public record Skill(String id, String description, int version, List<String> inputs,
                        List<String> outputs, List<String> dependsOn, List<String> steps, String body, String path, String hash, String owner) {}
    /** '---' 로 둘러싼 front matter YAML 과 그 뒤 본문이다. */
    private record FrontMatter(String yaml, String body) {}
    /** 필수 값 검사를 통과한 front matter 메타데이터다. */
    private record Metadata(Map<?,?> fields, String description, int version, String owner) {}

    /**
     * snapshot 안에서 Skill 파일이 놓이는 경로(skills 폴더 아래 Skill 이름 폴더의 SKILL.md)다. SkillQueryService.get 도 이 경로로 파일을 찾는다.
     * skillops-agent/skillops_agent/contracts.py 의 skill_file_path 와 frontend/src/utils/skillSet.js 의 skillPath 가 같은 모양을 만든다.
     */
    public static String skillPath(String id) { return "skills/"+id+"/SKILL.md"; }
    /**
     * snapshot 의 파일 맵에서 Skill 하나를 읽어 검증한다. 이름 규칙을 먼저 보고(INVALID_SKILL_NAME) 파일이 없으면 UNKNOWN_SKILL 이다.
     * 엄격한 UTF-8 해석, front matter 분리, 필수 값, 비어 있지 않은 본문, 목록 형식 가운데 하나라도 어긋나면 INVALID_SKILL_FORMAT 이다.
     * YAML 파서 예외처럼 예상하지 못한 예외도 INVALID_SKILL_FORMAT 으로 바꾼다.
     * 이름을 경로로 바꾸기 전에 검사하므로 상위 폴더를 가리키는 이름으로 다른 파일을 읽을 수 없다.
     * OrderHarness 가 엔트리 flow 와 단계마다 부르고, candidates, validateFlow, SkillQueryService 도 부른다.
     */
    public Skill load(SortedMap<String,byte[]> files, String id) {
        validateName(id);
        String path=skillPath(id); byte[] bytes=files.get(path);
        if (bytes==null) throw new AgentException("UNKNOWN_SKILL");
        try {
            var text=splitFrontMatter(decodeUtf8(bytes));
            var metadata=requireMetadata(parseMetadata(text.yaml()),id);
            String body=text.body().strip(); if(body.isEmpty()) throw new AgentException("INVALID_SKILL_FORMAT");
            var m=metadata.fields();
            var steps=strings(m,"steps",false);
            return new Skill(id,metadata.description(),metadata.version(),strings(m,"inputs",steps.isEmpty()),strings(m,"outputs",steps.isEmpty()),
                strings(m,"depends_on",steps.isEmpty()),steps,body,path,Json.hash(bytes),metadata.owner());
        } catch(AgentException e){throw e;}catch(Exception e){throw new AgentException("INVALID_SKILL_FORMAT");}
    }
    /**
     * Skill 이름 규칙을 검사한다. 소문자로 시작하고 소문자와 숫자만 쓰며, 하이픈은 한 개씩 단어 사이에만 두고 64자를 넘지 않아야 한다.
     * 어기거나 null 이면 INVALID_SKILL_NAME 이다. 경로 조작을 막는 첫 관문이라 SkillQueryService.get 도 경로를 만들기 전에 부른다.
     * skillops-agent/skillops_agent/order_agent.py 의 SKILL_ID 정규식이 같은 규칙으로 요청을 보내기 전에 거른다.
     */
    public static void validateName(String id) {
        if (id==null || id.length()>64 || !id.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*"))
            throw new AgentException("INVALID_SKILL_NAME");
    }
    /** 잘못된 바이트를 거절하는 UTF-8 로 읽고 줄바꿈을 LF 로 맞춘다. */
    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString().replace("\r\n","\n");
    }
    /** 맨 앞 '---' 줄과 다음 '---' 줄 사이를 front matter 로, 그 뒤를 본문으로 나눈다. 어느 한쪽 구분선이 없으면 INVALID_SKILL_FORMAT 이다. */
    private static FrontMatter splitFrontMatter(String text) {
        if (!text.startsWith("---\n")) throw new AgentException("INVALID_SKILL_FORMAT");
        int end=text.indexOf("\n---\n",4);
        if(end<0) throw new AgentException("INVALID_SKILL_FORMAT");
        return new FrontMatter(text.substring(4,end),text.substring(end+5));
    }
    /**
     * 중복 키, 컬렉션 별칭, 길이를 제한한 안전한 YAML 로 읽는다. SafeConstructor 라 YAML 태그로 임의 Java 객체를 만들 수 없다.
     * Yaml 은 스레드에 안전하지 않아 호출마다 만든다.
     */
    private static Object parseMetadata(String yaml) {
        var options=new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(0); options.setCodePointLimit(100000);
        return new Yaml(new SafeConstructor(options)).load(yaml);
    }
    /**
     * front matter 의 필수 값을 확인한다. name 은 폴더 이름과 같아야 하고, description 과 owner 는 비어 있지 않은 문자열,
     * version 은 1 이상의 정수여야 한다. 하나라도 어긋나면 INVALID_SKILL_FORMAT 이다.
     */
    private static Metadata requireMetadata(Object parsed,String id) {
        if(!(parsed instanceof Map<?,?> m) || !id.equals(m.get("name")) || !(m.get("description") instanceof String d) || d.isBlank()
            || !(m.get("version") instanceof Integer v) || v<1 || !(m.get("owner") instanceof String owner) || owner.isBlank())
            throw new AgentException("INVALID_SKILL_FORMAT");
        return new Metadata(m,d,v,owner);
    }
    /**
     * front matter 의 문자열 목록 값을 읽는다. 값이 없으면 required 일 때만 오류이고 아니면 빈 목록이다.
     * 목록이 아니거나, 비어 있거나 공백뿐인 문자열 또는 문자열이 아닌 항목이 섞여 있으면 INVALID_SKILL_FORMAT 이다.
     */
    private List<String> strings(Map<?,?> m,String key,boolean required){
        Object value=m.get(key);if(value==null&&!required)return List.of();
        if(!(value instanceof List<?> list)||list.stream().anyMatch(x->!(x instanceof String s)||s.isBlank()))throw new AgentException("INVALID_SKILL_FORMAT");
        return list.stream().map(Object::toString).toList();
    }
    /**
     * snapshot 의 최상위 Skill 파일(소문자로 시작하는 이름 폴더 바로 아래 SKILL.md)마다 load 해서 엔트리 후보 목록을 만든다.
     * 항목은 skill_id, name, description, version 이고 파일 맵이 정렬돼 있어 경로 순서를 따른다. 하나라도 깨져 있으면 전체가 실패한다.
     * OrderHarness 가 이 목록을 CANDIDATES_OFFERED 로 남기고 엔트리 선택 질문에 넣는다.
     */
    public List<Map<String,Object>> candidates(SortedMap<String,byte[]> files){
        return files.keySet().stream().filter(p->p.matches("skills/[a-z][a-z0-9-]*/SKILL\\.md"))
            .map(p->load(files,p.split("/")[1])).map(s->Map.<String,Object>of("skill_id",s.id(),"name",s.id(),"description",s.description(),"version",s.version())).toList();
    }
    /**
     * 업무 도구를 하나라도 실행하기 전에 flow 전체의 입력 공급 관계를 검사한다(static_validation).
     * steps 가 비었거나 중복되거나 OrderFlowPolicy.MAX_FLOW_STEPS 를 넘으면 단계를 읽기 전에 INVALID_FLOW 다.
     * 그다음 OrderFlowPolicy.INITIAL_INPUT_KEYS 에서 시작해 단계 순서대로 각 단계의 inputs 와 depends_on 이 앞에서 채워졌는지 보고,
     * 통과한 단계의 outputs 를 공급된 키에 더한다. 단계 자리에 flow Skill 이 오면 NESTED_FLOW_NOT_SUPPORTED 계약 위반이다.
     * 검증한 단계 Skill 목록을 steps 순서대로 돌려준다. OrderHarness.loadValidatedFlow 가 부른다.
     * service 의 DependencyAnalyzer 와 frontend 의 flowGraph.js 가 같은 규칙으로 진단용 그래프를 그리므로 규칙을 바꾸면 함께 고친다.
     */
    public List<Skill> validateFlow(SortedMap<String,byte[]> files,Skill flow){
        if(flow.steps().isEmpty()||new HashSet<>(flow.steps()).size()!=flow.steps().size()||flow.steps().size()>OrderFlowPolicy.MAX_FLOW_STEPS)throw new AgentException("INVALID_FLOW");
        var available=new HashSet<>(OrderFlowPolicy.INITIAL_INPUT_KEYS);
        var done=new HashSet<String>();var skills=new ArrayList<Skill>();
        for(String id:flow.steps()){
            var skill=load(files,id);
            if(!skill.steps().isEmpty())throw new AgentException("CONTRACT_VIOLATION",Map.of(
                "skill_id",skill.id(),"phase","static_validation","reason","NESTED_FLOW_NOT_SUPPORTED",
                "missing_inputs",List.of(),"missing_dependencies",List.of(),"message","단계 Skill에 중첩 flow를 사용할 수 없습니다."));
            validateInputs(skill,available,done,"static_validation");
            skills.add(skill);done.add(id);available.addAll(skill.outputs());
        }
        return skills;
    }
    /**
     * Skill 의 inputs 가 모두 available 에 있고 depends_on 이 모두 completed 에 있는지 검사한다. 빠진 것이 있으면 CONTRACT_VIOLATION 을
     * 던지고 details 에 skill_id, phase, missing_inputs, missing_dependencies, message 를 담는다.
     * 실패한 경계를 정확히 남겨야 trace 를 읽는 쪽과 진단 도구가 어느 단계에서 어떤 값이 빠졌는지 알 수 있다.
     * 이 details 는 EXECUTION_ERROR 의 data 가 되고, service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java 의
     * errorText 가 missing_inputs 와 missing_dependencies 로 오류 문장을 만든다.
     * validateFlow 가 static_validation 으로, OrderHarness.runStep 이 runtime_validation 으로 부른다.
     */
    public void validateInputs(Skill skill,Set<String> available,Set<String> completed,String phase){
        var missingInputs=skill.inputs().stream().filter(key->!available.contains(key)).toList();
        var missingDependencies=skill.dependsOn().stream().filter(id->!completed.contains(id)).toList();
        if(!missingInputs.isEmpty()||!missingDependencies.isEmpty()){
            throw new AgentException("CONTRACT_VIOLATION",Map.of(
                "skill_id",skill.id(),"phase",phase,"missing_inputs",missingInputs,
                "missing_dependencies",missingDependencies,
                "message","Skill에 필요한 입력 또는 선행 단계가 충족되지 않았습니다."));
        }
    }

}
