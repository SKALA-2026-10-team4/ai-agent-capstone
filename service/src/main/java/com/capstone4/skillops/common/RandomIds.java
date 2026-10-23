package com.capstone4.skillops.common;

import java.security.SecureRandom;

/**
 * 접두어 뒤에 정한 문자 집합에서 무작위로 고른 글자를 붙여 번호를 만드는 공용 도구다.
 * 서비스가 스스로 발급하는 번호 세 가지가 같은 방식을 쓰도록 한곳에 모았다.
 * service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 의 newId 가 연습 공간 번호(w_ 와 8자)를,
 * service/src/main/java/com/capstone4/skillops/scenario/ContractOnlyFlowRunner.java 의 newRunId 가 가짜 실행 번호(r_fake 와 8자)를,
 * service/src/main/java/com/capstone4/skillops/approval/ApprovalService.java 의 newToken 이 승인 번호(a_ 와 10자)를 만든다.
 * 번호 모양을 바꾸려면 이 클래스가 아니라 각 호출처의 접두어, 길이, 문자 집합을 고친다.
 * service/src/test/java/com/capstone4/skillops/common/RandomIdsTest.java 가 번호 모양을 고정한다.
 */
public final class RandomIds {

    /** 영문 대문자와 소문자, 숫자를 합친 62자다. 연습 공간 번호와 가짜 실행 번호가 이 문자 집합을 쓴다. */
    public static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

    /** 모든 호출이 나눠 쓰는 난수기다. SecureRandom 은 여러 스레드가 함께 불러도 안전하고 값을 예측하기 어렵다. */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 정적 메서드만 있는 도구 클래스라서 객체를 만들지 못하게 막는다. */
    private RandomIds() {
    }

    /**
     * prefix 뒤에 alphabet 에서 무작위로 고른 글자 length 개를 붙여 돌려준다.
     * 같은 글자가 여러 번 나올 수 있고, 이미 쓴 번호와 겹치는지는 검사하지 않는다. length 가 0 이면 prefix 만 돌려준다.
     */
    public static String next(String prefix, int length, String alphabet) {
        StringBuilder id = new StringBuilder(prefix);
        for (int i = 0; i < length; i++) {
            id.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return id.toString();
    }
}
