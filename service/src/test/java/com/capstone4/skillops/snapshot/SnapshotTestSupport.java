package com.capstone4.skillops.snapshot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.assertj.core.api.ThrowableAssert.ThrowingCallable;

/**
 * snapshot 패키지 특성 테스트들이 함께 쓰는 도우미다. 운영 skill 원문, 장애 F1 글, 분석기 입력, manifest, 오류 단언을 만든다.
 * 운영 원문은 저장소 루트의 skills 폴더에서 읽는다. 테스트는 service/ 에서 돌기 때문에 상대 경로 ../skills 를 쓴다.
 * 같은 패키지(service/src/test/java/com/capstone4/skillops/snapshot/)의 DependencyAnalyzerTest, DependencyGraphServiceTest,
 * OrderAgentClientTest, SkillDiffServiceTest, SkillDifferTest 가 쓴다.
 */
final class SnapshotTestSupport {

    // 테스트에서 쓰는 기준 snapshot 과 비교 대상 snapshot 의 UUID 다.
    static final String BEFORE = "11111111-1111-1111-1111-111111111111";
    static final String AFTER = "22222222-2222-2222-2222-222222222222";
    /** 운영 flow 와 단계 skill 이다. order-flow 를 맨 앞에 두고, 나머지는 order-flow 의 steps 순서다. */
    static final List<String> LIVE_SKILLS = List.of(
            "order-flow", "order-intake", "inventory-check", "payment", "shipping-reserve", "notify");

    /** 정적 메서드만 있는 도구 클래스라서 객체를 만들지 못하게 막는다. */
    private SnapshotTestSupport() {
    }

    /** 저장소 루트 skills 폴더에 있는 그 skill 의 SKILL.md 원문을 읽는다. */
    static String liveText(String skillId) {
        try {
            return Files.readString(Path.of("../skills/" + skillId + "/SKILL.md"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * 장애 F1 의 repaired 글을 만든다. 운영 inventory-check 의 inventory.available 을 inventory.reservable_quantity 로 바꾼다.
     * service/src/test/java/com/capstone4/skillops/workspace/F1Texts.java 의 repaired 와 같은 치환이다.
     */
    static String f1Repaired() {
        return liveText("inventory-check").replace("inventory.available", "inventory.reservable_quantity");
    }

    /** 장애 F1 의 broken 글을 만든다. repaired 글의 outputs 에서 inventory.reserved 를 지워 payment 가 받는 값을 내놓는 skill 이 없어진다. */
    static String f1Broken() {
        return f1Repaired().replace(", inventory.reserved", "");
    }

    /** 운영 skill 여섯 개를 분석기 입력으로 만든다. fileHash 는 hash- 뒤에 skill ID 를 붙인 값이다. */
    static Map<String, SkillSource> liveSources() {
        Map<String, SkillSource> sources = new LinkedHashMap<>();
        for (String id : LIVE_SKILLS) {
            sources.put(id, source(id, liveText(id)));
        }
        return sources;
    }

    /** id 의 표준 SKILL.md 경로와 hash- 접두 해시를 붙인 분석기 입력 하나를 만든다. */
    static SkillSource source(String id, String text) {
        return new SkillSource("skills/" + id + "/SKILL.md", "hash-" + id, text);
    }

    /** 단계 skill 하나의 SKILL.md 를 만든다. 목록 칸은 쉼표로 이은 글을 그대로 넣는다. */
    static String stepSkill(String name, String inputs, String outputs, String dependsOn) {
        return "---\nname: " + name + "\ninputs: [" + inputs + "]\noutputs: [" + outputs + "]\ndepends_on: [" + dependsOn
                + "]\n---\n# " + name + "\n";
    }

    /** steps 만 적은 entry flow 의 SKILL.md 를 만든다. */
    static String flowSkill(String name, String steps) {
        return "---\nname: " + name + "\nsteps: [" + steps + "]\n---\n# " + name + "\n";
    }

    /** 고정한 created_at 과 빈 metadata 로 manifest 를 만든다. */
    static SnapshotManifest manifest(String snapshotId, String hash, List<SnapshotManifest.FileEntry> files) {
        return new SnapshotManifest(snapshotId, hash, "2026-10-20T00:00:00Z", Map.of(), files);
    }

    /** 크기가 1 인 manifest 파일 항목을 만든다. */
    static SnapshotManifest.FileEntry file(String path, String hash) {
        return new SnapshotManifest.FileEntry(path, hash, 1);
    }

    /** call 이 OrderAgentException 을 던지는지 확인하고 그 예외를 돌려준다. */
    static OrderAgentException failure(ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).isInstanceOf(OrderAgentException.class);
        return (OrderAgentException) thrown;
    }

    /** 예외의 다섯 칸(code, message, retryable, upstream_status, snapshot_id)이 모두 기대값과 같은지 확인한다. */
    static void assertFailure(OrderAgentException e, String code, String message, boolean retryable,
            Integer upstreamStatus, String snapshotId) {
        assertThat(e.code()).isEqualTo(code);
        assertThat(e.getMessage()).isEqualTo(message);
        assertThat(e.retryable()).isEqualTo(retryable);
        assertThat(e.upstreamStatus()).isEqualTo(upstreamStatus);
        assertThat(e.snapshotId()).isEqualTo(snapshotId);
    }
}
