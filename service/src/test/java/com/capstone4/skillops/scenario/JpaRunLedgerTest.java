package com.capstone4.skillops.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class JpaRunLedgerTest {
    /** DB의 성공·실패와 시나리오를 보존하며 실행마다 추가 조회하지 않는다. */
    @Test
    void readsStoredGradesInOneWorkspaceQuery() {
        ScenarioRunRecordRepository records = mock(ScenarioRunRecordRepository.class);
        when(records.findByWorkspaceId("w1")).thenReturn(List.of(
                new ScenarioRunRecord("pass", "w1", "g-01", true, Instant.EPOCH),
                new ScenarioRunRecord("fail", "w1", "f-repro", false, Instant.EPOCH)));

        assertThat(new JpaRunLedger(records).evidenceOf("w1")).containsExactly(
                new RunLedger.Evidence("pass", "g-01", true),
                new RunLedger.Evidence("fail", "f-repro", false));
        verify(records).findByWorkspaceId("w1");
        verifyNoMoreInteractions(records);
    }
}
