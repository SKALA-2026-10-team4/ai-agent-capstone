package com.capstone4.orderagent.controller;

import java.nio.charset.*;
import java.util.*;
import com.capstone4.orderagent.harness.*;
import com.capstone4.orderagent.trace.RunId;
import com.capstone4.orderagent.trace.TraceService;
import com.capstone4.orderagent.workspace.*;
import org.springframework.web.bind.annotation.*;

/**
 * 진단과 시연에 쓰는 실행 증거 HTTP API 를 모은 컨트롤러다. 실행 기록(trace) 조회, 최근 실행과 실패 실행 목록, snapshot 의 manifest 와
 * 파일과 Skill 조회, Skill 수정 저장, 후보 snapshot 발행, 시연 fixture 고정, 수정 저장 이벤트의 전달 상태 조회를 맡는다.
 * 일은 order-agent/backend/src/main/java/com/capstone4/orderagent/trace/ 의 TraceService 와
 * order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/ 의 SkillQueryService, SnapshotService, SkillChangeDispatcher 에 맡기고,
 * 이 클래스는 경로와 응답 모양만 정한다.
 * 실패는 AgentException 으로 나가며 order-agent/backend/src/main/java/com/capstone4/orderagent/exception/GlobalExceptionHandler.java 가
 * category 가 NOT_FOUND 로 끝나면 404, 아니면 400 으로 바꾼다.
 *
 * 응답의 키 이름과 값 타입은 모듈 밖에서 읽는 계약이다. 특히 skillops-agent/skillops_agent/order_agent.py 는 응답을 가공하지 않고 돌려주고,
 * skillops-agent/skillops_agent/nodes.py 의 hypothesize 는 trace 응답을 그대로 LLM 프롬프트에 넣으므로 키를 바꾸면 진단 입력이 바뀐다.
 * order-agent/backend/src/test/java/com/capstone4/orderagent/integration/TraceContractIntegrationTest.java,
 * order-agent/backend/src/test/java/com/capstone4/orderagent/integration/EvidenceApiContractIntegrationTest.java,
 * order-agent/backend/src/test/java/com/capstone4/orderagent/integration/DiagnosisReadApiIntegrationTest.java 가 응답 모양을 고정한다.
 * 경로마다의 호출처는 각 메서드 주석에 적었다. 명세 문서는 order-agent/docs/diagnosis-read-api.md 와 order-agent/docs/agent-evidence.md 다.
 */
