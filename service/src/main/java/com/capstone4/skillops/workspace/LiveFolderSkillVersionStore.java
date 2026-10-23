package com.capstone4.skillops.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.capstone4.skillops.snapshot.SkillPaths;

/**
 * 운영 폴더의 skills/&lt;이름&gt;/SKILL.md 를 읽는 SkillVersionStore 의 임시 구현이다.
 * 폴더에는 운영 버전 하나만 있으므로 예전 버전은 줄 수 없다. C-1 의 버전 저장소가 붙으면 그쪽이 {@code @Primary} 로
 * 대신한다. Spring 이 빈으로 만들어 service/src/main/java/com/capstone4/skillops/workspace/WorkspaceService.java 의
 * 생성자에 넣는다.
 * 폴더 위치는 service/src/main/resources/application.yml 의 skillops.skills-dir 이고 환경 변수 SKILLOPS_SKILLS_DIR 로
 * 바꾼다. 기본값 ../skills 는 service 폴더에서 띄울 때 저장소 루트의 skills 폴더를 가리킨다.
 */
@Component
public class LiveFolderSkillVersionStore implements SkillVersionStore {

    /** skill 폴더들이 들어 있는 운영 폴더다. */
    private final Path skillsDir;

    /**
     * skillops.skills-dir 설정값으로 만든다. 경로는 글자로 받는다. Path 로 바로 받으면 Spring 이 상대 경로를 웹 리소스로
     * 해석해 Tomcat 에서 거부된다.
     */
    public LiveFolderSkillVersionStore(@Value("${skillops.skills-dir}") String skillsDir) {
        this.skillsDir = Path.of(skillsDir);
    }

    /**
     * SKILL.md 가 있는 하위 폴더마다 맨 위 칸의 version 을 읽어 skill 이름별 운영 버전을 돌려준다. 폴더 이름을 skill 이름으로
     * 쓴다. 폴더를 읽지 못하면 UncheckedIOException 을 던진다.
     */
    @Override
    public Map<String, Integer> liveVersions() {
        Map<String, Integer> versions = new TreeMap<>();
        try (Stream<Path> dirs = Files.list(skillsDir)) {
            dirs.filter(d -> Files.isRegularFile(d.resolve(SkillPaths.SKILL_FILE_NAME)))
                    .forEach(d -> {
                        String skill = d.getFileName().toString();
                        versions.put(skill, versionOf(skill, read(d.resolve(SkillPaths.SKILL_FILE_NAME))));
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("skill 폴더를 읽을 수 없습니다: " + skillsDir, e);
        }
        return Collections.unmodifiableMap(versions);
    }

    /**
     * 운영 폴더의 SKILL.md 를 읽고, 요청한 버전이 운영 버전과 같을 때만 원문을 돌려준다.
     * 다르면 예전 버전은 C-1 버전 저장소가 붙은 뒤에 쓸 수 있다는 IllegalArgumentException 을 던진다.
     */
    @Override
    public String fullText(String skill, int version) {
        Path file = skillsDir.resolve(skill).resolve(SkillPaths.SKILL_FILE_NAME);
        // skill 이름은 폴더 이름과 같은 kebab-case 다. 그 밖의 이름(../ 등)은 없는 skill 로 본다.
        if (!SkillPaths.isSkillId(skill) || !Files.isRegularFile(file)) {
            throw new IllegalArgumentException(skill + " 이라는 skill 이 없습니다");
        }
        String text = read(file);
        int live = versionOf(skill, text);
        if (live != version) {
            throw new IllegalArgumentException("운영 폴더에는 " + skill + " 버전 " + live + " 만 있습니다 (요청 " + version
                    + "). 예전 버전은 C-1 버전 저장소가 붙은 뒤 쓸 수 있습니다");
        }
        return text;
    }

    /**
     * 맨 위 칸의 version 을 읽는다. version 이 없으면 IllegalStateException 을 던진다. 요청이 아니라 서버 쪽 파일 문제라서
     * 도구 오류 code 는 INTERNAL_ERROR 가 된다. 맨 위 칸 자체가 없으면 SkillHeader.parse 의 IllegalArgumentException 이
     * 그대로 올라간다.
     */
    private static int versionOf(String skill, String text) {
        Integer version = SkillHeader.parse(text).version();
        if (version == null) {
            throw new IllegalStateException(skill + " 의 SKILL.md 맨 위 칸에 version 이 없습니다");
        }
        return version;
    }

    /** 파일을 UTF-8 글로 읽는다. 읽지 못하면 경로를 담은 UncheckedIOException 을 던진다. */
    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("SKILL.md 를 읽을 수 없습니다: " + file, e);
        }
    }
}
