package com.capstone4.skillops.scenario;

import java.util.List;

/**
 * 실행 한 번의 채점 결과다. service/src/main/java/com/capstone4/skillops/scenario/ScenarioGrader.java 의 grade() 가 만든다.
 * service/src/main/java/com/capstone4/skillops/scenario/ScenarioRunner.java 는 합격 여부를 장부에 남기고,
 * 불합격이면 skill 과 reasons 로 failures[] 한 칸을 만든다.
 *
 * @param passed  떨어진 이유가 하나도 없으면 true 다
 * @param skill   처음 오류가 난 단계의 skill 이다. 오류 없이 결과만 다르면 null 이고, 합격이어도 오류 단계가 있으면 채워진다
 * @param reasons 떨어진 이유다. 사람이 읽을 한국어 한 줄씩이며 순서는 ScenarioGrader 가 정한다
 */
public record GradeResult(boolean passed, String skill, List<String> reasons) {
}
