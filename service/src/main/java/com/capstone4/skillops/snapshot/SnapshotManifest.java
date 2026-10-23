package com.capstone4.skillops.snapshot;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 주문 Agent GET /api/snapshots/{id} 응답 record 다. 칸 이름은 주문 Agent
 * (order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의 manifest) 그대로다.
 * hash 는 snapshot 전체 해시이고 files 는 파일마다 경로, 해시, 크기를 담는다.
 * service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java 의 getSnapshot 이 읽는다.
 * 같은 패키지의 SkillDiffService, DependencyGraphService, DependencyGraphResult 와
 * service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 의 createFromSnapshot 이 쓴다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SnapshotManifest(String snapshotId, String hash, String createdAt, Map<String, Object> metadata,
        List<FileEntry> files) {

    /**
     * 경로를 키로, 파일 해시를 값으로 담은 맵을 만든다. 같은 경로가 두 번 있으면 IllegalStateException 을, 해시가 null 이면
     * NullPointerException 을 던지며 조용히 합치지 않는다. DependencyGraphService.dependencyGraph 가 쓴다.
     */
    Map<String, String> hashByPath() {
        return files.stream().collect(Collectors.toMap(FileEntry::path, FileEntry::hash));
    }

    /**
     * dir 로 시작하는 경로만 골라 경로와 파일 해시의 맵을 만든다. 실패 규칙은 hashByPath 와 같지만 dir 밖의 겹침은 보지 않는다.
     * SkillDiffService.diffSkill 이 skill 폴더(SkillPaths.skillDir) 안 파일만 고를 때 쓴다.
     */
    Map<String, String> hashByPathUnder(String dir) {
        return files.stream()
                .filter(f -> f.path().startsWith(dir))
                .collect(Collectors.toMap(FileEntry::path, FileEntry::hash));
    }

    /** manifest 의 파일 하나다. hash 는 파일 바이트의 소문자 16진 SHA-256 이고 size 는 바이트 수다. */
    public record FileEntry(String path, String hash, long size) {
    }
}
