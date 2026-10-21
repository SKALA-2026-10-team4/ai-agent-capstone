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

/** 테스트 공용 도우미. 저장소의 실제 파일(scenarios/, skills/)을 읽는다. 테스트는 service/ 에서 돈다. */
final class TestFixtures {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private TestFixtures() {
    }

    static Scenario g01() {
        return JSON.readValue(Path.of("../scenarios/golden/g-01.json").toFile(), Scenario.class);
    }

    static Map<String, Object> g01Order() {
        return g01().order();
    }

    /** 운영 폴더의 skill 6개를 버전 1 로 읽는다. */
    static Map<String, WorkspaceSkill> liveSkills() {
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

    /** 장애 F1: 재고 확인 skill 이 '예약 여부'를 더 이상 내놓지 않는다. */
    static Map<String, WorkspaceSkill> f1Skills() {
        Map<String, WorkspaceSkill> skills = new HashMap<>(liveSkills());
        String text = skills.get("inventory-check").fullText().replace(
                "outputs:    [inventory.available, inventory.reserved]", "outputs:    [inventory.예약가능수량]");
        skills.put("inventory-check", new WorkspaceSkill(2, text));
        return skills;
    }
}
