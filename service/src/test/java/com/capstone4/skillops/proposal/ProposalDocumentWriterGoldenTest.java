package com.capstone4.skillops.proposal;

import static com.capstone4.skillops.proposal.ProposalDocumentWriterTest.CREATED;
import static com.capstone4.skillops.proposal.ProposalDocumentWriterTest.fixture;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.json.JsonMapper;

/**
 * service/src/main/java/com/capstone4/skillops/proposal/ProposalDocumentWriter.java 의 출력 전체를 골든 파일
 * (service/src/test/resources/proposal/golden)과 한 글자씩 비교하는 특성 테스트다.
 * F1 픽스처(service/src/test/resources/proposal/f1-proposed.json)를 조금씩 바꾼 변형마다 본문 JSON 을 만들어, 문장 틀, 절 순서,
 * JSON 키 이름, 경계 입력(빈 값, 잘못된 모양, 소수 번호)의 처리가 바뀌지 않았는지 본다. kind 가 없거나 공백인 후보에서
 * NullPointerException 이 나는 지금 동작도 고정한다. 골든 파일은 리팩토링 전 코드로 만들었다. 개발 요청서가 없는 no_valid_fix 의 개요
 * 마지막 문장은 2026-10-26 에 "담당자가 Skill을 직접 고쳐야 합니다."에서 승인할 안이 없다는 사실과 볼 곳을 쓰는 문장으로 바꿨고,
 * 그 문장만 골든 파일에서 고쳤다(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.11 절). needs_tool 두 파일은 개발 요청서 픽스처
 * (service/src/test/resources/proposal/needs-tool.json)로 만든 본문이다.
 * 파일이 없으면 지금 출력으로 새로 만들고 테스트를 실패시키므로, 내용을 확인한 뒤 다시 돌린다.
 * 문장을 일부러 바꿨다면 해당 골든 파일을 지우고 다시 만들어 차이를 검토한다.
 */
class ProposalDocumentWriterGoldenTest {

    static final JsonMapper JSON = JsonMapper.builder().build();
    /** 골든 파일 폴더다. Gradle 이 service 폴더에서 테스트를 돌리므로 그 폴더 기준 경로를 쓴다. */
    static final Path GOLDEN = Path.of("src/test/resources/proposal/golden");
    /** 원문에 적용하지 못한 후보에 넣는 validation_error 값이다. */
    static final String PATCH_ERROR = "patch context does not match";

