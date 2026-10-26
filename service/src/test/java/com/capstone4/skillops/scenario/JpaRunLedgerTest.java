package com.capstone4.skillops.scenario;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * service/src/main/java/com/capstone4/skillops/scenario/JpaRunLedger.java 가 intent_matches 를 쉼표로 이은 글로 저장하고 다시 번호
 * 목록으로 읽는지, 의도 없이 돌린 실행과 옛 줄은 null 로 두는지, find 가 run_id 로 줄 하나를 찾는지 본다
 * (docs/plans/2026-10-26-behavior-edit-root-fix.md 2.10 절 V3-4, V3-5). 표 저장소는 mock 이다.
 */
class JpaRunLedgerTest {

    ScenarioRunRecordRepository records = mock(ScenarioRunRecordRepository.class);
    JpaRunLedger ledger = new JpaRunLedger(records);

    /** record 가 저장소에 넘긴 줄을 꺼낸다. */
    ScenarioRunRecord saved() {
        ArgumentCaptor<ScenarioRunRecord> row = ArgumentCaptor.forClass(ScenarioRunRecord.class);
        then(records).should().save(row.capture());
        return row.getValue();
    }

    @Test
    void intentMatchesAreStoredAsCommaSeparatedText() {
        ledger.record("w_1", "g-01", "r_1", true, true, "i:abc", List.of(0, 2));

        ScenarioRunRecord row = saved();
        assertThat(row.getIntentMatches()).isEqualTo("0,2");
        given(records.findById("r_1")).willReturn(Optional.of(row));
        given(records.findByWorkspaceId("w_1")).willReturn(List.of(row));

        assertThat(ledger.find("r_1")).get().satisfies(run -> {
            assertThat(run.intentMatches()).containsExactly(0, 2);
            assertThat(run.scenarioId()).isEqualTo("g-01");
            assertThat(run.passed()).isTrue();
        });
        assertThat(ledger.runsOf("w_1")).singleElement().satisfies(run -> assertThat(run.intentMatches()).containsExactly(0, 2));
    }

    @Test
    void runsWithoutIntentKeepNullAndEmptyMatchesStayEmpty() {
        ledger.record("w_1", "g-01", "r_1", true);
        assertThat(saved().getIntentMatches()).isNull();

        assertThat(JpaRunLedger.matchesText(List.of())).isEmpty();
        assertThat(JpaRunLedger.matchesOf("")).isEmpty();
        assertThat(JpaRunLedger.matchesOf(null)).isNull();
        assertThat(JpaRunLedger.matchesOf(" 1 ,x, 3")).containsExactly(1, 3);
    }

    @Test
    void findReturnsEmptyForUnknownOrNullRunIds() {
        given(records.findById(any())).willReturn(Optional.empty());

        assertThat(ledger.find("r_none")).isEmpty();
        assertThat(ledger.find(null)).isEmpty();
    }
}
