package com.capstone4.skillops.proposal;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/** AI 생산자 변경 없이 운영자가 검증 완료 snapshot을 제안할 수 있는 백엔드 경계다. */
@RestController
public class VerifiedProposalController {
    private final VerifiedProposalService service;
    /** 서버 검증 저장 서비스를 받는다. */
    public VerifiedProposalController(VerifiedProposalService service) { this.service=service; }
    /** 클라이언트가 합격 결과를 직접 전달할 수 없는 저장 경계다. */
    @PostMapping("/api/verified-proposals") @PreAuthorize("hasRole('OPERATOR')")
    public VerifiedProposalService.Saved save(@RequestBody VerifiedProposalService.Input input) { return service.save(input); }
}
