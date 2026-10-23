package com.capstone4.skillops.scenario;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * 기준 주문 목록이다. 서비스가 켜질 때 skillops.scenarios-dir 아래 golden 폴더의 .json 파일을 모두 읽어 번호로 찾게 해 둔다.
 * 파일은 D 가 쓴다. 설정 기본값은 ../scenarios 이며 service/src/main/resources/application.yml 에 있고 환경 변수
 * SKILLOPS_SCENARIOS_DIR 로 바꾼다. 주문 Agent 와 연동해 판정할 때는 ../scenarios/order-agent 로 켜며,
 * skillops-agent/scripts/c2_real_judge.py 의 설명이 그 방법을 적고 있다.
 * 파일이 하나라도 잘못되면 서비스가 켜지지 않으므로, 오류 문구에 어느 파일이 문제인지 꼭 적는다.
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 의 planScenarioId() 가 get 을 부른다.
 * 재현 주문(f-&lt;execution_id&gt;)은 이 목록에 없고, ScenarioRunner 가 주문 Agent 에서 원래 주문을 꺼내 만든다.
 */
@Component
public class ScenarioCatalog {

    /** 시험 주문 번호별 시험 주문이다. TreeMap 이라 번호가 정렬돼 있다. */
    private final Map<String, Scenario> scenarios = new TreeMap<>();

    /**
     * Spring 이 skillops.scenarios-dir 값과 JsonMapper 빈을 넣어 만들고, 이때 파일을 모두 읽는다.
     * 경로는 글자로 받는다. Path 로 바로 받으면 Spring 이 상대 경로를 웹 리소스로 해석해 Tomcat 에서 거부된다.
     */
    public ScenarioCatalog(@Value("${skillops.scenarios-dir}") String scenariosDir, JsonMapper mapper) {
        Path golden = goldenFolder(scenariosDir);
        // 파일이 잘못되면 서비스가 켜지지 않으므로, 어느 파일이 문제인지 메시지에 꼭 적는다.
        Map<String, Path> sources = new HashMap<>();
        for (Path file : jsonFilesIn(golden)) {
            register(readScenario(mapper, file), file, sources);
        }
    }

    /** scenarios-dir 아래 golden 폴더를 돌려준다. 없으면 skillops.scenarios-dir 설정을 확인하라는 IllegalStateException 을 던진다. */
    private static Path goldenFolder(String scenariosDir) {
        Path golden = Path.of(scenariosDir).resolve("golden");
        if (!Files.isDirectory(golden)) {
            throw new IllegalStateException("시험 주문 폴더를 찾을 수 없습니다: " + golden.toAbsolutePath()
                    + ". skillops.scenarios-dir 설정을 확인하세요");
        }
        return golden;
    }

    /** 폴더 안의 .json 파일을 이름 순으로 돌려준다. README.md 같은 다른 파일은 무시한다. */
    private static List<Path> jsonFilesIn(Path golden) {
        try (Stream<Path> listed = Files.list(golden)) {
            return listed.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException("시험 주문 폴더를 읽을 수 없습니다: " + golden, e);
        }
    }

    /** 시험 주문 파일 하나를 읽는다. 읽을 수 없거나 id 가 없으면 파일 이름을 밝힌 IllegalStateException 을 던진다. */
    private static Scenario readScenario(JsonMapper mapper, Path file) {
        Scenario scenario;
        try {
            scenario = mapper.readValue(file.toFile(), Scenario.class);
        } catch (RuntimeException e) {
            throw new IllegalStateException("시험 주문 파일을 읽을 수 없습니다: " + file, e);
        }
        if (scenario.id() == null || scenario.id().isBlank()) {
            throw new IllegalStateException("시험 주문 파일에 id 가 없습니다: " + file);
        }
        return scenario;
    }

    /** 목록에 넣는다. 같은 id 가 이미 다른 파일에 있었으면 두 파일 이름을 밝힌 IllegalStateException 을 던진다. */
    private void register(Scenario scenario, Path file, Map<String, Path> sources) {
        Path previous = sources.putIfAbsent(scenario.id(), file);
        if (previous != null) {
            throw new IllegalStateException("시험 주문 id " + scenario.id() + " 이 두 파일에 있습니다: "
                    + previous.getFileName() + ", " + file.getFileName());
        }
        scenarios.put(scenario.id(), scenario);
    }

    /**
     * 번호로 기준 주문을 찾는다. 없으면 있는 번호 목록을 붙인 IllegalArgumentException 을 던지고,
     * 이 문구는 run_scenarios 의 INVALID_INPUT 오류로 Agent 에게 간다.
     */
    public Scenario get(String id) {
        Scenario scenario = scenarios.get(id);
        if (scenario == null) {
            throw new IllegalArgumentException("시험 주문 " + id + " 이 없습니다. 있는 번호: " + ids());
        }
        return scenario;
    }

    /**
     * 있는 시험 주문 번호를 정렬된 순서로 돌려준다. 고칠 수 없는 집합이다.
     * get 이 없는 번호 오류 문구에 쓰고, 그 밖에는 테스트에서만 부른다.
     */
    public Set<String> ids() {
        return Collections.unmodifiableSet(scenarios.keySet());
    }
}
