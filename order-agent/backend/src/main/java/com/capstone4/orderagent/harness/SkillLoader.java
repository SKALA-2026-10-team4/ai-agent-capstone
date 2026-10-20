package com.capstone4.orderagent.harness;

import java.nio.charset.StandardCharsets;
import java.util.*;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.constructor.SafeConstructor;

@Component
public class SkillLoader {
    // TODO(A 연동): 공용 Registry/로더의 Skill 정의 타입과 검증 결과로 연결한다.
    // unknown 이름 원문·파일 해시·실제 로드 관찰 계약은 보존한다.
    public record Skill(String id, String description, int version, List<String> inputs,
                        List<String> outputs, List<String> dependsOn, List<String> steps, String body, String path, String hash) {}
    public Skill load(SortedMap<String,byte[]> files, String id) {
        if (id==null || !id.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)*") || id.length()>64) throw new AgentException("INVALID_SKILL_NAME");
        String path="skills/"+id+"/SKILL.md"; byte[] bytes=files.get(path);
        if (bytes==null) throw new AgentException("UNKNOWN_SKILL");
        try {
            String text=new String(bytes,StandardCharsets.UTF_8).replace("\r\n","\n");
            if (!text.startsWith("---\n")) throw new AgentException("INVALID_SKILL_FORMAT");
            int end=text.indexOf("\n---\n",4);
            if(end<0) throw new AgentException("INVALID_SKILL_FORMAT");
            var options=new LoaderOptions(); options.setAllowDuplicateKeys(false); options.setMaxAliasesForCollections(0); options.setCodePointLimit(100000);
            Object parsed=new Yaml(new SafeConstructor(options)).load(text.substring(4,end));
            if(!(parsed instanceof Map<?,?> m) || !id.equals(m.get("name")) || !(m.get("description") instanceof String d) || d.isBlank()
                || !(m.get("version") instanceof Integer v) || v<1 || !(m.get("owner") instanceof String owner) || owner.isBlank())
                throw new AgentException("INVALID_SKILL_FORMAT");
            String body=text.substring(end+5).strip(); if(body.isEmpty()) throw new AgentException("INVALID_SKILL_FORMAT");
            var steps=strings(m,"steps",false);
            return new Skill(id,d,v,strings(m,"inputs",steps.isEmpty()),strings(m,"outputs",steps.isEmpty()),
                strings(m,"depends_on",steps.isEmpty()),steps,body,path,Json.hash(bytes));
        } catch(AgentException e){throw e;}catch(Exception e){throw new AgentException("INVALID_SKILL_FORMAT");}
    }
    private List<String> strings(Map<?,?> m,String key,boolean required){
        Object value=m.get(key);if(value==null&&!required)return List.of();
        if(!(value instanceof List<?> list)||list.stream().anyMatch(x->!(x instanceof String s)||s.isBlank()))throw new AgentException("INVALID_SKILL_FORMAT");
        return list.stream().map(Object::toString).toList();
    }
    public List<Map<String,Object>> candidates(SortedMap<String,byte[]> files){
        return files.keySet().stream().filter(p->p.matches("skills/[a-z][a-z0-9-]*/SKILL\\.md"))
            .map(p->load(files,p.split("/")[1])).map(s->Map.<String,Object>of("skill_id",s.id(),"name",s.id(),"description",s.description(),"version",s.version())).toList();
    }
    /** Validate declared input supply before any business tools run. */
    public List<Skill> validateFlow(SortedMap<String,byte[]> files,Skill flow){
        if(flow.steps().isEmpty()||new HashSet<>(flow.steps()).size()!=flow.steps().size()||flow.steps().size()>12)throw new AgentException("INVALID_FLOW");
        var available=new HashSet<>(List.of("order.id","order.items","order.customer","order.payment_method","order.shipping"));
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
    /** Preserve the exact failing boundary for trace consumers and future diagnosis tools. */
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