    /** 골든 파일 이름과 입력 제안서의 쌍 목록이다. 이름마다 golden 폴더에 같은 이름의 .json 파일이 하나 있다. */
    static Stream<Arguments> variants() {
        return Stream.of(
                variant("f1", f1(p -> {
                })),
                variant("no_root_cause", fixture("no-root-cause.json")),
                variant("second_untested", f1(p -> untested(candidate(p, 1)))),
                variant("none_tested", f1(p -> {
                    untested(candidate(p, 0));
                    untested(candidate(p, 1));
                    p.put("status", "no_valid_fix");
                })),
                variant("no_intent_checks", f1(p -> map(p, "intent").put("checks", List.of()))),
                variant("no_failure", f1(p -> p.put("failure", null))),
                variant("failure_trigger_no_fix", f1(p -> {
                    map(p, "trigger").put("type", "failure");
                    results(p, 0).put("fixes_failure", false);
                    candidate(p, 0).put("valid", false);
                    p.put("status", "no_valid_fix");
                    p.put("recommended_candidate_no", null);
                })),
                variant("two_passing", f1(p -> {
                    results(p, 1).put("keeps_old_orders", oldOrders(3, 3));
                    results(p, 1).put("no_broken_links", true);
                    candidate(p, 1).put("valid", true);
                    candidate(p, 1).put("rank", 2);
                })),
                variant("root_is_failed_step", f1(p -> map(p, "failure").put("skill", "inventory-check"))),
                variant("unknown_step_bad_time", f1(p -> {
                    map(p, "failure").remove("skill");
                    map(p, "failure").put("at", "not-a-time");
                })),
                variant("failure_at_missing", f1(p -> map(p, "failure").remove("at"))),
                variant("mixed_results", f1(p -> {
                    candidate(p, 0).put("rank", null);
                    candidate(p, 0).put("kind", "run_order");
                    results(p, 0).put("keeps_intent", false);
                    candidate(p, 1).put("kind", "custom_kind");
                    results(p, 1).remove("keeps_old_orders");
                    results(p, 1).put("fixes_failure", false);
                })),
                variant("other_category", f1(p -> {
                    map(p, "failure").put("category", "TIMEOUT");
                    map(p, "failure").put("message", null);
                    map(p, "failure").put("missing_inputs", List.of());
                })),
                variant("untested_without_error", f1(p -> {
                    candidate(p, 1).put("results", null);
                    candidate(p, 1).put("validation_error", null);
                })),
                variant("missing_ids", f1(p -> {
                    p.put("thread_id", null);
                    map(p, "trigger").put("change_id", null);
                    p.put("baseline_snapshot_id", null);
                    p.put("snapshot_id", null);
                })),
                variant("partial_none_passing", f1(p -> {
                    results(p, 0).put("fixes_failure", false);
                    candidate(p, 0).put("valid", false);
                    untested(candidate(p, 1));
                    p.put("status", "no_valid_fix");
                    p.put("recommended_candidate_no", null);
                })),
                variant("tone_many", f1(p -> {
                    candidate(p, 0).put("explanation", "획기적인 개선을 통해 비로소 문제가 해결되어진 것입니다.");
                    candidate(p, 1).put("explanation", "이 방법에 의해 연결이 되어지고, 그에 대한 결과가 보여집니다.");
                })),
                variant("root_skill_blank", f1(p -> {
                    map(p, "root_cause").put("skill", "");
                    map(p, "root_cause").put("proof_run_ids", "not-a-list");
                })),
                variant("recommended_decimal", f1(p -> p.put("recommended_candidate_no", 1.0))),
                variant("junk_candidates", f1(p -> {
                    Map<String, Object> first = candidate(p, 0);
                    Map<String, Object> second = candidate(p, 1);
                    first.put("patches", List.of());
                    map(maps(second.get("patches")).get(1)).put("skill", null);
                    p.put("candidates", new ArrayList<>(Arrays.asList(first, "junk", null, second)));
                })),
                variant("old_orders_partial", f1(p -> {
                    results(p, 0).put("keeps_old_orders", oldOrders(0, 0));
                    Map<String, Object> totalOnly = new LinkedHashMap<>();
                    totalOnly.put("total", 3);
                    results(p, 1).put("keeps_old_orders", totalOnly);
                })),
                variant("first_total_from_errored", f1(p -> {
                    candidate(p, 0).put("validation_error", PATCH_ERROR);
                    results(p, 0).put("keeps_old_orders", oldOrders(5, 5));
                })),
                variant("intent_without_text", f1(p -> map(p, "intent").put("text", null))),
                variant("no_trigger", f1(p -> p.put("trigger", null))),
                variant("candidate_no_text", f1(p -> {
                    candidate(p, 0).put("candidate_no", "1");
                    candidate(p, 0).put("explanation", "이 기능을 통해 고쳤습니다.");
                })),
                // 개발 요청서(docs/plans/2026-10-26-behavior-edit-root-fix.md 2.11 절). 이 두 파일은 개발 요청 기능을 넣을 때 만들었다.
                variant("needs_tool", fixture("needs-tool.json")),
                variant("needs_tool_minimal", needsTool(p -> {
                    Map<String, Object> request = map(p, "development_request");
                    request.remove("interim");
                    request.put("options", List.of(maps(request.get("options")).get(0)));
                    request.remove("change_points");
                    Map<String, Object> outputGap = map(maps(request.get("gaps")).get(1));
                    outputGap.put("key", "shipping.rain_hold");
                    outputGap.put("field", "outputs");
                    outputGap.put("kind", "tool_output_missing");
                })));
    }

    /** 개발 요청서 픽스처(service/src/test/resources/proposal/needs-tool.json)를 새로 읽어 change 로 고친 제안서를 돌려준다. */
    static Map<String, Object> needsTool(Consumer<Map<String, Object>> change) {
        Map<String, Object> proposal = fixture("needs-tool.json");
        change.accept(proposal);
        return proposal;
    }

