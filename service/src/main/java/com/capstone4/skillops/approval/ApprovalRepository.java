package com.capstone4.skillops.approval;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * approvals 표를 읽고 쓰는 Spring Data JPA 저장소다. Spring 이 메서드 이름으로 질의를 만들어 구현체를 빈으로 등록한다.
 * service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java 만 이 저장소를 쓴다.
 * 결정 기록을 남기는 save 는 JpaRepository 가 주는 메서드다.
 */
public interface ApprovalRepository extends JpaRepository<Approval, UUID> {

    /**
     * 결정 시각이 늦은 것부터 기록 50개를 읽는다. 목록 크기 50 은 메서드 이름의 Top50 이 정한다.
     * ApprovalService 의 recent 가 GET /api/approvals 응답을 만들 때 쓴다.
     */
    List<Approval> findTop50ByOrderByDecidedAtDesc();

    /**
     * 한 제안서의 마지막 결정 기록을 읽는다. 같은 제안서는 한 번만 결정할 수 있으므로 보통 한 줄뿐이다.
     * ApprovalService 의 latestFor 가 제안서 상세 응답에 붙일 결정을 찾을 때 쓴다.
     */
    Optional<Approval> findTopByProposalIdOrderByDecidedAtDesc(String proposalId);
}
