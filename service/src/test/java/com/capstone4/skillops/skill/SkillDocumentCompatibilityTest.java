package com.capstone4.skillops.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import com.capstone4.skillops.snapshot.DependencyAnalyzer;
import com.capstone4.skillops.snapshot.DependencyGraph;
import com.capstone4.skillops.snapshot.OrderAgentClient;
import com.capstone4.skillops.snapshot.SkillSource;
import com.capstone4.skillops.snapshot.SnapshotManifest;
import com.capstone4.skillops.workspace.SkillHeader;
import com.capstone4.skillops.workspace.SkillVersionStore;
import com.capstone4.skillops.workspace.WorkspaceRecord;
import com.capstone4.skillops.workspace.WorkspaceRepository;
import com.capstone4.skillops.workspace.WorkspaceService;

import tools.jackson.databind.json.JsonMapper;

/** 정상 문서와 주문 Agent가 허용하는 문법을 workspace에서도 보존한다. */
class SkillDocumentCompatibilityTest {

    private static final String SNAPSHOT = "11111111-2222-4333-8444-555555555555";
    private static final String COMPANION_STEP = """
            ---
            name: compatibility-step
            description: 호환성 검사 단계
            owner: backend
            version: 1
            inputs: []
            outputs: []
            depends_on: []
            ---
            본문
            """;

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

    record WorkspaceFixture(WorkspaceService service, WorkspaceRepository repository) {
    }

    /** 고정한 정상 원문 6개의 해석 값과 원본 바이트 해시가 유지되는지 확인한다. */
    @ParameterizedTest(name = "baseline {index}: {0}")
    @MethodSource("baselineDocuments")
    void frozenNormalDocumentKeepsItsMeaning(Document document) {
        assertMetadataAndSource(document);
        assertHeader(document);
    }

    /** 정상 문법 20개 모두의 해석 값과 snapshot workspace 생성 성공을 요구한다. */
    @ParameterizedTest(name = "list syntax {index}: {0}")
    @MethodSource("validListVariants")
    void acceptsAllOrderAgentValidListSyntaxInWorkspace(Document document) {
        assertMetadataAndSource(document);
        Map<String, String> texts = companionTexts(document);
        var graph = assertGraphReadable(texts);
        if (document.expected().steps().isEmpty()) {
            assertNodeDeclaration(graph, document);
        }
        WorkspaceFixture workspace = workspaceFor(texts);

        assertHeader(document);
        workspace.service().create(null, SNAPSHOT);
        assertWorkspacePreservesSources(workspace, texts);
    }

    /** 현재 skills/의 모든 문서와 전체 연결 관계를 원문 변환 없이 검사한다. */
    @Test
    void allCurrentRepositoryDocumentsRemainReadable() throws IOException {
        Map<String, String> texts = currentRepositoryTexts();
        assertThat(texts.keySet()).containsAll(fixture().baselineDocuments().stream().map(Document::skill).toList());
        texts.forEach((id, text) -> {
            assertThat(SkillMetadataValidator.validate(id, text)).as(id).isEmpty();
            assertThat(SkillHeader.parse(text).name()).isEqualTo(id);
        });
        assertGraphReadable(texts);
    }

    /** 고정한 원본 전체를 snapshot workspace로 복사해 버전과 줄바꿈 보존을 검사한다. */
    @Test
    void frozenNormalBundleCanCreateWorkspaceWithoutRewritingFiles() {
        Map<String, String> texts = baselineTexts();
        WorkspaceFixture workspace = workspaceFor(texts);

        var created = workspace.service().create(null, SNAPSHOT);

        Map<String, Integer> expectedVersions = new TreeMap<>();
        fixture().baselineDocuments().forEach(document -> expectedVersions.put(document.skill(), document.expected().version()));
        assertThat(created.skillVersions()).isEqualTo(expectedVersions);
        assertWorkspacePreservesSources(workspace, texts);
    }

