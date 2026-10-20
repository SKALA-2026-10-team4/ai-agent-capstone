package com.capstone4.skillops.snapshot;

/** snapshot 에서 받아 온 SKILL.md 하나. path·fileHash 는 주문 Agent manifest 값 그대로다. */
public record SkillSource(String path, String fileHash, String text) {
}
