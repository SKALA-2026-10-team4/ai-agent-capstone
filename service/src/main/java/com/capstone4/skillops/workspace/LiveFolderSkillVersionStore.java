package com.capstone4.skillops.workspace;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 운영 폴더 skills/&lt;이름&gt;/SKILL.md 를 읽는 임시 구현. 폴더에는 운영 버전 하나만 있으므로 예전 버전은 줄 수 없다.
 * C-1 의 버전 저장소가 붙으면 그쪽이 {@code @Primary} 로 대신한다.
 */
@Component
public class LiveFolderSkillVersionStore implements SkillVersionStore {

    /** skill 이름은 폴더 이름과 같은 kebab-case. 그 밖의 이름(../ 등)은 없는 skill 로 본다. */
    private static final Pattern SKILL_NAME = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private final Path skillsDir;

    /** 경로는 글자로 받는다. Path 로 바로 받으면 Spring 이 상대 경로를 웹 리소스로 해석해 Tomcat 에서 거부된다. */
    public LiveFolderSkillVersionStore(@Value("${skillops.skills-dir}") String skillsDir) {
        this.skillsDir = Path.of(skillsDir);
    }

    @Override
    public Map<String, Integer> liveVersions() {
        Map<String, Integer> versions = new TreeMap<>();
        try (Stream<Path> dirs = Files.list(skillsDir)) {
            dirs.filter(d -> Files.isRegularFile(d.resolve("SKILL.md")))
                    .forEach(d -> {
                        String skill = d.getFileName().toString();
                        versions.put(skill, versionOf(skill, read(d.resolve("SKILL.md"))));
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("skill 폴더를 읽을 수 없습니다: " + skillsDir, e);
        }
        return Collections.unmodifiableMap(versions);
    }

    @Override
    public String fullText(String skill, int version) {
        Path file = skillsDir.resolve(skill).resolve("SKILL.md");
        if (!SKILL_NAME.matcher(skill).matches() || !Files.isRegularFile(file)) {
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

    private static int versionOf(String skill, String text) {
        Integer version = SkillHeader.parse(text).version();
        if (version == null) {
            throw new IllegalStateException(skill + " 의 SKILL.md 맨 위 칸에 version 이 없습니다");
        }
        return version;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("SKILL.md 를 읽을 수 없습니다: " + file, e);
        }
    }
}