    /** 고정한 원본 전체의 입력·선행 단계·실행 구조가 함께 유지되는지 확인한다. */
    @Test
    void frozenNormalFlowKeepsItsStaticContract() {
        var graph = assertGraphReadable(baselineTexts());
        fixture().baselineDocuments().forEach(document -> {
            if (document.expected().steps().isEmpty()) {
                assertNodeDeclaration(graph, document);
            } else {
                assertThat(graph.steps()).containsExactlyElementsOf(document.expected().steps());
            }
        });
    }

    /** 일반 단계의 필수 목록은 누락·null 표현 모두 snapshot 분석에서 계속 거부한다. */
    @ParameterizedTest
    @ValueSource(strings = {"inputs", "outputs", "depends_on"})
    void requiredStepListsRemainInvalidWhenMissingOrNull(String field) {
        String flow = fixture().validListVariants().stream()
                .filter(document -> document.id().equals("flow-optional-lists-omitted"))
                .findFirst().orElseThrow().text();
        for (String declaration : List.of("", field + ": null\n", field + ": ~\n", field + ":\n")) {
            Map<String, SkillSource> sources = Map.of(
                    "order-flow", SkillSource.fromText("skills/order-flow/SKILL.md", flow),
                    "compatibility-step", SkillSource.fromText("skills/compatibility-step/SKILL.md",
                            COMPANION_STEP.replace(field + ": []\n", declaration)));

            var graph = DependencyAnalyzer.analyze("order-flow", sources);

            assertThat(graph.complete()).as(field + ": " + declaration).isFalse();
            assertThat(graph.nodes().getFirst().parseError()).contains(field);
            assertThat(FlowStructureValidator.validate(graph)).extracting(FlowStructureValidator.Violation::reason)
                    .containsExactly("UNREADABLE_SKILL");
        }
    }

    /** 필수 정보·본문·원문 해시를 외부에 고정한 기대값과 대조한다. */
    private static void assertMetadataAndSource(Document document) {
        assertThat(SkillMetadataValidator.validate(document.skill(), document.text())).as(document.id()).isEmpty();
        var parsed = SkillFrontmatter.readForExecution(document.text());
        Expected expected = document.expected();
        assertThat(parsed.fields()).containsEntry("name", expected.name()).containsEntry("description", expected.description())
                .containsEntry("owner", expected.owner()).containsEntry("version", expected.version());
        assertThat(parsed.body().strip()).isEqualTo(expected.body());
        assertThat(SkillSource.fromText(document.path(), document.text()).fileHash()).isEqualTo(document.sha256());
    }

    /** workspace가 제공하는 각 목록과 버전을 실제 실행기와 공유하는 기대값과 대조한다. */
    private static void assertHeader(Document document) {
        SkillHeader header = SkillHeader.parse(document.text());
        Expected expected = document.expected();
        assertThat(header.name()).isEqualTo(expected.name());
        assertThat(header.version()).isEqualTo(expected.version());
        assertThat(header.inputs()).containsExactlyElementsOf(expected.inputs());
        assertThat(header.outputs()).containsExactlyElementsOf(expected.outputs());
        assertThat(header.steps()).containsExactlyElementsOf(expected.steps());
    }

    /** snapshot 분석기의 실제 전체 그래프가 읽히고 구조·공급 오류가 없는지 검사한다. */
    private static DependencyGraph assertGraphReadable(Map<String, String> texts) {
        Map<String, SkillSource> sources = new TreeMap<>();
        texts.forEach((id, text) -> sources.put(id, SkillSource.fromText("skills/" + id + "/SKILL.md", text)));
        var graph = DependencyAnalyzer.analyze("order-flow", sources);
        assertThat(graph.complete()).isTrue();
        assertThat(graph.missing()).isEmpty();
        assertThat(FlowStructureValidator.validate(graph)).isEmpty();
        return graph;
    }

    /** snapshot 노드의 입력·출력·선행 선언이 고정한 값과 같은지 검사한다. */
    private static void assertNodeDeclaration(DependencyGraph graph, Document document) {
        var node = graph.nodes().stream().filter(value -> value.skillId().equals(document.skill())).findFirst().orElseThrow();
        assertThat(node.inputs()).containsExactlyElementsOf(document.expected().inputs());
        assertThat(node.outputs()).containsExactlyElementsOf(document.expected().outputs());
        assertThat(node.dependsOn()).containsExactlyElementsOf(document.expected().dependsOn());
    }

