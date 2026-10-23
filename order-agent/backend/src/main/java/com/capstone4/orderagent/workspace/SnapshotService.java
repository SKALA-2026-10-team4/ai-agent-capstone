package com.capstone4.orderagent.workspace;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import com.capstone4.orderagent.harness.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Skill 파일 묶음을 바뀌지 않는 snapshot 으로 발행하고 읽는 서비스다. 주문 실행은 늘 snapshot 하나에 고정되므로 실행 도중 파일이 바뀌어도
 * 한 실행 안에서 섞이지 않고, 실패한 실행을 나중에 같은 바이트로 다시 볼 수 있다.
 * snapshot 을 만드는 길은 네 가지이며 모두 publish 로 모인다.
 * 1. capture: 게시 번들(order-agent/skills, Docker 이미지 안에서는 /app/skills)을 읽어 발행한다. 주문에 snapshot_id 가 없으면 Harness 가 쓴다.
 * 2. saveEdits: 사용자가 고친 SKILL.md 를 기준 snapshot 위에 덮어 발행하고 진단 요청 outbox 를 남긴다(POST /api/snapshots/edits).
 * 3. publish: 후보 파일 묶음을 그대로 발행한다(POST /api/snapshots/candidates).
 * 4. demo: 시연 fixture 를 만들어 발행한다(POST /api/snapshots).
 * get 은 파일 해시와 전체 해시를 다시 검증해 변조된 snapshot 을 SNAPSHOT_TAMPERED 로 거절한다.
 * 크기, 개수, 경로 한도는 아래 상수에서 바꾼다. order-agent/backend/src/test/java/com/capstone4/orderagent/integration/SnapshotLimitsIntegrationTest.java 가
 * 지금 한도와 검사 순서를 고정하고, order-agent/docs/agent-evidence.md 의 API 표도 같은 한도를 적고 있다.
 * readPublishedBundle 의 거절 규칙은 order-agent/backend/src/test/java/com/capstone4/orderagent/workspace/PublishedBundleReaderTest.java 가 고정한다.
 * 호출처는 order-agent/backend/src/main/java/com/capstone4/orderagent/harness/OrderHarness.java(capture, get),
 * order-agent/backend/src/main/java/com/capstone4/orderagent/service/ 의 OrderService.createOrder 와 DryRunService.run(get),
 * order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SkillQueryService.java(get),
 * order-agent/backend/src/main/java/com/capstone4/orderagent/controller/EvidenceController.java(saveEdits, publish, demo, get)다.
 */