    /** 변형 하나의 본문을 보기 좋게 줄 바꾼 JSON 으로 만들어 골든 파일과 비교한다. 파일이 없으면 만들고 실패한다. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("variants")
    void documentMatchesGoldenFile(String name, Map<String, Object> proposal) throws IOException {
        String actual = pretty(ProposalDocumentWriter.write("p_1", proposal, CREATED));
        Path file = GOLDEN.resolve(name + ".json");
        if (!Files.exists(file)) {
            Files.createDirectories(GOLDEN);
            Files.writeString(file, actual);
            fail("골든 파일을 만들었다: " + file.toAbsolutePath() + " — 내용을 확인한 뒤 다시 돌린다");
        }
        assertThat(actual).isEqualTo(normalized(Files.readString(file)));
    }

    /** kind 가 없거나 공백인 후보는 후보 종류 이름표(KIND)를 찾다가 NullPointerException 이 나는 지금 동작을 고정한다. */
    @Test
    void candidateWithoutKindThrowsNullPointerException() {
        Map<String, Object> missing = f1(p -> candidate(p, 0).remove("kind"));
        assertThatThrownBy(() -> ProposalDocumentWriter.write("p_1", missing, CREATED))
                .isInstanceOf(NullPointerException.class);
        Map<String, Object> blank = f1(p -> candidate(p, 0).put("kind", " "));
        assertThatThrownBy(() -> ProposalDocumentWriter.write("p_1", blank, CREATED))
                .isInstanceOf(NullPointerException.class);
    }

    /** 본문을 Jackson 의 기본 들여쓰기 JSON 글로 바꾼다. 저장할 때와 같은 snake_case 키가 나온다. */
    static String pretty(ProposalDocument document) {
        return normalized(JSON.writerWithDefaultPrettyPrinter().writeValueAsString(JSON.valueToTree(document)));
    }

    /** 줄바꿈을 LF 로 맞추고 앞뒤 공백을 지운 뒤 줄바꿈 하나로 끝낸다. 운영체제에 따라 골든 파일 비교가 갈리지 않게 한다. */
    static String normalized(String text) {
        return text.replace("\r\n", "\n").strip() + "\n";
    }

    /** 골든 파일 이름과 입력 제안서로 테스트 인자 하나를 만든다. */
    static Arguments variant(String name, Map<String, Object> proposal) {
        return Arguments.of(name, proposal);
    }

    /** F1 픽스처를 새로 읽어 change 로 고친 제안서를 돌려준다. */
    static Map<String, Object> f1(Consumer<Map<String, Object>> change) {
        Map<String, Object> proposal = fixture("f1-proposed.json");
        change.accept(proposal);
        return proposal;
    }

    /** 후보를 원문에 적용하지 못해 시험하지 않은 후보로 바꾼다. */
    static void untested(Map<String, Object> candidate) {
        candidate.put("validation_error", PATCH_ERROR);
        candidate.put("results", null);
        candidate.put("evidence_run_ids", List.of());
    }

    /** passed 와 total 을 이 순서로 담은 keeps_old_orders 묶음을 만든다. */
    static Map<String, Object> oldOrders(int passed, int total) {
        Map<String, Object> orders = new LinkedHashMap<>();
        orders.put("passed", passed);
        orders.put("total", total);
        return orders;
    }

    /** JSON 객체 값을 Map 으로 형 변환한다. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    /** 제안서의 key 칸을 Map 으로 꺼낸다. */
    static Map<String, Object> map(Map<String, Object> proposal, String key) {
        return map(proposal.get(key));
    }

    /** JSON 목록 값을 원소 형을 정하지 않은 목록으로 형 변환한다. */
    @SuppressWarnings("unchecked")
    static List<Object> maps(Object value) {
        return (List<Object>) value;
    }

    /** 제안서의 index 번째 후보를 꺼낸다. index 는 0부터 센다. */
    static Map<String, Object> candidate(Map<String, Object> proposal, int index) {
        return map(maps(proposal.get("candidates")).get(index));
    }

    /** index 번째 후보의 results 를 꺼낸다. */
    static Map<String, Object> results(Map<String, Object> proposal, int index) {
        return map(candidate(proposal, index).get("results"));
    }
}