    /** 원인별 문법을 실제 flow 분석에 넣기 위한 최소 정상 문서 묶음을 만든다. */
    private static Map<String, String> companionTexts(Document document) {
        Map<String, String> texts = new TreeMap<>();
        if (document.expected().steps().isEmpty()) {
            texts.put("order-flow", "---\nname: order-flow\ndescription: 호환성 검사\nowner: backend\nversion: 1\n"
                    + "steps: [" + document.skill() + "]\n---\n본문\n");
        } else {
            texts.put("compatibility-step", COMPANION_STEP);
        }
        texts.put(document.skill(), document.text());
        return texts;
    }

    /** HTTP·DB 대신 경계만 대체하고 실제 snapshot workspace 생성 코드를 연결한다. */
    private static WorkspaceFixture workspaceFor(Map<String, String> texts) {
        WorkspaceRepository repository = mock(WorkspaceRepository.class);
        OrderAgentClient orderAgent = mock(OrderAgentClient.class);
        var entries = texts.entrySet().stream().map(entry -> {
            String path = "skills/" + entry.getKey() + "/SKILL.md";
            when(orderAgent.readText(SNAPSHOT, path)).thenReturn(entry.getValue());
            return new SnapshotManifest.FileEntry(path, SkillSource.fromText(path, entry.getValue()).fileHash(),
                    entry.getValue().getBytes(StandardCharsets.UTF_8).length);
        }).toList();
        when(orderAgent.getSnapshot(SNAPSHOT)).thenReturn(
                new SnapshotManifest(SNAPSHOT, "fixture", "2026-10-23T00:00:00Z", Map.of(), entries));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        return new WorkspaceFixture(new WorkspaceService(repository, mock(SkillVersionStore.class), orderAgent), repository);
    }

    /** 저장된 원문이 CRLF와 끝 줄바꿈까지 입력과 같고 출처도 보존됐는지 검사한다. */
    private static void assertWorkspacePreservesSources(WorkspaceFixture workspace, Map<String, String> texts) {
        var saved = ArgumentCaptor.forClass(WorkspaceRecord.class);
        verify(workspace.repository()).save(saved.capture());
        assertThat(saved.getValue().getSourceSnapshotId()).isEqualTo(SNAPSHOT);
        assertThat(saved.getValue().getSkills()).hasSameSizeAs(texts);
        texts.forEach((id, text) -> assertThat(saved.getValue().getSkills().get(id).fullText()).as(id).isEqualTo(text));
    }

    /** 고정된 정상 원본 묶음을 Skill 이름으로 묶는다. */
    private static Map<String, String> baselineTexts() {
        Map<String, String> texts = new TreeMap<>();
        fixture().baselineDocuments().forEach(document -> texts.put(document.skill(), document.text()));
        return texts;
    }

    /** 지금 저장소에 있는 문서도 별도로 읽어 고정본 외의 회귀를 감지한다. */
    private static Map<String, String> currentRepositoryTexts() throws IOException {
        Map<String, String> texts = new TreeMap<>();
        try (Stream<Path> directories = Files.list(Path.of("../skills"))) {
            for (Path directory : directories.sorted().toList()) {
                Path document = directory.resolve("SKILL.md");
                if (Files.isRegularFile(document)) {
                    texts.put(directory.getFileName().toString(), Files.readString(document));
                }
            }
        }
        return texts;
    }

    /** 원문을 고정한 정상 문서 목록을 제공한다. */
    static Stream<Document> baselineDocuments() {
        return fixture().baselineDocuments().stream();
    }

    /** 실제 실행기에서 허용하는 정상 목록 표현을 제공한다. */
    static Stream<Document> validListVariants() {
        return fixture().validListVariants().stream();
    }

    /** 실제 주문 Agent 테스트와 공유하는 기준 데이터를 읽는다. */
    private static Fixture fixture() {
        return JsonMapper.builder().build().readValue(
                Path.of("../docs/contracts/examples/skill-document-compatibility.json").toFile(), Fixture.class);
    }
}
