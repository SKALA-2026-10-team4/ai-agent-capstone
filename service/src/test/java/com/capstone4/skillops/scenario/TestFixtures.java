package com.capstone4.skillops.scenario;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import com.capstone4.skillops.workspace.WorkspaceSkill;

import tools.jackson.databind.json.JsonMapper;

/**
 * 테스트 공용 도우미다. 저장소의 실제 파일인 scenarios/golden/g-01.json 과 skills/ 아래 SKILL.md 를 읽는다.
 * Gradle 테스트는 service/ 폴더에서 돌므로 ../ 로 저장소 루트를 가리킨다.
 * scenario 패키지 테스트 말고도 service/src/test/java/com/capstone4/skillops/proposal/ 의 테스트가 InMemoryRunLedger 를,
 * service/src/test/java/com/capstone4/skillops/workspace/ 의 F1Texts 와 ApplyPatchTest 가 liveSkills 를 쓰므로 public 으로 둔다.
 */
public final class TestFixtures {

    /** 시험 주문 파일과 trace 를 읽는 JSON 파서다. */
    static final JsonMapper JSON = JsonMapper.builder().build();

    /** 정적 메서드만 있는 도우미라 객체를 만들지 않는다. */
    private TestFixtures() {
    }

    /** 저장소의 기준 주문 g-01 을 읽는다. */
    static Scenario g01() {
        return JSON.readValue(Path.of("../scenarios/golden/g-01.json").toFile(), Scenario.class);
    }

    /** g-01 의 order 칸이다. */
    static Map<String, Object> g01Order() {
        return g01().order();
    }

    /** 운영 폴더(skills/)에서 SKILL.md 가 있는 skill 여섯 개를 모두 버전 1 로 읽는다. */
    public static Map<String, WorkspaceSkill> liveSkills() {
        Map<String, WorkspaceSkill> skills = new TreeMap<>();
        try (Stream<Path> dirs = Files.list(Path.of("../skills"))) {
            for (Path dir : dirs.filter(d -> Files.isRegularFile(d.resolve("SKILL.md"))).toList()) {
                skills.put(dir.getFileName().toString(), new WorkspaceSkill(1, Files.readString(dir.resolve("SKILL.md"))));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return skills;
    }

    /**
     * 장애 F1 을 흉내 낸 skill 묶음이다. 재고 확인 skill 의 outputs 를 [inventory.available, inventory.reserved] 에서
     * [inventory.예약가능수량] 으로 바꿔 '예약 여부'(inventory.reserved)를 더 이상 내놓지 않게 하고, 그 skill 의 버전을 2 로 둔다.
     * 장애 설명은 scenarios/failures/F1.md 에 있다.
     */
    static Map<String, WorkspaceSkill> f1Skills() {
        Map<String, WorkspaceSkill> skills = new HashMap<>(liveSkills());
        String text = skills.get("inventory-check").fullText().replace(
                "outputs:    [inventory.available, inventory.reserved]", "outputs:    [inventory.예약가능수량]");
        skills.put("inventory-check", new WorkspaceSkill(2, text));
        return skills;
    }

    /**
     * 테스트용 {@link RunLedger} 다. 메모리에만 둔다. record 는 run_id 와 연습 공간 번호만 남기고 scenario_id 와 passed 는 버리며,
     * runIdsOf 와 known 은 그 기록에서 찾는다.
     */
    public static final class InMemoryRunLedger implements RunLedger {

        private final java.util.Map<String, String> workspaceOf = new java.util.LinkedHashMap<>();

        @Override
        public void record(String workspaceId, String scenarioId, String runId, boolean passed) {
            workspaceOf.put(runId, workspaceId);
        }

        @Override
        public java.util.Set<String> runIdsOf(String workspaceId) {
            return workspaceOf.entrySet().stream().filter(e -> e.getValue().equals(workspaceId))
                    .map(java.util.Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        @Override
        public boolean known(String runId) {
            return workspaceOf.containsKey(runId);
        }

        /** 장부를 비운다. proposal 테스트가 근거 실행을 찾을 수 없는 경우를 만들 때 쓴다. */
        public void clear() {
            workspaceOf.clear();
        }
    }
}
