package com.capstone4.skillops.scenario;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * scenario_runs 표의 JPA 저장소다. 기본 키는 run_id 다.
 * Spring Data JPA 가 구현을 만들어 service/src/main/java/com/capstone4/skillops/scenario/JpaRunLedger.java 의 생성자에 주입한다.
 */
public interface ScenarioRunRecordRepository extends JpaRepository<ScenarioRunRecord, String> {

    /** 한 연습 공간에서 돌린 실행을 모두 찾는다. Spring Data 가 메서드 이름으로 쿼리를 만들고, JpaRunLedger 의 runIdsOf 가 쓴다. */
    List<ScenarioRunRecord> findByWorkspaceId(String workspaceId);
}
