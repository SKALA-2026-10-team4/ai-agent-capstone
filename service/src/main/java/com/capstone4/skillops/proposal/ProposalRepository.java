package com.capstone4.skillops.proposal;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * proposals 표를 읽고 쓰는 Spring Data JPA 저장소다. Spring 이 메서드 이름으로 질의를 만들어 구현체를 빈으로 등록한다.
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalService.java 만 이 저장소를 쓴다.
 * 번호로 찾는 findById 와 저장하는 save 는 JpaRepository 가 주는 메서드다.
 */
public interface ProposalRepository extends JpaRepository<Proposal, String> {

    /** thread_id 로 이미 저장한 제안서를 찾는다. ProposalService 의 save 가 같은 진단의 재저장을 알아볼 때 쓴다. */
    Optional<Proposal> findByThreadId(String threadId);

    /**
     * 만든 시각이 늦은 것부터 제안서 100개를 읽는다. 목록 크기 100 은 메서드 이름의 Top100 이 정한다.
     * ProposalService 의 recent 가 제안서 목록 응답을 만들 때 쓴다.
     */
    List<Proposal> findTop100ByOrderByCreatedAtDesc();
}
