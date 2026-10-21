package com.capstone4.skillops.scenario;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ScenarioRunRecordRepository extends JpaRepository<ScenarioRunRecord, String> {

    List<ScenarioRunRecord> findByWorkspaceId(String workspaceId);
}
