package com.capstone4.skillops.scenario;

import java.nio.file.Path;

import tools.jackson.databind.json.JsonMapper;

/** 테스트 공용 도우미. 저장소의 실제 파일(scenarios/, skills/)을 읽는다. 테스트는 service/ 에서 돈다. */
final class TestFixtures {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private TestFixtures() {
    }

    static Scenario g01() {
        return JSON.readValue(Path.of("../scenarios/golden/g-01.json").toFile(), Scenario.class);
    }
}
