package com.capstone4.skillops.scenario;

import java.util.List;

/**
 * 실행 한 건의 채점 결과.
 *
 * @param skill   처음 오류가 난 단계의 skill. 오류 없이 결과만 다르면 null
 * @param reasons 떨어진 이유. 사람이 읽을 한국어 한 줄씩
 */
public record GradeResult(boolean passed, String skill, List<String> reasons) {
}
