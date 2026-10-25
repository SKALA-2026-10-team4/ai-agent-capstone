package com.capstone4.skillops.proposal;

import jakarta.persistence.*;
import org.springframework.data.jpa.repository.JpaRepository;

/** 검증 시도 하나가 여러 제안서로 중복 저장되는 것을 DB에서 막는다. */
@Entity
@Table(name="verified_proposal_receipts")
class VerifiedProposalReceipt {
    @Id String attemptId;
    @Column(nullable=false) String requestHash;
    @Column(nullable=false) String proposalId;
    @Version Long version;
    /** JPA 복원용 생성자다. */
    protected VerifiedProposalReceipt() {}
}
/** 서버 검증과 제안서의 불변 연결을 조회한다. */
interface VerifiedProposalReceiptRepository extends JpaRepository<VerifiedProposalReceipt,String> {}
