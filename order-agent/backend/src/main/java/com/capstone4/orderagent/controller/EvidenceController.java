package com.capstone4.orderagent.controller;

import java.nio.charset.*;
import java.util.*;
import com.capstone4.orderagent.harness.*;
import com.capstone4.orderagent.trace.TraceService;
import com.capstone4.orderagent.workspace.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class EvidenceController {
    private final SnapshotService snapshots;private final TraceService traces;private final SkillQueryService skills;private final boolean demo;
    public EvidenceController(SnapshotService snapshots,TraceService traces,SkillQueryService skills,@Value("${order-agent.demo-enabled}") boolean demo){this.snapshots=snapshots;this.traces=traces;this.skills=skills;this.demo=demo;}
    @GetMapping("/traces/{runId}") public Map<String,Object> legacyTrace(@PathVariable String runId){
        if(!runId.matches("r_[a-fA-F0-9]{32}"))throw new AgentException("EXECUTION_NOT_FOUND");
        String raw=runId.substring(2);String id=raw.substring(0,8)+"-"+raw.substring(8,12)+"-"+raw.substring(12,16)+"-"+raw.substring(16,20)+"-"+raw.substring(20);
        return traces.get(UUID.fromString(id));
    }
    @GetMapping("/executions") public Map<String,Object> recent(){return Map.of("items",traces.recent());}
    // TODO(C-1/B, 공용 계약 확정 후): 현재 trace는 execution_id + events이고 v1은 run_id + steps다.
    // HTTP 계약은 유지하고 호출 측 DTO 변환 위치를 합의한다. run_id는 r_ + UUID 별칭이며 진단 run ID가 아니다.
    // events의 실제 입력/출력/측정 시간만 사용하고 정적 차단·과거 기록에 실행 단계나 시간을 만들지 않는다.
    @GetMapping("/executions/{id}/trace") public Map<String,Object> trace(@PathVariable UUID id){return traces.get(id);}
    // TODO(C-1/B, 공용 계약 확정 후): 현재 {items:[{execution_id,error:{category,skill_id,...}}]}와
    // v1 {failed_runs:[{run_id,failed_skill,error:문자열,skill_versions,...}]}는 다르다.
    // 호출 측에서 식별자·오류 요약/상세를 매핑하되 원래 error 상세는 보존한다.
    // since/50건 제한은 도입하지 않는다. 사용자 합의는 파라미터 없는 전체 live 실패 목록이다.
    @GetMapping("/executions/failed") public Map<String,Object> failed(){return Map.of("items",traces.failed());}
    @GetMapping("/snapshots/{id}/skills") public Map<String,Object> skills(@PathVariable UUID id){return skills.list(id);}
    @GetMapping("/snapshots/{id}/skills/{skillId}") public Map<String,Object> skill(@PathVariable UUID id,@PathVariable String skillId){return skills.get(id,skillId);}
    public record PublishRequest(String fixture){}
    // TODO(B/C-2 연동): 실제 진단 후보는 검증된 workspace 참조로 받아 SnapshotService에 연결한다.
    // 아래 fixture API는 미리 준비한 시연 데이터뿐이며 진단/수정 생성 기능이 아니다.
    // MCP 도구 구현은 이 컨트롤러를 복제하지 말고 내부 서비스 계약을 호출하도록 담당자와 맞춘다.
    @PostMapping("/snapshots") public Map<String,Object> publish(@RequestBody PublishRequest request){
        if(!demo)throw new AgentException("SNAPSHOT_NOT_FOUND");
        if(request.fixture()==null)throw new AgentException("UNKNOWN_FIXTURE");
        return manifest(snapshots.demo(request.fixture()));
    }
    public record CandidateRequest(Map<String,String> files,Map<String,Object> metadata){}
    /** 후보 SKILL.md 묶음을 그대로 snapshot으로 발행한다. 경로·크기 검사는 SnapshotService.publish가 한다. */
    @PostMapping("/snapshots/candidates") public Map<String,Object> publishCandidate(@RequestBody CandidateRequest request){
        if(!demo)throw new AgentException("SNAPSHOT_NOT_FOUND");
        var files=new TreeMap<String,byte[]>();
        if(request.files()!=null)request.files().forEach((path,text)->files.put(path,text.getBytes(StandardCharsets.UTF_8)));
        var metadata=new LinkedHashMap<String,Object>();
        if(request.metadata()!=null)metadata.putAll(request.metadata());
        metadata.put("source","candidate");
        return manifest(snapshots.publish(files,metadata));
    }
    @GetMapping("/snapshots/{id}") public Map<String,Object> snapshot(@PathVariable UUID id){return manifest(snapshots.get(id));}
    @GetMapping("/snapshots/{id}/files") public Map<String,Object> file(@PathVariable UUID id,@RequestParam String path){
        var snapshot=snapshots.get(id);byte[] bytes=snapshot.files().get(path);if(bytes==null)throw new AgentException("SNAPSHOT_FILE_NOT_FOUND");
        String encoding="utf-8",content;
        try{content=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();}
        catch(CharacterCodingException e){encoding="base64";content=Base64.getEncoder().encodeToString(bytes);}
        return Map.of("snapshot_id",id,"path",path,"hash",Json.hash(bytes),"size",bytes.length,"encoding",encoding,"content",content);
    }
    private Map<String,Object> manifest(SnapshotRepository.Stored s){
        return Map.of("snapshot_id",s.id(),"hash",s.hash(),"created_at",s.createdAt().toString(),"metadata",s.metadata(),
            "files",s.files().entrySet().stream().map(e->Map.of("path",e.getKey(),"hash",Json.hash(e.getValue()),"size",e.getValue().length)).toList());
    }
}
