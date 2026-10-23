package com.capstone4.orderagent.workspace;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Clock;
import java.util.*;
import com.capstone4.orderagent.harness.AgentException;
import com.capstone4.orderagent.harness.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * order-agent/backend/src/main/java/com/capstone4/orderagent/workspace/SnapshotService.java 의 readPublishedBundle 을 보호하는 특성 테스트다.
 * 게시 번들(bundle.json manifest) 읽기의 정상 결과와 거절 규칙(INCOMPLETE_BUNDLE, INVALID_BUNDLE_PATH, BUNDLE_READ_ERROR)을 고정한다.
 * readsTheRepositoryBundle 은 저장소의 order-agent/skills 를 그대로 읽고, 나머지 테스트는 임시 폴더에
 * order-agent/scripts/publish-skills.py 가 만드는 모양의 manifest 를 직접 쓴다. 모두 DB 없이 돈다.
 */
class PublishedBundleReaderTest {
    @TempDir Path temp;

    /** 생성자 인자 구성과 무관하게 skills 루트만 지정한 SnapshotService 를 만든다. 나머지 협력 객체는 mock 이다. */
    static SnapshotService service(Path root) throws Exception {
        var constructor=Arrays.stream(SnapshotService.class.getConstructors()).findFirst().orElseThrow();
        var args=Arrays.stream(constructor.getParameterTypes())
            .map(type->type==String.class?root.toString():type==Clock.class?Clock.systemUTC():mock(type)).toArray();
        return (SnapshotService)constructor.newInstance(args);
    }
    /** 파일을 쓰고 그 해시를 담은 manifest 를 만든다. */
    Path bundle(Map<String,String> files) throws Exception {
        Path root=temp.resolve("skills");
        var hashes=new TreeMap<String,Object>();
        for(var entry:files.entrySet()){
            Path file=root.resolve(entry.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file,entry.getValue());
            hashes.put(entry.getKey(),Json.hash(entry.getValue().getBytes(StandardCharsets.UTF_8)));
        }
        manifest(root,Map.of("release","camping-v1","files",hashes));
        return root;
    }
    /** manifest 객체를 루트의 bundle.json 으로 쓴다. 일부러 잘못된 manifest 를 만들 때도 쓴다. */
    static void manifest(Path root,Object manifest) throws Exception {
        Files.writeString(root.resolve("bundle.json"),Json.write(manifest));
    }
    /** 실패가 AgentException 인지 확인하고 그 category 를 꺼낸다. */
    static String code(Throwable failure){
        assertThat(failure).isInstanceOf(AgentException.class);
        return ((AgentException)failure).category();
    }

    /** manifest 와 실제 파일 집합·해시가 같으면 skills/ 접두어를 붙인 바이트 묶음을 돌려준다. */
    @Test void readsEveryDeclaredFileWithSkillsPrefix() throws Exception {
        Path root=bundle(Map.of("payment/SKILL.md","결제","payment/resources/policy.md","정책"));
        var files=service(root).readPublishedBundle();
        assertThat(files.keySet()).containsExactly("skills/payment/SKILL.md","skills/payment/resources/policy.md");
        assertThat(new String(files.get("skills/payment/SKILL.md"),StandardCharsets.UTF_8)).isEqualTo("결제");
    }
    /**
     * 실제 저장소의 게시 번들(order-agent/skills)도 manifest 그대로 읽힌다. Gradle 테스트의 작업 폴더가 order-agent/backend 라
     * 상대 경로 ../skills 가 그 폴더를 가리킨다. SKILL.md 를 고친 뒤 publish-skills.py 로 다시 봉인하지 않으면 이 테스트가 실패한다.
     */
    @Test void readsTheRepositoryBundle() throws Exception {
        var files=service(Path.of("../skills")).readPublishedBundle();
        assertThat(files.keySet()).allMatch(path->path.startsWith("skills/")).contains("skills/order-flow/SKILL.md");
        assertThat(files).hasSize(Json.object(Files.readString(Path.of("../skills/bundle.json"))).get("files") instanceof Map<?,?> m?m.size():-1);
    }
    /** 선언되지 않은 파일, 빠진 파일, 해시 불일치, 빈 manifest 는 INCOMPLETE_BUNDLE 이다. */
    @Test void mismatchedBundlesAreIncomplete() throws Exception {
        Path extra=bundle(Map.of("payment/SKILL.md","결제"));
        Files.writeString(extra.resolve("payment/extra.md"),"추가");
        assertThat(code(catchThrowable(()->service(extra).readPublishedBundle()))).isEqualTo("INCOMPLETE_BUNDLE");
        Files.delete(extra.resolve("payment/extra.md"));
        Files.delete(extra.resolve("payment/SKILL.md"));
        assertThat(code(catchThrowable(()->service(extra).readPublishedBundle()))).isEqualTo("INCOMPLETE_BUNDLE");
        Files.writeString(extra.resolve("payment/SKILL.md"),"변경된 결제");
        assertThat(code(catchThrowable(()->service(extra).readPublishedBundle()))).isEqualTo("INCOMPLETE_BUNDLE");
        manifest(extra,Map.of("release","camping-v1","files",Map.of()));
        assertThat(code(catchThrowable(()->service(extra).readPublishedBundle()))).isEqualTo("INCOMPLETE_BUNDLE");
        manifest(extra,Map.of("release","camping-v1"));
        assertThat(code(catchThrowable(()->service(extra).readPublishedBundle()))).isEqualTo("INCOMPLETE_BUNDLE");
    }
    /** 루트나 내부 경로에 심볼릭 링크가 있으면 INVALID_BUNDLE_PATH 다. */
    @Test void symbolicLinksAreRejected() throws Exception {
        Path root=bundle(Map.of("payment/SKILL.md","결제"));
        Path outside=Files.writeString(temp.resolve("outside.md"),"밖");
        Files.createSymbolicLink(root.resolve("payment/link.md"),outside);
        assertThat(code(catchThrowable(()->service(root).readPublishedBundle()))).isEqualTo("INVALID_BUNDLE_PATH");
        Path linkedRoot=Files.createSymbolicLink(temp.resolve("linked"),bundle(Map.of("notify/SKILL.md","알림")));
        assertThat(code(catchThrowable(()->service(linkedRoot).readPublishedBundle()))).isEqualTo("INVALID_BUNDLE_PATH");
    }
    /** 루트가 없거나 manifest 가 JSON 객체가 아니거나 files 가 객체가 아니면 BUNDLE_READ_ERROR 다. */
    @Test void unreadableBundlesAreReadErrors() throws Exception {
        assertThat(code(catchThrowable(()->service(temp.resolve("missing")).readPublishedBundle()))).isEqualTo("BUNDLE_READ_ERROR");
        Path root=bundle(Map.of("payment/SKILL.md","결제"));
        Files.writeString(root.resolve("bundle.json"),"not json");
        assertThat(code(catchThrowable(()->service(root).readPublishedBundle()))).isEqualTo("BUNDLE_READ_ERROR");
        manifest(root,Map.of("release","camping-v1","files",List.of("payment/SKILL.md")));
        assertThat(code(catchThrowable(()->service(root).readPublishedBundle()))).isEqualTo("BUNDLE_READ_ERROR");
    }
}
