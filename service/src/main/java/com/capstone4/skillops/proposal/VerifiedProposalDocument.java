package com.capstone4.skillops.proposal;

import java.util.List;
import com.capstone4.skillops.evaluation.EvaluationService;
import com.capstone4.skillops.proposal.ProposalDocument.Fact;
import com.capstone4.skillops.proposal.ProposalDocument.Section;

/** 실행하지 않은 원인 실험·LLM 진단을 했다고 서술하지 않는 평가용 본문이다. */
final class VerifiedProposalDocument {
    /** 정적 본문 생성만 제공한다. */
    private VerifiedProposalDocument() {}
    /** 실제 검증 범위와 snapshot·평가 근거를 사람이 확인할 수 있게 만든다. */
    static ProposalDocument write(VerifiedProposalService.Input input,EvaluationService.Verification verification,List<String> checks) {
        var plan=verification.plan();
        Section candidate=new Section("candidate","검증된 후보 snapshot",List.of(input.summary()),null,List.of(),
                List.of(new Fact("후보 snapshot",plan.candidate().snapshotId()),new Fact("후보 hash",plan.candidate().hash())),List.of(),1);
        return new ProposalDocument("Skill snapshot 기능 검증 제안서",
                "격리된 fixed 주문 실행에서 필수 기능 검사를 통과했습니다. 실제 LLM 호출 품질 개선과 원인 분석은 수행하지 않았습니다.",
                List.of(new Section("intent","변경 의도",List.of("승인 전 담당자가 의도를 확인해야 합니다."),input.intentText(),checks,List.of(),List.of(),null),
                        new Section("candidates","후보 검증",List.of("운영 반영은 실행하지 않았습니다."),null,List.of(),
                                List.of(new Fact("평가",input.evaluationId()),new Fact("시도",input.attemptId()),
                                        new Fact("기준 snapshot",plan.baseline().snapshotId()),new Fact("정책",plan.policyVersion())),List.of(candidate),null),
                        new Section("limits","검증 범위",List.of("규칙 기반 fixed harness의 기능 검증 결과입니다.",
                                "원인 분석·자동 수정·실모델 선택 품질·안전성 평가는 수행하지 않았습니다."),null,List.of(),List.of(),List.of(),null)),List.of());
    }
}
