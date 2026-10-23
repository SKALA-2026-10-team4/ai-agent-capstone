package com.capstone4.skillops.common;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * service/src/main/java/com/capstone4/skillops/common/RandomIds.java 의 next 가 만드는 번호 모양(접두어, 길이, 문자 집합)과
 * ALPHANUMERIC 의 62자를 고정한다. 연습 공간 번호(w_), 가짜 실행 번호(r_fake), 승인 번호(a_)가 모두 next 로 만들어지므로
 * 이 모양에 기댄다.
 */
class RandomIdsTest {

    /** 무작위 값이라 200번 만들어 매번 모양이 맞는지 본다. */
    @Test
    void prefixThenLengthCharactersOfTheAlphabet() {
        for (int i = 0; i < 200; i++) {
            assertThat(RandomIds.next("w_", 8, RandomIds.ALPHANUMERIC)).matches("w_[A-Za-z0-9]{8}");
            assertThat(RandomIds.next("r_fake", 8, RandomIds.ALPHANUMERIC)).matches("r_fake[A-Za-z0-9]{8}");
        }
    }

    @Test
    void usesOnlyTheGivenAlphabet() {
        assertThat(RandomIds.next("p_", 5, "x")).isEqualTo("p_xxxxx");
        assertThat(RandomIds.next("p_", 0, "x")).isEqualTo("p_");
        assertThat(RandomIds.next("", 6, "ab")).matches("[ab]{6}");
    }

    @Test
    void alphanumericIsTheSixtyTwoLettersAndDigits() {
        assertThat(RandomIds.ALPHANUMERIC)
                .isEqualTo("ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789")
                .hasSize(62);
    }
}