@Service
public class SnapshotService {
    /** 게시 번들의 manifest 파일 이름이다. order-agent/scripts/publish-skills.py 가 이 이름으로 쓴다. */
    private static final String MANIFEST = "bundle.json";
    /** snapshot 하나의 파일 수 상한이다. 넘거나 파일이 하나도 없으면 INVALID_BUNDLE_SIZE 다. */
    private static final int MAX_FILES = 256;
    /** 파일 하나의 바이트 상한이다. 발행과 수정 저장이 함께 쓰며 넘으면 INVALID_BUNDLE_SIZE 다. */
    private static final int MAX_FILE_BYTES = 1_000_000;
    /** snapshot 전체 바이트 상한이다. 파일별 검사를 모두 통과한 뒤 마지막에 본다. */
    private static final long MAX_BUNDLE_BYTES = 5_000_000;
    /** 수정 저장 요청 하나에 담을 수 있는 변경 수 상한이다. 넘거나 비어 있으면 INVALID_SKILL_EDITS 다. */
    private static final int MAX_EDIT_CHANGES = 64;
    /**
     * snapshot 안 경로의 글자 수 상한이다. order-agent/backend/src/main/resources/schema.sql 의 snapshot_files.path 칼럼 길이(500)와
     * 같은 값이라 하나를 바꾸면 다른 하나도 바꾼다.
     */
    private static final int MAX_PATH_LENGTH = 500;
    /**
     * 수정 저장으로 바꿀 수 있는 경로 정규식이다. skills 폴더 바로 아래 소문자나 숫자로 시작하는 폴더의 SKILL.md 만 받으며 resources 파일은 고칠 수 없다.
     * SkillLoader.validateName 보다 느슨해 숫자로 시작하거나 하이픈이 겹치거나 끝에 오는 폴더도 저장되고, 그런 Skill 은 실행이나 조회 때
     * INVALID_SKILL_NAME 이 된다.
     */
    private static final String EDITABLE_SKILL_PATH = "skills/[a-z0-9][a-z0-9-]*/SKILL\\.md";
    /**
     * capture 가 snapshot metadata 의 release 로 남기는 게시본 이름이다. order-agent/scripts/publish-skills.py 의 RELEASE 가 bundle.json 에 쓰는 값과
     * 같아야 한다. readPublishedBundle 은 manifest 의 release 를 읽지 않으므로 두 값이 달라도 오류는 나지 않지만, snapshot 이 실제 게시본과
     * 다른 이름으로 남는다. 바꿀 때는 그 스크립트,
     * order-agent/backend/src/test/java/com/capstone4/orderagent/integration/EvidenceApiContractIntegrationTest.java 의 기대값,
     * frontend/src/mocks/index.js 의 mock 값을 함께 바꾼다.
     */
    private static final String CAPTURE_RELEASE = "camping-v1";
    /**
     * demo 가 만들 수 있는 시연 fixture 이름이다. frontend/src/api/orderAgent.js 의 FIXTURES 와
     * order-agent/frontend/src/components/ExecutionInspector.vue 의 SNAPSHOT_FIXTURES 가 같은 이름을 들고 있으므로 이름을 바꾸면 함께 고친다.
     */
    private static final Set<String> DEMO_FIXTURES = Set.of("baseline", "broken-contract", "repaired");
    private final SnapshotRepository store;
    // 게시 번들 루트의 절대 경로다(order-agent.skills-root, 환경 변수 ORDER_AGENT_SKILLS_ROOT, 기본 ../skills).
    private final Path root;
    private final Clock clock;
    /**
     * Spring 이 snapshot 저장소, 게시 번들 경로, UTC Clock 을 주입해 빈으로 만든다. 번들 경로는 절대 경로로 바꿔 정규화한다.
     * PublishedBundleReaderTest 는 이 생성자를 리플렉션으로 불러 번들 루트만 바꾼다.
     */
    public SnapshotService(SnapshotRepository store, @Value("${order-agent.skills-root}") String root, Clock clock) {
        this.store = store; this.root = Path.of(root).toAbsolutePath().normalize(); this.clock = clock;
    }
    /**
     * 게시 번들을 읽어 snapshot 경로(skills 접두어)별 바이트로 돌려준다. manifest(bundle.json)가 모든 파일의 해시를 고정하므로,
     * 파일이 빠지거나 더해졌거나 내용이 다르면 INCOMPLETE_BUNDLE, 루트나 내부에 심볼릭 링크가 있으면 INVALID_BUNDLE_PATH 다.
     * 파일을 다 읽은 뒤 manifest 를 한 번 더 읽어 처음과 다르면 INCOMPLETE_BUNDLE 이라, 읽는 도중 다시 봉인된 번들을 섞어 쓰지 않는다.
     * 그 밖의 읽기 실패(루트 없음, JSON 이 아닌 manifest, 객체가 아닌 files)는 BUNDLE_READ_ERROR 로 바꾼다.
     * manifest 는 order-agent/scripts/publish-skills.py 가 만든다. capture 와 demo 가 부른다.
     */
    public SortedMap<String, byte[]> readPublishedBundle() {
        try {
            if (Files.isSymbolicLink(root)) throw new AgentException("INVALID_BUNDLE_PATH");
            byte[] manifestBytes = Files.readAllBytes(root.resolve(MANIFEST));
            var declared = declaredFiles(manifestBytes);
            var files = readVerifiedFiles(listBundleFiles(), declared);
            if (!Arrays.equals(manifestBytes, Files.readAllBytes(root.resolve(MANIFEST))))
                throw new AgentException("INCOMPLETE_BUNDLE");
            return files;
        } catch (AgentException e) { throw e; }
        catch (Exception e) { throw new AgentException("BUNDLE_READ_ERROR"); }
    }
    /** manifest 의 files 객체(번들 루트 기준 상대 경로와 SHA-256 의 짝)를 읽는다. 없거나 비어 있으면 INCOMPLETE_BUNDLE 이다. */
    private static Map<?, ?> declaredFiles(byte[] manifestBytes) {
        var manifest = Json.object(new String(manifestBytes, StandardCharsets.UTF_8));
        var declared = (Map<?, ?>) manifest.get("files");
        if (declared == null || declared.isEmpty()) throw new AgentException("INCOMPLETE_BUNDLE");
        return declared;
    }
    /**
     * 번들 루트 아래 manifest 를 뺀 일반 파일의 상대 경로를 모으고 경로 구분자를 슬래시로 맞춘다. 심볼릭 링크가 하나라도 있으면 INVALID_BUNDLE_PATH 다.
     * 루트의 bundle.json 만 빼므로 봉인 스크립트가 남긴 임시 파일(.bundle.tmp)이 남아 있으면 manifest 와 어긋나 INCOMPLETE_BUNDLE 이 된다.
     */
    private SortedSet<String> listBundleFiles() throws IOException {
        var actual = new TreeSet<String>();
        try (var paths = Files.walk(root)) {
            for (var p : paths.toList()) {
                if (Files.isSymbolicLink(p)) throw new AgentException("INVALID_BUNDLE_PATH");
                if (Files.isRegularFile(p) && !p.equals(root.resolve(MANIFEST)))
                    actual.add(root.relativize(p).toString().replace('\\', '/'));
            }
        }
        return actual;
    }
    /**
     * 실제 파일 집합이 manifest 와 같고 파일마다 해시가 맞는지 확인하면서, skills 접두어를 붙인 snapshot 경로로 읽어 들인다.
     * 붙인 경로도 validatePath 규칙을 지켜야 한다.
     */
    private SortedMap<String, byte[]> readVerifiedFiles(SortedSet<String> actual, Map<?, ?> declared) throws IOException {
        if (!actual.equals(declared.keySet())) throw new AgentException("INCOMPLETE_BUNDLE");
        var files = new TreeMap<String, byte[]>();
        for (var path : actual) {
            validatePath("skills/" + path);
            byte[] bytes = Files.readAllBytes(root.resolve(path));
            if (!Json.hash(bytes).equals(declared.get(path))) throw new AgentException("INCOMPLETE_BUNDLE");
            files.put("skills/" + path, bytes);
        }
        return files;
    }
    /**
     * 지금 게시 번들을 새 snapshot 으로 발행한다. metadata 는 source 가 published-bundle, release 가 CAPTURE_RELEASE 다.
     * 내용이 같아도 부를 때마다 새 snapshot id 가 생긴다. 주문에 snapshot_id 가 없을 때 OrderHarness 의 fixSnapshot 이 부르고,
     * 통합 테스트가 기준 snapshot 을 만들 때도 쓴다.
     */
    @Transactional
    public SnapshotRepository.Stored capture() {
        return publish(readPublishedBundle(), Map.of("source", "published-bundle", "release", CAPTURE_RELEASE));
    }
    /**
     * 사용자가 고친 SKILL.md 들을 기준 snapshot 위에 덮어 새 snapshot 으로 저장하고, 진단 요청 전달 대기 행(outbox)을 같은 트랜잭션에 남긴다.
     * 게시 번들(order-agent/skills)은 바꾸지 않고 flow 계약도 검증하지 않는다.
     * 검사 순서는 요청 모양(INVALID_SKILL_EDITS), 기준 snapshot 의 존재와 무결성(SNAPSHOT_NOT_FOUND, SNAPSHOT_TAMPERED), 기준 해시(BASELINE_HASH_MISMATCH),
     * 변경별 경로와 크기, 실제로 바뀐 파일이 있는지(NO_SKILL_CHANGES)다. 실패하면 snapshot 도 outbox 행도 남지 않는다.
     * metadata 의 source 는 user-edit 이고 change_id, baseline_snapshot_id, baseline_hash, changed_paths, validation_status(QUEUED)를 담는다.
     * EvidenceController.saveEdits(POST /api/snapshots/edits)가 부르고, 남긴 행은 SkillChangeDispatcher 가 service 로 보낸다.
     */
    @Transactional
    public SnapshotRepository.Stored saveEdits(UUID baselineId, String baselineHash, Map<String, String> changes) {
        requireEditRequest(baselineId, baselineHash, changes);
        var baseline=get(baselineId);
        if(!baseline.hash().equals(baselineHash))throw new AgentException("BASELINE_HASH_MISMATCH");
        var files=new TreeMap<>(baseline.files());
        var changedPaths=applyChanges(files,changes);
        if(changedPaths.isEmpty())throw new AgentException("NO_SKILL_CHANGES");
        // 계약이나 형식이 틀린 Skill 도 진단할 수 있도록 일부러 저장한다. 검증은 실행이 맡는다.
        String changeId=UUID.randomUUID().toString();
        var saved=publish(files,Map.of("source","user-edit","change_id",changeId,
            "baseline_snapshot_id",baselineId.toString(),"baseline_hash",baselineHash,
            "changed_paths",List.copyOf(changedPaths),"validation_status","QUEUED"));
        store.insertChangeEvent(UUID.fromString(changeId),saved.id(),changeEventPayload(changeId,baselineId,saved.id(),changedPaths));
        return saved;
    }
    /** 수정 요청에 기준 snapshot id 와 기준 해시가 있고 변경이 1~MAX_EDIT_CHANGES 개인지 확인한다. 어긋나면 INVALID_SKILL_EDITS 다. */
    private static void requireEditRequest(UUID baselineId, String baselineHash, Map<String, String> changes) {
        if(baselineId==null||baselineHash==null||changes==null||changes.isEmpty()||changes.size()>MAX_EDIT_CHANGES)
            throw new AgentException("INVALID_SKILL_EDITS");
    }
    /**
     * 변경마다 경로와 내용을 먼저 보고(INVALID_SKILL_EDITS) 그다음 크기를 본다(INVALID_BUNDLE_SIZE). 기준과 바이트가 같은 변경은 버리고
     * 실제로 달라진 파일만 files 에 반영한 뒤, 바뀐 경로를 정렬해 돌려준다. 기준에 없던 경로는 새 파일로 더한다.
     */
    private static SortedSet<String> applyChanges(SortedMap<String, byte[]> files, Map<String, String> changes) {
        var changedPaths=new TreeSet<String>();
        for(var entry:changes.entrySet()){
            String path=entry.getKey(),content=entry.getValue();
            if(path==null||!path.matches(EDITABLE_SKILL_PATH)||content==null)
                throw new AgentException("INVALID_SKILL_EDITS");
            byte[] bytes=content.getBytes(StandardCharsets.UTF_8);
            if(bytes.length>MAX_FILE_BYTES)throw new AgentException("INVALID_BUNDLE_SIZE");
            if(!Arrays.equals(files.get(path),bytes)){
                files.put(path,bytes);changedPaths.add(path);
            }
        }
        return changedPaths;
    }
    /**
     * service 의 POST /internal/skill-changes 로 전달할 skill_saved 이벤트 본문 JSON 을 만든다. 키는 type, change_id, baseline_snapshot_id,
     * candidate_snapshot_id, changed_paths 다. service/src/main/java/com/capstone4/skillops/diagnosis/DiagnosisController.java 는 type 과 change_id 를,
     * skillops-agent/skillops_agent/main.py 의 DiagnoseRequest 는 candidate_snapshot_id 를 반드시 요구하고 모르는 키를 거절하므로
     * 키를 더하거나 이름을 바꾸지 않는다. service 는 이 본문을 고치지 않고 그대로 넘긴다.
     */
    private static String changeEventPayload(String changeId, UUID baselineId, UUID candidateId, SortedSet<String> changedPaths) {
        return Json.write(Map.of("type","skill_saved","change_id",changeId,
            "baseline_snapshot_id",baselineId.toString(),"candidate_snapshot_id",candidateId.toString(),
            "changed_paths",List.copyOf(changedPaths)));
    }
    // TODO(C-1/C-2 연동): 확정된 버전 집합이나 SEALED workspace revision 을 받아 발행한다.
    // 편집 중인 경로를 직접 읽지 말고 후보 메타데이터와 관련 파일 전체를 함께 넘겨야 한다.
    // 지금 저장소는 독립 데모용이다. 공용 저장소가 생기면 이 서비스 경계에서 연결하고 원장을 둘로 만들지 않는다.
    /**
     * 완성된 후보 파일 묶음 하나를 새 snapshot 으로 발행한다. metadata 와 관련 파일 전체를 한 트랜잭션에 저장해 일부만 발행된 snapshot 이 생기지 않게 한다.
     * 파일 수(1~MAX_FILES)를 먼저 보고, 경로 순서대로 파일마다 경로(validatePath)와 크기(MAX_FILE_BYTES)를 본 뒤, 합계(MAX_BUNDLE_BYTES)를 마지막에 본다.
     * 크기 위반은 INVALID_BUNDLE_SIZE, 경로 위반은 INVALID_BUNDLE_PATH 다. 바이트를 복사해 저장하므로 호출자가 넘긴 배열을 나중에 바꿔도 snapshot 은 그대로다.
     * 전체 해시는 bundleHash 로 만들고, 저장한 뒤 get 으로 다시 읽어 검증된 값을 돌려준다.
     * capture, saveEdits, demo, EvidenceController.publishCandidate 가 부르고, 통합 테스트가 후보 snapshot 을 만들 때도 직접 부른다.
     */
    @Transactional
    public SnapshotRepository.Stored publish(SortedMap<String, byte[]> files, Map<String, Object> metadata) {
        var frozen = new TreeMap<String, byte[]>();
        long total = 0;
        if (files.isEmpty() || files.size() > MAX_FILES) throw new AgentException("INVALID_BUNDLE_SIZE");
        for (var entry : files.entrySet()) {
            validatePath(entry.getKey());
            if (entry.getValue().length > MAX_FILE_BYTES) throw new AgentException("INVALID_BUNDLE_SIZE");
            total += entry.getValue().length; frozen.put(entry.getKey(), entry.getValue().clone());
        }
        if (total > MAX_BUNDLE_BYTES) throw new AgentException("INVALID_BUNDLE_SIZE");
        UUID id = UUID.randomUUID();
        store.insert(id, bundleHash(frozen, metadata), metadata, frozen, clock.instant());
        return get(id);
    }
    /**
     * snapshot 을 읽고 전체 해시를 다시 계산해 저장된 값과 다르면 SNAPSHOT_TAMPERED 다. 파일별 해시 검사는 SnapshotRepository.get 이 먼저 하고,
     * 없으면 SNAPSHOT_NOT_FOUND 다. 실행, 조회, 수정 저장이 모두 이 검증을 거치므로 DB 에서 바뀐 snapshot 은 어디에도 쓰이지 않는다.
     */
    @Transactional(readOnly = true)
    public SnapshotRepository.Stored get(UUID id) {
        var stored = store.get(id);
        if (!bundleHash(stored.files(), stored.metadata()).equals(stored.hash())) throw new AgentException("SNAPSHOT_TAMPERED");
        return stored;
    }
    /**
     * 시연 fixture 하나를 게시 번들에서 만들어 새 snapshot 으로 발행한다. 미리 준비한 시연 데이터이며 SkillOps 가 만든 수정안이 아니다.
     * baseline 은 게시 번들 그대로다. broken-contract 와 repaired 는 inventory-check 의 SKILL.md 에서 inventory.available 을
     * inventory.reservable_quantity 로 바꾼다. broken-contract 는 이어서 ", inventory.reserved" 문자열을 지워(지금 파일에서는 출력 선언에만 있다)
     * payment 단계의 정적 계약 위반을 만들고, repaired 는 reserved 기록 규칙 문장을 본문 끝에 더한다.
     * 번들을 먼저 읽은 뒤 이름을 보므로 모르는 이름이어도 번들 읽기 오류가 먼저 날 수 있다.
     * metadata 는 source 가 demo-fixture, fixture 가 그 이름이다. EvidenceController.publish(POST /api/snapshots)가 부른다.
     * 바꾸는 문자열을 고치면 order-agent/backend/src/test/java/com/capstone4/orderagent/integration/EvidenceApiContractIntegrationTest.java 의
     * 기대값과 frontend/src/mocks/skillFiles.js 의 fixtureFiles 도 함께 맞춘다.
     */
    @Transactional
    public SnapshotRepository.Stored demo(String fixture) {
        var files = readPublishedBundle();
        if (!DEMO_FIXTURES.contains(fixture)) throw new AgentException("UNKNOWN_FIXTURE");
        if (!fixture.equals("baseline")) {
            String path = "skills/inventory-check/SKILL.md";
            String text = new String(files.get(path), StandardCharsets.UTF_8);
            text = text.replace("inventory.available", "inventory.reservable_quantity");
            if (fixture.equals("broken-contract")) text = text.replace(", inventory.reserved", "");
            else text += "\n예약 가능 수량이 주문 수량 이상이면 inventory.reserved를 true로 기록한다.\n";
            files.put(path, text.getBytes(StandardCharsets.UTF_8));
        }
        return publish(files, Map.of("source", "demo-fixture", "fixture", fixture));
    }
    /**
     * snapshot 전체 해시를 만든다. 파일마다 SHA-256 을 구해 경로와 짝지은 맵과 metadata 를 한 객체로 묶어 Json.hash 의 정규화 해시를 낸다.
     * 그래서 내용이 같아도 metadata 가 다르면 해시가 다르고, 만든 시각은 해시에 들어가지 않는다.
     * 규칙을 바꾸면 이미 저장된 snapshot 이 모두 SNAPSHOT_TAMPERED 로 거절되므로 바꾸지 않는다. publish 와 get 이 쓴다.
     */
    public static String bundleHash(SortedMap<String, byte[]> files, Map<String, Object> metadata) {
        var hashes = new TreeMap<String, Object>(); files.forEach((p,b) -> hashes.put(p, Json.hash(b)));
        return Json.hash(Map.of("files", hashes, "metadata", metadata));
    }
    /**
     * snapshot 안 경로 하나를 검사한다. skills 접두어로 시작하고 MAX_PATH_LENGTH 이하이며, 역슬래시와 NUL 문자가 없고,
     * 빈 세그먼트나 점 세그먼트(. 과 ..)가 없어야 한다. 어긋나면 INVALID_BUNDLE_PATH 다.
     * 상위 폴더를 가리키는 경로와 운영체제에 따라 달리 해석되는 경로를 저장 단계에서 막는다. readVerifiedFiles 와 publish 가 부른다.
     */
    private void validatePath(String path) {
        if (!path.startsWith("skills/") || path.length() > MAX_PATH_LENGTH || path.contains("\\") || path.contains("\u0000"))
            throw new AgentException("INVALID_BUNDLE_PATH");
        for (var segment : path.split("/", -1)) if (segment.isEmpty() || segment.equals(".") || segment.equals(".."))
            throw new AgentException("INVALID_BUNDLE_PATH");
    }
}
