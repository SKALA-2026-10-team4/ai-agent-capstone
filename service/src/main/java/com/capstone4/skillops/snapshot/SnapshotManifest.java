package com.capstone4.skillops.snapshot;

import java.util.List;
import java.util.Map;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/** 주문 Agent GET /api/snapshots/{id} 응답. 칸 이름은 주문 Agent 그대로다. */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SnapshotManifest(String snapshotId, String hash, String createdAt, Map<String, Object> metadata,
        List<FileEntry> files) {

    public record FileEntry(String path, String hash, long size) {
    }
}
