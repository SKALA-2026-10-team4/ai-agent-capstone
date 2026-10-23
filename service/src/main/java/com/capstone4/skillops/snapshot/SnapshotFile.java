package com.capstone4.skillops.snapshot;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;

/**
 * 주문 Agent GET /api/snapshots/{id}/files?path= 응답 record 다. 칸 이름은 주문 Agent
 * (order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java 의 file) 그대로이고, JsonNaming
 * 으로 snake_case 칸과 맞춘다. service/src/main/java/com/capstone4/skillops/snapshot/OrderAgentClient.java 의 readFile 이
 * 읽고 readText 가 encoding 과 content 를 쓴다.
 *
 * @param encoding utf-8 이면 content 가 원문이다. 주문 Agent 가 UTF-8 로 읽지 못한 파일은 base64 이고, 이때 content 는 바이트를
 *                 인코딩한 값이다.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record SnapshotFile(String snapshotId, String path, String hash, long size, String encoding, String content) {
}
