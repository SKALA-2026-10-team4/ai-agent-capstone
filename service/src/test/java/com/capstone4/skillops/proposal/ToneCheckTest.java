package com.capstone4.skillops.proposal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ToneCheckTest {

    @Test
    void findsBannedPhrasesInOrder() {
        assertThat(ToneCheck.findIn("이 기능을 통해 모으고, 알고리즘에 의해 정합니다"))
                .containsExactly("을 통해", "에 의해");
    }

    @Test
    void plainSentenceHasNone() {
        assertThat(ToneCheck.findIn("이 기능으로 데이터를 모을 수 있습니다")).isEmpty();
    }

    @Test
    void nullOrBlankHasNone() {
        assertThat(ToneCheck.findIn(null)).isEmpty();
        assertThat(ToneCheck.findIn("")).isEmpty();
    }

    @Test
    void findsExaggerationAndAwkwardWords() {
        assertThat(ToneCheck.findIn("획기적인 개선으로 비로소 확인됩니다")).containsExactly("획기적", "비로소");
    }
}
