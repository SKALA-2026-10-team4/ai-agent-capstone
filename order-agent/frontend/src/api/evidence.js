// TODO(A/C-1 UI 연동): 공용 trace·snapshot API가 확정되면 이 모듈에서 경로/DTO를 변환한다.
// snapshot ID와 실행 ID를 혼용하거나 존재하지 않는 과거 실행 기록을 생성하지 않는다.
import { request } from './client.js'
export const listExecutions = (options) => request('/executions', options)
export const getTrace = (id, options) => request(`/executions/${encodeURIComponent(id)}/trace`, options)
export const getSnapshot = (id, options) => request(`/snapshots/${encodeURIComponent(id)}`, options)
export const getSnapshotFile = (id, path, options) => request(`/snapshots/${encodeURIComponent(id)}/files?path=${encodeURIComponent(path)}`, options)
export const publishSnapshot = (fixture, options) => request('/snapshots', {
  method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ fixture }), ...options,
})
