package com.capstone4.orderagent.harness;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.json.JsonMapper;

/** service와 같은 정상 원문·허용 문법을 실제 주문 Agent 로더로 검증한다. */
class SkillDocumentCompatibilityTest {

    record Expected(String name, String description, String owner, int version, List<String> inputs,
            List<String> outputs, List<String> dependsOn, List<String> steps, String body) {
    }

    record Document(String id, String skill, String path, String sha256, String text, Expected expected) {

        /** 매개변수 테스트 이름에는 긴 원문 대신 사례 번호만 표시한다. */
        @Override
        public String toString() {
            return id;
        }
    }

    record Fixture(String baselineCommit, List<Document> baselineDocuments, List<Document> validListVariants) {
    }

    /** 고정한 원문이 필수 정보·목록·본문·파일 해시를 그대로 유지하는지 확인한다. */
    @ParameterizedTest(name = "baseline {index}: {0}")
    @MethodSource("baselineDocuments")
    void frozenNormalDocumentKeepsItsExecutionMeaning(Document document) {
        assertLoadedDocument(document);
    }

    /** null·생략·빈 목록을 포함한 모든 정상 변형을 실제 로더가 수락하는지 확인한다. */
    @ParameterizedTest(name = "valid syntax {index}: {0}")
    @MethodSource("validListVariants")
    void executionLoaderAcceptsSupportedListSyntax(Document document) {
        assertLoadedDocument(document);
    }

    /** 고정한 원본 전체의 후보 조회와 실제 flow 정적 검증이 유지되는지 확인한다. */
    @Test
    void frozenNormalBundleKeepsItsCandidatesAndFlow() {
        SortedMap<String, byte[]> files = new TreeMap<>();
        fixture().baselineDocuments().forEach(document ->
                files.put(document.path(), document.text().getBytes(StandardCharsets.UTF_8)));

        assertCandidatesAndFlow(files);
    }

    /** 현재 저장소 문서도 모두 읽어 기준 원본 외의 문서 변경을 검사한다. */
    @Test
    void allCurrentRepositoryDocumentsRemainLoadable() throws IOException {
        SortedMap<String, byte[]> files = new TreeMap<>();
        try (Stream<Path> directories = Files.list(Path.of("../../skills"))) {
            for (Path directory : directories.sorted().toList()) {
                Path document = directory.resolve("SKILL.md");
                if (Files.isRegularFile(document)) {
                    files.put(SkillLoader.skillPath(directory.getFileName().toString()), Files.readAllBytes(document));
                }
            }
        }
        assertThat(files.keySet()).containsAll(fixture().baselineDocuments().stream().map(Document::path).toList());

        assertCandidatesAndFlow(files);
    }

    /** 일반 단계의 필수 목록 누락·null 거부가 실제 로더에서도 유지되는지 확인한다. */
    @ParameterizedTest
    @ValueSource(strings = {"inputs", "outputs", "depends_on"})
    void requiredStepListsRemainInvalidWhenMissingOrNull(String field) {
        Document normal = fixture().validListVariants().stream()
                .filter(document -> document.id().equals("step-required-empty-lists")).findFirst().orElseThrow();
        for (String declaration : List.of("", field + ": null\n", field + ": ~\n", field + ":\n")) {
            var files = new TreeMap<String, byte[]>();
            files.put(normal.path(), normal.text().replace(field + ": []\n", declaration).getBytes(StandardCharsets.UTF_8));

            assertThatThrownBy(() -> new SkillLoader().load(files, normal.skill()))
                    .as(field + ": " + declaration).isInstanceOf(AgentException.class).hasMessage("INVALID_SKILL_FORMAT");
        }
    }

    /** root를 읽을 수 있어도 비어 있는 실행 순서가 실제 flow 검증을 통과하지 못하게 한다. */
    @ParameterizedTest
    @ValueSource(strings = {"", "steps: null\n", "steps: ~\n", "steps: []\n", "steps:\n"})
    void emptyOrNullRootStepsNeverPassExecutionValidation(String declaration) {
        Document normal = fixture().validListVariants().stream()
                .filter(document -> document.id().equals("step-required-empty-lists")).findFirst().orElseThrow();
        String text = normal.text().replace("name: compatibility-step", "name: order-flow")
                .replace("\n---\n", "\n" + declaration + "---\n");
        var files = new TreeMap<String, byte[]>();
        files.put(SkillLoader.skillPath("order-flow"), text.getBytes(StandardCharsets.UTF_8));
        var loader = new SkillLoader();
        var root = loader.load(files, "order-flow");

        assertThatThrownBy(() -> loader.validateFlow(files, root))
                .isInstanceOf(AgentException.class).hasMessage("INVALID_FLOW");
    }

    /** 실행기가 반환하는 모든 선언과 증거를 공유 fixture의 기대값과 대조한다. */
    private static void assertLoadedDocument(Document document) {
        SortedMap<String, byte[]> files = new TreeMap<>();
        files.put(document.path(), document.text().getBytes(StandardCharsets.UTF_8));

        SkillLoader.Skill loaded = new SkillLoader().load(files, document.skill());

        Expected expected = document.expected();
        assertThat(loaded.id()).isEqualTo(expected.name());
        assertThat(loaded.version()).isEqualTo(expected.version());
        assertThat(loaded.owner()).isEqualTo(expected.owner());
        assertThat(loaded.description()).isEqualTo(expected.description());
        assertThat(loaded.inputs()).containsExactlyElementsOf(expected.inputs());
        assertThat(loaded.outputs()).containsExactlyElementsOf(expected.outputs());
        assertThat(loaded.dependsOn()).containsExactlyElementsOf(expected.dependsOn());
        assertThat(loaded.steps()).containsExactlyElementsOf(expected.steps());
        assertThat(loaded.body()).isEqualTo(expected.body());
        assertThat(loaded.path()).isEqualTo(document.path());
        assertThat(loaded.hash()).isEqualTo(document.sha256());
    }

    /** 후보 조회가 모든 문서를 읽고 실제 실행 순서의 연결 검사도 통과하는지 확인한다. */
    private static void assertCandidatesAndFlow(SortedMap<String, byte[]> files) {
        var loader = new SkillLoader();
        assertThat(loader.candidates(files)).hasSize(files.size());
        var flow = loader.load(files, "order-flow");

        assertThat(loader.validateFlow(files, flow)).extracting(SkillLoader.Skill::id)
                .containsExactlyElementsOf(flow.steps());
    }

    /** 원문을 고정한 정상 문서 목록을 제공한다. */
    static Stream<Document> baselineDocuments() {
        return fixture().baselineDocuments().stream();
    }

    /** service에서도 검사하는 정상 목록 표현을 제공한다. */
    static Stream<Document> validListVariants() {
        return fixture().validListVariants().stream();
    }

    /** service와 같은 기준 데이터를 저장소 계약 디렉터리에서 읽는다. */
    private static Fixture fixture() {
        return JsonMapper.builder().build().readValue(
                Path.of("../../docs/contracts/examples/skill-document-compatibility.json").toFile(), Fixture.class);
    }
}
