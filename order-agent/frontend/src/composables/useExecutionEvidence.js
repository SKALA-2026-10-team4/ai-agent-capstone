// 실행 증거 패널의 상태 useExecutionEvidence 를 정의하는 composable 이다.
// 조회 순서는 trace, 그 trace 의 snapshot manifest, snapshot 의 첫 파일이고, 시연 후보 고정(publish)도 여기서 한다.
// 화면 배선은 order-agent/frontend/src/components/ExecutionInspector.vue 가, 표시 문구는
// order-agent/frontend/src/utils/evidenceFormat.js 가 맡는다.
import { ref } from "vue";
import {
  listExecutions,
  getTrace,
  getSnapshot,
  getSnapshotFile,
  publishSnapshot,
} from "../api/evidence.js";

/**
 * 실행 증거(trace, snapshot) 조회와 시연 후보 고정 상태를 관리한다.
 * api 는 order-agent/frontend/src/api/evidence.js 의 함수가 기본값이고, 테스트는 가짜 api 를 넣는다.
 * executionId 와 locked 는 컴포넌트 props 를 읽는 getter 이고, onPublished 는 후보를 고정한 뒤 새 snapshot_id 를 받는 콜백이다.
 * generation 은 trace 조회, fileGeneration 은 파일 조회의 세대 번호다. 새 조회를 시작하면 이전 응답은 반영하지 않는다.
 * order-agent/frontend/src/components/ExecutionInspector.vue 가 이 함수를 한 번 부르고 lifecycle hook 과 watch 배선도 맡는다.
 * 이 함수가 lifecycle hook 과 watch 를 부르지 않으므로 order-agent/frontend/tests/evidence.test.js 가 node --test 로 이 상태를 검사한다.
 */
