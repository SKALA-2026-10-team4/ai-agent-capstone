package com.capstone4.skillops.evaluation;

import java.security.Principal;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import static com.capstone4.skillops.evaluation.EvaluationTypes.*;

/** 로그인한 운영자의 평가 요청을 공통 서비스로 전달한다. */
@RestController
@RequestMapping("/api")
public class EvaluationController {
    private final EvaluationService service;
    /** 공통 유스케이스를 주입한다. */
    public EvaluationController(EvaluationService service) { this.service=service; }
    /** 라벨은 미검토 상태로 등록한다. */
    @PostMapping("/evaluation-sets") @PreAuthorize("hasRole('OPERATOR')")
    public SetView register(@RequestBody SetInput input) { return service.register(input); }
    /** 검토할 라벨 원문을 조회한다. */
    @GetMapping("/evaluation-sets/{id}") @PreAuthorize("isAuthenticated()")
    public SetDetail definition(@PathVariable String id) { return service.definition(id); }
    /** 요청자가 임의의 검토자 이름을 보내지 못하게 한다. */
    @PostMapping("/evaluation-sets/{id}/review") @PreAuthorize("hasRole('OPERATOR')")
    public SetView review(@PathVariable String id,Principal principal) { return service.review(id,principal.getName()); }
    /** 실행은 DB 작업자에게 맡기고 202를 반환한다. */
    @PostMapping("/evaluations") @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Accepted> submit(@RequestBody Submit input,@RequestHeader("Idempotency-Key") String key,Principal principal) {
        return ResponseEntity.accepted().body(service.submit(input,key,principal.getName()));
    }
    /** 원문을 제외한 요약 목록을 반환한다. */
    @GetMapping("/evaluations") @PreAuthorize("isAuthenticated()")
    public Page<View> list(@RequestParam(defaultValue="0") int page,@RequestParam(defaultValue="20") int limit) { return service.list(page,limit); }
    /** 최신 attempt와 진행 수를 반환한다. */
    @GetMapping("/evaluations/{id}") @PreAuthorize("isAuthenticated()")
    public View get(@PathVariable String id) { return service.get(id); }
    /** 재시작 뒤에도 이전 시도의 결과에 접근할 수 있게 한다. */
    @GetMapping("/evaluations/{id}/attempts") @PreAuthorize("isAuthenticated()")
    public java.util.List<AttemptView> history(@PathVariable String id) { return service.history(id); }
    /** 저장한 사례별 증거를 페이지로 반환한다. */
    @GetMapping("/evaluations/{id}/attempts/{attempt}/cases") @PreAuthorize("isAuthenticated()")
    public CasePage<Trial> cases(@PathVariable String id,@PathVariable String attempt,
            @RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="20") int limit) { return service.cases(id,attempt,offset,limit); }
    /** 완료·부분 결과를 같은 계약으로 반환한다. */
    @GetMapping("/evaluations/{id}/attempts/{attempt}/report") @PreAuthorize("isAuthenticated()")
    public Report report(@PathVariable String id,@PathVariable String attempt) { return service.report(id,attempt); }
    /** 실제 중단 여부와 구분하여 취소를 접수한다. */
    @PostMapping("/evaluations/{id}/attempts/{attempt}/cancel") @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Accepted> cancel(@PathVariable String id,@PathVariable String attempt) { return ResponseEntity.accepted().body(service.cancel(id,attempt)); }
    /** 동일 계획의 새 시도를 멱등 접수한다. */
    @PostMapping("/evaluations/{id}/attempts/{attempt}/retries") @PreAuthorize("hasRole('OPERATOR')")
    public ResponseEntity<Accepted> retry(@PathVariable String id,@PathVariable String attempt,
            @RequestHeader("Idempotency-Key") String key,Principal principal) { return ResponseEntity.accepted().body(service.retry(id,attempt,key,principal.getName())); }
}
