package com.capstone4.skillops.snapshot;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * UTF-8 글의 SHA-256 을 소문자 16진 문자열로 만든다. service/src/main/java/com/capstone4/skillops/snapshot/SkillDiffer.java 의
 * hunk_id 와 service/src/main/java/com/capstone4/skillops/snapshot/SkillSource.java 의 fromText 가 만드는 파일 해시가 같은
 * 계산을 쓰도록 한곳에 두었다. 주문 Agent 의 파일 해시(order-agent/backend/src/main/java/com/capstone4/orderagent/harness/Json.java
 * 의 hash)도 파일 바이트의 소문자 16진 SHA-256 이라서, 같은 UTF-8 글이면 manifest 해시와 같은 값이 나온다.
 */
final class Sha256 {

    /** 정적 메서드만 있는 도구 클래스라서 객체를 만들지 못하게 막는다. */
    private Sha256() {
    }

    /** text 를 UTF-8 바이트로 바꿔 SHA-256 을 구하고 소문자 16진 문자열로 돌려준다. JDK 에 SHA-256 이 없으면 IllegalStateException 이다. */
    static String hex(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