export function useExecutionEvidence(
  api = {
    listExecutions,
    getTrace,
    getSnapshot,
    getSnapshotFile,
    publishSnapshot,
  },
  {
    executionId = () => null,
    locked = () => false,
    onPublished = () => {},
  } = {},
) {
  // recent 는 최근 실행 목록, trace 는 선택한 실행 기록, snapshot 은 그 실행에 고정된 파일 묶음의 manifest,
  // file 은 읽은 파일 응답(해시, 인코딩, 본문), path 는 선택한 파일 경로다.
  const recent = ref([]),
    trace = ref(null),
    snapshot = ref(null),
    file = ref(null),
    path = ref("");
  // error 는 화면에 보일 마지막 오류 문구, loading 은 trace 를 읽는 중인지, publishing 은 후보 고정 요청 중인지 나타낸다.
  // published 는 마지막으로 고정한 후보의 manifest 다. 패널이 후보 선택 안내를 보일지 정할 때 쓴다.
  const error = ref(""),
    loading = ref(false),
    publishing = ref(false),
    published = ref(null);
  // dispose 가 진행 중인 모든 조회를 한 번에 취소하도록 취소 신호 하나를 모든 호출이 함께 쓴다.
  const controller = new AbortController();
  let generation = 0,
    fileGeneration = 0;
  /** 모든 API 호출에 공유 취소 신호를 붙인다. timeout 은 order-agent/frontend/src/api/client.js 의 기본값을 따른다. */
  const options = () => ({ signal: controller.signal });
  /**
   * 실행 하나의 trace 를 읽고, snapshot 이 있으면 manifest 와 첫 파일까지 이어서 읽는다. id 가 비어 있으면 아무것도 하지 않는다.
   * 시작할 때 파일 세대 번호도 올려, 이전 snapshot 의 늦은 파일 응답이 새 화면을 덮지 못하게 한다.
   * 이전 trace 는 새 trace 가 올 때까지 남겨 두고, 실패하면 error 에 메시지를 둔다.
   * order-agent/frontend/src/components/ExecutionInspector.vue 의 executionId watch 와 실행 select 의 change 가 부르고,
   * refresh 의 자동 선택도 부른다.
   */
  async function inspect(id) {
    if (!id) return;
    const current = ++generation;
    fileGeneration++;
    loading.value = true;
    error.value = "";
    file.value = null;
    snapshot.value = null;
    path.value = "";
    try {
      const record = await api.getTrace(id, options());
      if (current !== generation) return;
      trace.value = record;
      if (record.snapshot_id) await showSnapshot(record.snapshot_id, current);
    } catch (e) {
      if (current === generation) error.value = e.message;
    } finally {
      if (current === generation) loading.value = false;
    }
  }
  /**
   * trace 에 연결된 snapshot manifest 를 읽고, 첫 파일을 기본 선택으로 두어 readFile 로 읽는다.
   * 그 사이 다른 inspect 가 시작되면 결과를 버린다. inspect 가 부른다.
   * manifest 조회 오류는 잡지 않고 inspect 의 catch 로 넘기고, 파일 조회 오류는 readFile 이 직접 처리한다.
   */
  async function showSnapshot(snapshotId, current) {
    const bundle = await api.getSnapshot(snapshotId, options());
    if (current !== generation) return;
    snapshot.value = bundle;
    path.value = bundle.files[0]?.path || "";
    if (path.value) await readFile();
  }
  /**
   * 최근 실행 목록을 다시 읽는다. 보이는 trace 도 없고 주문에서 받은 executionId 도 없으면 가장 최근 실행을 자동으로 연다.
   * 이 자동 선택 덕분에 새로고침 뒤에도 이전 실행 기록이 바로 보이고, order-agent/frontend/tests/e2e/evidence.spec.js 의
   * 'history remains inspectable after page reload' 가 이 동작을 확인한다.
   * 템플릿의 @click 이 이벤트 객체를 첫 인자로 넘기므로 매개변수를 두지 않는다.
   * order-agent/frontend/src/components/ExecutionInspector.vue 가 화면이 붙을 때, executionId 를 inspect 한 뒤,
   * "실행 목록 새로고침" 버튼에서 부른다.
   */
  async function refresh() {
    try {
      const result = await api.listExecutions(options());
      recent.value = result.items;
      if (!trace.value && !executionId() && recent.value.length)
        await inspect(recent.value[0].execution_id);
    } catch (e) {
      error.value = e.message;
    }
  }
  /**
   * 선택한 snapshot 파일 하나의 본문을 읽는다. 겹쳐 부르면 가장 나중에 시작한 조회 결과만 반영한다.
   * 템플릿의 @change 가 이벤트 객체를 넘기므로 매개변수 없이 path ref 를 읽는다.
   * showSnapshot 과 order-agent/frontend/src/components/ExecutionInspector.vue 의 파일 select 가 부른다.
   */
  async function readFile() {
    if (!snapshot.value || !path.value) return;
    const current = ++fileGeneration;
    try {
      const result = await api.getSnapshotFile(
        snapshot.value.snapshot_id,
        path.value,
        options(),
      );
      if (current === fileGeneration) file.value = result;
    } catch (e) {
      if (current === fileGeneration) error.value = e.message;
    }
  }
  /**
   * 미리 준비한 시연 fixture 를 새 snapshot 으로 고정하고 onPublished 로 새 snapshot_id 를 알린다.
   * 주문 입력이 잠겨 있거나 이미 고정 요청 중이면 무시한다. onPublished 는 published 를 채운 직후, publishing 을 끄기 전에 부른다.
   * order-agent/frontend/src/components/ExecutionInspector.vue 의 fixture 버튼이 부르고, onPublished 는 snapshot 이벤트로
   * order-agent/frontend/src/App.vue 까지 올라가 다음 주문 입력에 snapshot_id 를 넣는다.
   */
  async function publish(fixture) {
    if (locked() || publishing.value) return;
    publishing.value = true;
    error.value = "";
    try {
      published.value = await api.publishSnapshot(fixture, options());
      onPublished(published.value.snapshot_id);
    } catch (e) {
      error.value = e.message;
    } finally {
      publishing.value = false;
    }
  }
  /**
   * 늦게 도착하는 응답을 무시하고 진행 중인 요청을 취소한다.
   * order-agent/frontend/src/components/ExecutionInspector.vue 의 onUnmounted 가 부른다.
   */
  function dispose() {
    generation++;
    fileGeneration++;
    controller.abort();
  }
  // order-agent/frontend/src/components/ExecutionInspector.vue 가 이 객체를 컴포넌트 최상위에서 구조 분해해 ref 로 쓴다.
  // 특히 path 는 파일 select 의 v-model 이 ref 에 직접 쓰므로 최상위 변수여야 한다.
  return {
    recent,
    trace,
    snapshot,
    file,
    path,
    error,
    loading,
    publishing,
    published,
    inspect,
    refresh,
    readFile,
    publish,
    dispose,
  };
}
