package com.capstone4.skillops.proposal;

import java.util.Comparator;
import java.util.List;

/**
 * 운영자가 읽는 문장에 번역투·피동형·과장 표현이 섞였는지 찾는다. 글을 고치지는 않는다.
 * 제안서 본문의 문장 틀은 이 검사에 걸리지 않아야 하고(테스트로 고정), LLM 이 쓴 후보 설명은 걸린 표현을 tone_warnings 에 남긴다.
 */
public final class ToneCheck {

    static final List<String> PHRASES = List.of("을 통해", "를 통해", "에 의해", "에 있어서", "에 대한", "되어지", "되어진",
            "보여집니다", "것입니다", "음에도 불구하고", "중 하나입니다", "사실을 확인", "혁명적", "획기적", "놀랍게도", "마법처럼",
            "차원이 다른", "변신", "도해", "두 갈래", "비로소", "손보", "먹혔", "먹힌", "읽힙니다", "여쭤");

    private ToneCheck() {
    }

    /** text 에서 찾은 표현을 글에 처음 나온 순서대로 돌려준다. 같은 표현은 한 번만 넣는다 */
    public static List<String> findIn(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return PHRASES.stream().filter(text::contains).sorted(Comparator.comparingInt(text::indexOf)).toList();
    }
}
