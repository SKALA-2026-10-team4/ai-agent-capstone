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
    private final SnapshotService snapshots;private final TraceService traces;private final boolean demo;
    public EvidenceController(SnapshotService snapshots,TraceService traces,@Value("${order-agent.demo-enabled}") boolean demo){this.snapshots=snapshots;this.traces=traces;this.demo=demo;}
    @GetMapping("/traces/{runId}") public Map<String,Object> legacyTrace(@PathVariable String runId){
        if(!runId.matches("r_[a-fA-F0-9]{32}"))throw new AgentException("EXECUTION_NOT_FOUND");
        String raw=runId.substring(2);String id=raw.substring(0,8)+"-"+raw.substring(8,12)+"-"+raw.substring(12,16)+"-"+raw.substring(16,20)+"-"+raw.substring(20);
        return traces.get(UUID.fromString(id));
    }
    @GetMapping("/executions") public Map<String,Object> recent(){return Map.of("items",traces.recent());}
    @GetMapping("/executions/{id}/trace") public Map<String,Object> trace(@PathVariable UUID id){return traces.get(id);}
    public record PublishRequest(String fixture){}
    // TODO(B/C-2 연동): 실제 진단 후보는 검증된 workspace 참조로 받아 SnapshotService에 연결한다.
    // 아래 fixture API는 미리 준비한 시연 데이터뿐이며 진단/수정 생성 기능이 아니다.
    // MCP 도구 구현은 이 컨트롤러를 복제하지 말고 내부 서비스 계약을 호출하도록 담당자와 맞춘다.
    @PostMapping("/snapshots") public Map<String,Object> publish(@RequestBody PublishRequest request){
        if(!demo)throw new AgentException("SNAPSHOT_NOT_FOUND");
        if(request.fixture()==null)throw new AgentException("UNKNOWN_FIXTURE");
        return manifest(snapshots.demo(request.fixture()));
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
