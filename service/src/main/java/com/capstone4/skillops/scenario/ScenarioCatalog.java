package com.capstone4.skillops.scenario;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.json.JsonMapper;

/**
 * 기준 주문 목록. 서비스가 켜질 때 scenarios/golden/*.json 을 모두 읽는다(D 가 쓰는 파일).
 * 재현 주문(f-NN)과 scenarios 테이블은 다음 계획(원인 확인)에서 더한다.
 */
@Component
public class ScenarioCatalog {

    private final Map<String, Scenario> scenarios = new TreeMap<>();

    /** 경로는 글자로 받는다. Path 로 바로 받으면 Spring 이 상대 경로를 웹 리소스로 해석해 Tomcat 에서 거부된다. */
    public ScenarioCatalog(@Value("${skillops.scenarios-dir}") String scenariosDir, JsonMapper mapper) {
        Path golden = Path.of(scenariosDir).resolve("golden");
        if (!Files.isDirectory(golden)) {
            throw new IllegalStateException("시험 주문 폴더를 찾을 수 없습니다: " + golden.toAbsolutePath()
                    + ". skillops.scenarios-dir 설정을 확인하세요");
        }
        try (Stream<Path> files = Files.list(golden)) {
            files.filter(f -> f.getFileName().toString().endsWith(".json"))
                    .map(f -> mapper.readValue(f.toFile(), Scenario.class))
                    .forEach(s -> scenarios.put(s.id(), s));
        } catch (IOException e) {
            throw new UncheckedIOException("시험 주문 폴더를 읽을 수 없습니다: " + golden, e);
        }
    }

    public Scenario get(String id) {
        Scenario scenario = scenarios.get(id);
        if (scenario == null) {
            throw new IllegalArgumentException("시험 주문 " + id + " 이 없습니다. 있는 번호: " + ids());
        }
        return scenario;
    }

    /** 있는 시험 주문 번호. 정렬돼 있다. */
    public Set<String> ids() {
        return Collections.unmodifiableSet(scenarios.keySet());
    }
}
