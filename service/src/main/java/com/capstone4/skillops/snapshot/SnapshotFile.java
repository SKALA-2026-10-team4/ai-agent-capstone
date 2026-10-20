package com.capstone4.skillops.snapshot;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 주문 Agent GET /api/snapshots/{id}/files?path= 응답. 칸 이름은 주문 Agent 그대로다.
 *
 * @param encoding utf-8 이면 content 가 원문, base64 면 content 가 바이트를 인코딩한 값
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SnapshotFile(String snapshotId, String path, String hash, long size, String encoding, String content) {
}