@RestController
@RequestMapping("/api")
public class EvidenceController {
    private final SnapshotService snapshots;private final TraceService traces;private final SkillQueryService skills;private final DemoGuard demo;private final SkillChangeDispatcher dispatcher;
    /** Spring 이 snapshot, trace, Skill 조회, demo 가드, 전달 상태 협력 객체를 주입해 빈으로 만든다. */
    public EvidenceController(SnapshotService snapshots,TraceService traces,SkillQueryService skills,DemoGuard demo,SkillChangeDispatcher dispatcher){this.dispatcher=dispatcher;this.snapshots=snapshots;this.traces=traces;this.skills=skills;this.demo=demo;}
    /**
     * GET /api/traces/{runId}: run_id 별칭(r_ 뒤에 32자리 16진수)으로 실행 기록을 읽는다. 응답은 trace 와 같다.
     * 기존 계약용 별칭 경로이며, 지금 저장소에서는 통합 테스트만 이 경로를 부르고 다른 모듈은 execution_id 경로를 쓴다.
     * 별칭 해석은 order-agent/backend/src/main/java/com/capstone4/orderagent/trace/RunId.java 의 parse 가 하고,
     * 형식이 틀리면 없는 실행과 같은 EXECUTION_NOT_FOUND(404)다. 16진수는 대소문자를 가리지 않는다.
     */
    @GetMapping("/traces/{runId}") public Map<String,Object> legacyTrace(@PathVariable String runId){
        return traces.get(RunId.parse(runId));
    }
    /**
     * GET /api/executions: 최근 실행 최대 20개의 요약을 시작 시각 내림차순으로 {items:[...]} 에 담는다. 이벤트는 넣지 않고,
     * live 와 dry_run 실행이 모두 나온다. frontend/src/api/orderAgent.js 의 listExecutions(frontend/src/stores/workspace.js 가 부른다),
     * order-agent/frontend/src/api/evidence.js 의 listExecutions, order-agent/scripts/check-evidence.py 가 부른다.
     */
    @GetMapping("/executions") public Map<String,Object> recent(){return Map.of("items",traces.recent());}
    // TODO(C-1/B, 공용 계약 확정 후): 지금 trace 응답은 execution_id 와 events 로 되어 있고, v1 계약은 run_id 와 steps 를 쓴다.
    // HTTP 계약은 그대로 두고, 호출하는 쪽에서 DTO 를 어디서 바꿀지 합의한다. run_id 는 실행 UUID 에서 만든 r_ 별칭이며 진단 run ID 가 아니다.
    // 바꿀 때는 events 에 실제로 남은 입력, 출력, 측정 시간만 쓴다. 정적 검사에서 막힌 실행이나 과거 기록에 없던 실행 단계나 시간을 만들어 넣지 않는다.
    /**
     * GET /api/executions/{id}/trace: 실행 하나의 요약과 sequence 순 이벤트 전체를 돌려준다. 실패한 실행을 읽는 것도 정상 응답이다.
     * 없는 실행은 EXECUTION_NOT_FOUND(404), UUID 가 아닌 id 는 VALIDATION_ERROR(400)다.
     * 호출처는 frontend/src/api/orderAgent.js 의 getTrace(frontend/src/views/ExecutionView.vue), order-agent/frontend/src/api/evidence.js 의 getTrace,
     * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java 의 trace, skillops-agent/skillops_agent/order_agent.py 의 get_trace,
     * order-agent/scripts/check-evidence.py, skillops-agent/scripts/b_c_contract_smoke.py, skillops-agent/scripts/c2_real_judge.py 다. service 는 이 응답을
     * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentTraceConverter.java 로 채점용 결과로 바꾼다.
     */
    @GetMapping("/executions/{id}/trace") public Map<String,Object> trace(@PathVariable UUID id){return traces.get(id);}
    // TODO(C-1/B, 공용 계약 확정 후): 지금 응답 {items:[{execution_id, error:{category, skill_id, ...}}]} 은
    // v1 계약 {failed_runs:[{run_id, failed_skill, error(문자열), skill_versions, ...}]} 과 모양이 다르다.
    // 호출하는 쪽에서 식별자와 오류 요약, 오류 상세를 짝지어 바꾸되 원래 error 상세는 지우지 않는다.
    // since 파라미터와 50건 제한은 넣지 않는다. 사용자와 합의한 계약은 파라미터 없이 live 실패 전체를 돌려주는 목록이다.
    /**
     * GET /api/executions/failed: 진단 대상인 live 실행 실패(completion 이 FAILED 나 INTERRUPTED)를 개수 제한 없이 최신순으로 {items:[...]} 에 담는다.
     * 재고 부족이나 결제 거절 같은 업무 거절은 실행이 COMPLETED 라 들어가지 않고, 모의 실행(dry_run)도 빠진다.
     * frontend/src/api/orderAgent.js 의 listFailedExecutions(frontend/src/views/OrderView.vue), skillops-agent/scripts/b_c_contract_smoke.py,
     * skillops-agent/scripts/c2_real_judge.py 가 부른다. skillops-agent/skillops_agent/order_agent.py 의 list_failed_runs 도 이 경로를 부르지만
     * 지금 진단 노드는 그 함수를 쓰지 않는다.
     */
    @GetMapping("/executions/failed") public Map<String,Object> failed(){return Map.of("items",traces.failed());}
    /**
     * GET /api/snapshots/{id}/skills: snapshot 의 Skill 메타데이터 목록을 실행 로더와 같은 규칙으로 해석해 돌려준다.
     * 해석하지 못한 Skill 은 items 에서 빠지고 parse_errors 에 남으며 partial 이 true 가 된다. SkillQueryService.list 가 만든다.
     * frontend/src/api/orderAgent.js 의 listSnapshotSkills(frontend/src/api/skillSets.js 의 loadSkillSet)와
     * skillops-agent/skillops_agent/order_agent.py 의 list_skills 가 부른다.
     */
    @GetMapping("/snapshots/{id}/skills") public Map<String,Object> skills(@PathVariable UUID id){return skills.list(id);}
    /**
     * GET /api/snapshots/{id}/skills/{skillId}: Skill 하나의 메타데이터와 SKILL.md 원문 전체(content)를 돌려준다. SkillQueryService.get 이 만든다.
     * 이름 형식이 틀리면 INVALID_SKILL_NAME(400), 파일이 없으면 SKILL_NOT_FOUND(404), 메타데이터를 해석하지 못하면 SKILL_METADATA_INVALID(400)다.
     * frontend/src/api/orderAgent.js 의 getSnapshotSkill(frontend/src/api/skillSets.js 의 loadSkillSet),
     * skillops-agent/skillops_agent/order_agent.py 의 get_skill, skillops-agent/scripts/b_c_contract_smoke.py 가 부른다.
     */
    @GetMapping("/snapshots/{id}/skills/{skillId}") public Map<String,Object> skill(@PathVariable UUID id,@PathVariable String skillId){return skills.get(id,skillId);}
    /**
     * POST /api/snapshots 본문이다. fixture 는 고정할 시연 fixture 이름이다. 모르는 JSON 키는
     * order-agent/backend/src/main/resources/application.yml 의 fail-on-unknown-properties 설정 때문에 MALFORMED_JSON(400)이 된다.
     * 아래 두 요청 record 도 같다.
     */
    public record PublishRequest(String fixture){}
    /**
     * GET /api/skill-changes/{id}: Skill 수정 저장 이벤트(change_id)가 service 로 전달됐는지 돌려준다. SkillChangeDispatcher.status 가
     * outbox 행에 delivery_status(PENDING 또는 ACCEPTED)를 붙여 만들고, 없는 변경이면 SKILL_CHANGE_NOT_FOUND(404)다.
     * frontend/src/api/diagnoses.js 의 getChangeDelivery 가 부르고, frontend/src/views/DiagnosisView.vue 는 thread_id 가 생기면 그 진단 화면으로 옮긴다.
     */
    @GetMapping("/skill-changes/{id}")
    public Map<String,Object> changeDelivery(@PathVariable UUID id){return dispatcher.status(id);}
    /**
     * POST /api/snapshots/edits 본문이다. JSON 키는 baseline_snapshot_id, baseline_hash, changes 이고 changes 는 SKILL.md 경로와 새 본문의 짝이다.
     * baseline_hash 는 사용자가 편집을 시작한 snapshot 의 해시이며, 지금 저장된 해시와 다르면 BASELINE_HASH_MISMATCH 다.
     */
    public record EditRequest(UUID baselineSnapshotId,String baselineHash,Map<String,String> changes){}
    /**
     * POST /api/snapshots/edits: 사용자가 고친 SKILL.md 들을 기준 snapshot 위에 덮어 새 snapshot 으로 저장하고 201 을 돌려준다.
     * SnapshotService.saveEdits 가 검사, 저장, 진단 요청 outbox 기록을 한 트랜잭션으로 하고 editResponse 가 응답을 만든다.
     * 계약이 깨진 Skill 도 진단할 수 있도록 저장은 막지 않고 검증은 실행이 맡으므로 validation_status 는 늘 QUEUED 다.
     * frontend/src/api/orderAgent.js 의 saveSkillEdits(frontend/src/stores/workspace.js 가 부른다)와 skillops-agent/scripts/b_c_contract_smoke.py 가 부른다.
     * 남긴 이벤트는 SkillChangeDispatcher 가 service 의 POST /internal/skill-changes 로 보내 자동 진단을 시작하게 한다.
     */
    @PostMapping("/snapshots/edits")
    @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Map<String,Object> saveEdits(@RequestBody EditRequest request){
        return editResponse(snapshots.saveEdits(request.baselineSnapshotId(),request.baselineHash(),request.changes()));
    }
    // TODO(B/C-2 연동): 실제 진단 후보는 검증을 마친 workspace 참조로 받아 SnapshotService 에 연결한다.
    // 아래 fixture API 는 미리 준비한 시연 데이터만 고정하며, 진단하거나 수정안을 만드는 기능이 아니다.
    // MCP 도구를 구현할 때는 이 컨트롤러를 복제하지 말고 내부 서비스 계약을 부르도록 담당자와 맞춘다.
    /**
     * POST /api/snapshots: 미리 준비한 시연 fixture(baseline, broken-contract, repaired) 하나를 게시 번들에서 만들어 새 snapshot 으로 고정한다.
     * fixture 가 없거나 모르는 이름이면 UNKNOWN_FIXTURE(400)이고, demo 기능이 꺼져 있으면 SNAPSHOT_NOT_FOUND(404)다. 내용은 SnapshotService.demo 가 정한다.
     * frontend/src/api/orderAgent.js 의 pinFixture(frontend/src/stores/workspace.js 가 부른다), order-agent/frontend/src/api/evidence.js 의 publishSnapshot,
     * skillops-agent/scripts/b_c_contract_smoke.py, skillops-agent/scripts/c2_real_judge.py 가 부른다.
     */
    @PostMapping("/snapshots") public Map<String,Object> publish(@RequestBody PublishRequest request){
        demo.requireEnabled();
        if(request.fixture()==null)throw new AgentException("UNKNOWN_FIXTURE");
        return manifest(snapshots.demo(request.fixture()));
    }
    /** POST /api/snapshots/candidates 본문이다. files 는 snapshot 안 경로와 텍스트 본문의 짝이고, metadata 는 snapshot metadata 에 담을 값이다. */
    public record CandidateRequest(Map<String,String> files,Map<String,Object> metadata){}
    /**
     * POST /api/snapshots/candidates: 후보 파일 묶음의 텍스트를 UTF-8 바이트로 바꿔 그대로 새 snapshot 으로 발행하고 manifest 를 돌려준다.
     * 요청 metadata 는 보존하되 source 는 candidate 로 덮어쓴다. 경로와 크기 검사는 SnapshotService.publish 가 하며,
     * files 가 없으면 빈 묶음이라 INVALID_BUNDLE_SIZE 다. demo 기능이 꺼져 있으면 SNAPSHOT_NOT_FOUND(404)다.
     * service/src/main/java/com/capstone4/skillops/scenario/OrderAgentRunClient.java 의 publishCandidate 가 연습 공간 파일을 모의 실행하기 전에 부른다.
     */
    @PostMapping("/snapshots/candidates") public Map<String,Object> publishCandidate(@RequestBody CandidateRequest request){
        demo.requireEnabled();
        var files=new TreeMap<String,byte[]>();
        if(request.files()!=null)request.files().forEach((path,text)->files.put(path,text.getBytes(StandardCharsets.UTF_8)));
        var metadata=new LinkedHashMap<String,Object>();
        if(request.metadata()!=null)metadata.putAll(request.metadata());
        metadata.put("source","candidate");
        return manifest(snapshots.publish(files,metadata));
    }
    /**
     * GET /api/snapshots/{id}: snapshot 의 manifest(식별자, 전체 해시, 만든 시각, metadata, 파일별 경로와 해시와 크기)를 돌려준다.
     * SnapshotService.get 이 해시를 다시 검증하므로 변조된 snapshot 은 SNAPSHOT_TAMPERED(400), 없으면 SNAPSHOT_NOT_FOUND(404)다.
     * 호출처는 frontend/src/api/orderAgent.js 의 getSnapshot(frontend/src/api/skillSets.js 의 loadSkillSet), order-agent/frontend/src/api/evidence.js 의 getSnapshot,
     * service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java 의 getSnapshot,
     * skillops-agent/skillops_agent/tools.py 의 MCPTools.get_snapshot, order-agent/scripts/check-evidence.py 다.
     */
    @GetMapping("/snapshots/{id}") public Map<String,Object> snapshot(@PathVariable UUID id){return manifest(snapshots.get(id));}
    /**
     * GET /api/snapshots/{id}/files?path=: snapshot 안 파일 하나의 원본을 해시, 크기와 함께 돌려준다. 엄격한 UTF-8 로 읽히면 encoding 이 utf-8 이고,
     * 아니면 base64 로 담는다. 이 선택은 decode 가 한다. snapshot 에 없는 경로는 SNAPSHOT_FILE_NOT_FOUND(404), path 파라미터가 없으면 VALIDATION_ERROR(400)다.
     * 경로를 파일 시스템이 아니라 snapshot 의 파일 맵에서만 찾으므로 상위 폴더를 가리키는 경로로 다른 파일을 읽을 수 없다.
     * 호출처는 frontend/src/api/orderAgent.js 의 getSnapshotFile, order-agent/frontend/src/api/evidence.js 의 getSnapshotFile,
     * service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java 의 readFile, order-agent/scripts/check-evidence.py,
     * skillops-agent/scripts/b_c_contract_smoke.py, skillops-agent/scripts/c2_real_judge.py 다. 응답 키는 snapshot_id, path, hash, size, encoding, content 다.
     */
    @GetMapping("/snapshots/{id}/files") public Map<String,Object> file(@PathVariable UUID id,@RequestParam String path){
        var snapshot=snapshots.get(id);byte[] bytes=snapshot.files().get(path);if(bytes==null)throw new AgentException("SNAPSHOT_FILE_NOT_FOUND");
        var content=decode(bytes);
        return Map.of("snapshot_id",id,"path",path,"hash",Json.hash(bytes),"size",bytes.length,"encoding",content.encoding(),"content",content.content());
    }
    /** 파일 응답의 인코딩 이름(utf-8 또는 base64)과 그 인코딩으로 적은 본문이다. */
    private record FileContent(String encoding, String content) {}
    /**
     * 바이트를 잘못된 입력을 거절하는 UTF-8 로 읽고, 읽을 수 없으면 base64 로 바꾼다. 깨진 바이트를 대체 문자로 바꿔 보여 주면
     * 원본과 다른 내용을 증거로 내놓게 되므로 원본을 그대로 되살릴 수 있는 base64 로 보낸다.
     */
    private static FileContent decode(byte[] bytes){
        try{return new FileContent("utf-8",StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString());}
        catch(CharacterCodingException e){return new FileContent("base64",Base64.getEncoder().encodeToString(bytes));}
    }
    /**
     * 수정 저장 응답을 만든다. 저장된 snapshot manifest 뒤에 기준 snapshot, 변경 id, 변경 경로, 검증 대기 상태를 이 순서로 붙인다.
     * 앞의 세 값은 SnapshotService.saveEdits 가 metadata 에 남긴 것을 꺼내고, validation_status 는 늘 QUEUED 다.
     * EvidenceApiContractIntegrationTest 가 뒤쪽 네 키의 순서를 고정한다. change_id 는 GET /api/skill-changes/{id} 로 전달 상태를 볼 때 쓰는 값이다.
     */
    private Map<String,Object> editResponse(SnapshotRepository.Stored saved){
        var response=new LinkedHashMap<String,Object>(manifest(saved));
        response.put("baseline_snapshot_id",saved.metadata().get("baseline_snapshot_id"));
        response.put("change_id",saved.metadata().get("change_id"));
        response.put("changed_paths",saved.metadata().get("changed_paths"));
        response.put("validation_status","QUEUED");
        return response;
    }
    /**
     * snapshot 을 manifest 응답으로 바꾼다. 키는 snapshot_id, hash, created_at(ISO-8601), metadata, files 이고, files 는 경로 순으로
     * path, hash(SHA-256), size 를 담는다. 최상위와 파일 항목은 Map.of 라 키 순서를 보장하지 않는다.
     * GET /api/snapshots/{id}, POST /api/snapshots, POST /api/snapshots/candidates, POST /api/snapshots/edits 가 같은 모양을 쓰고,
     * service/src/main/java/com/capstone4/skillops/snapshot/SnapshotManifest.java 가 이 응답을 record 로 읽는다.
     */
    private Map<String,Object> manifest(SnapshotRepository.Stored s){
        return Map.of("snapshot_id",s.id(),"hash",s.hash(),"created_at",s.createdAt().toString(),"metadata",s.metadata(),
            "files",s.files().entrySet().stream().map(e->Map.of("path",e.getKey(),"hash",Json.hash(e.getValue()),"size",e.getValue().length)).toList());
    }
}
