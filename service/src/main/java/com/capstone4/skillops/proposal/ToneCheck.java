package com.capstone4.skillops.proposal;

import java.util.Comparator;
import java.util.List;

/**
 * 운영자가 읽는 문장에 번역투, 피동형, 과장 표현, 어색한 말이 섞였는지 찾는다. 글을 고치지는 않는다.
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java 의 toneWarnings 가 LLM 이 쓴 후보 설명을 검사해
 * 걸린 표현을 tone_warnings 에 남긴다. 본문의 문장 틀은 이 검사에 걸리지 않아야 하며,
 * service/src/test/java/com/capstone4/skillops/proposal/ProposalDocumentWriterTest.java 의 templatesNeverUseBannedPhrases 가 이를 고정한다.
 */
public final class ToneCheck {

    /**
     * 찾을 표현 목록이다. skillops-agent/prompts/style.md 가 LLM 에게 쓰지 말라고 한 표현과 같은 표현을 다루며, 그 문서는
     * skillops-agent/skillops_agent/nodes.py 의 extract_intent 와 rank_and_explain 이 프롬프트에 넣는다.
     * 활용형이 여럿인 말은 줄기나 활용형으로 적었다(손보, 먹혔, 먹힌 등). 표현을 더하거나 빼면 style.md 도 함께 고친다.
     */
    static final List<String> PHRASES = List.of("을 통해", "를 통해", "에 의해", "에 있어서", "에 대한", "되어지", "되어진",
            "보여집니다", "것입니다", "음에도 불구하고", "중 하나입니다", "사실을 확인", "혁명적", "획기적", "놀랍게도", "마법처럼",
            "차원이 다른", "변신", "도해", "두 갈래", "비로소", "손보", "먹혔", "먹힌", "읽힙니다", "여쭤");

    /** 정적 메서드만 있는 클래스라서 인스턴스를 만들지 않는다. */
    private ToneCheck() {
    }

    /** text 에서 찾은 표현을 글에 처음 나온 순서대로 돌려준다. 같은 표현은 한 번만 넣고, text 가 없거나 공백뿐이면 빈 목록을 돌려준다. */
    public static List<String> findIn(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return PHRASES.stream().filter(text::contains).sorted(Comparator.comparingInt(text::indexOf)).toList();
    }
}
