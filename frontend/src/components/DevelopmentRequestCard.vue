<script setup>
// 개발 요청서 카드다. 담당자 수정이 어떤 업무 도구도 만들지 않는 값을 요구해 SKILL.md 수정만으로는 고칠 수
// 없을 때, SkillOps 가 제안서에 담은 development_request 를 보여 준다. 모양은
// docs/plans/2026-10-26-behavior-edit-root-fix.md 2.11 절과 docs/contracts/proposal.schema.json 이다.
// 필요한 값(gaps), 제안 방법(options), 바꿀 곳(change_points), 임시 대안(interim), 근거 실행을 차례로 그리고,
// '마크다운으로 복사' 버튼은 개발팀에 그대로 보낼 한국어 마크다운을 클립보드에 넣는다. 이름표와 마크다운은
// frontend/src/utils/developmentRequest.js 가 만든다. 글은 모두 텍스트 보간으로만 넣는다.
// frontend/src/views/ProposalView.vue 와 frontend/src/views/DiagnosisView.vue 가 쓴다.
import { computed } from 'vue'
import { ClipboardCopy, Wrench } from 'lucide-vue-next'
import { useToastStore } from '@/stores/toast'
import {
  developmentRequestMarkdown,
  gapFieldLabel,
  gapKindLabel,
  optionProduces,
  optionText,
  optionTypeLabel,
} from '@/utils/developmentRequest'
import { executionPath } from '@/utils/traceRun'

// request 는 development_request 칸이고, context 는 마크다운의 배경 절에 넣을 맥락
// (frontend/src/utils/developmentRequest.js 의 markdownContextOf 모양)이다.
const props = defineProps({
  request: { type: Object, required: true },
  context: { type: Object, default: () => ({}) },
})
const toast = useToastStore()

const options = computed(() => props.request.options ?? [])
const changePoints = computed(() => props.request.change_points ?? [])
const interim = computed(() => props.request.interim ?? null)
const evidence = computed(() => props.request.evidence_run_ids ?? [])
// 실행 번호를 실행 화면 주소로 바꾼다. 알아보지 못하는 모양이면 /executions/ 뒤에 그대로 붙인다.
const runLink = (id) => executionPath(id) ?? `/executions/${id}`

/**
 * 글을 클립보드에 넣는다. Clipboard API 가 없으면(보안 연결이 아닌 주소 등) 숨긴 textarea 를 골라
 * 복사 명령을 쓰고, 그것도 안 되면 오류를 던진다. copy 가 부른다.
 */
async function copyText(text) {
  if (navigator.clipboard?.writeText) return navigator.clipboard.writeText(text)
  const area = document.createElement('textarea')
  area.value = text
  area.setAttribute('readonly', '')
  area.style.position = 'fixed'
  area.style.opacity = '0'
  document.body.appendChild(area)
  area.select()
  const ok = document.execCommand('copy')
  area.remove()
  if (!ok) throw new Error('copy failed')
}

// '마크다운으로 복사' 버튼이 부른다. 결과는 토스트로 알린다.
async function copy() {
  try {
    await copyText(developmentRequestMarkdown(props.request, props.context))
    toast.show('개발 요청서를 마크다운으로 복사했어요. 이슈나 메신저에 붙여 넣어 주세요.')
  } catch {
    toast.show('복사하지 못했어요. 브라우저의 클립보드 권한을 확인해 주세요.', 'err')
  }
}
</script>

<template>
  <section id="dev-request" class="card dev">
    <div class="head">
      <h2 class="sec"><Wrench :size="18" /> 개발 요청서</h2>
      <button type="button" class="primary sm" @click="copy"><ClipboardCopy :size="14" /> 마크다운으로 복사</button>
    </div>
    <p class="title">{{ request.title }}</p>
    <p class="small muted">SKILL.md 수정만으로는 고칠 수 없어요. 아래 내용을 개발팀에 전달해 업무 도구를 만들거나 넓혀 주세요.</p>

    <h3>필요한 값</h3>
    <table class="gaps">
      <thead>
        <tr>
          <th>값</th>
          <th>필요로 하는 Skill</th>
          <th>칸</th>
          <th>종류</th>
        </tr>
      </thead>
      <tbody>
        <tr v-for="(g, i) in request.gaps" :key="i">
          <td><code>{{ g.key }}</code></td>
          <td>{{ g.needed_by }}</td>
          <td :title="g.field">{{ gapFieldLabel(g.field) }}</td>
          <td><span class="tag" :class="g.kind">{{ gapKindLabel(g.kind) }}</span></td>
        </tr>
      </tbody>
    </table>

    <template v-if="options.length">
      <h3>제안 방법 <span class="small muted">(하나를 골라 개발해 주세요)</span></h3>
      <ol class="options">
        <li v-for="(o, i) in options" :key="i">
          <span class="tag">{{ optionTypeLabel(o.type) }}</span>
          <span>{{ optionText(o) }}</span>
          <span v-if="o.type === 'new_tool' && optionProduces(o).length" class="small muted">
            내야 할 값: <code v-for="k in optionProduces(o)" :key="k">{{ k }}</code>
          </span>
        </li>
      </ol>
    </template>

    <template v-if="changePoints.length">
      <h3>바꿀 곳</h3>
      <ul class="paths">
        <li v-for="p in changePoints" :key="p"><code>{{ p }}</code></li>
      </ul>
    </template>

    <template v-if="interim">
      <h3>임시 대안</h3>
      <p v-if="interim.summary" class="para">{{ interim.summary }}</p>
      <pre v-if="interim.patch" class="patch">{{ interim.patch }}</pre>
      <p v-if="interim.proof_run_ids?.length" class="small runs">
        <span class="muted">증명 실행(이 변경을 되돌리면 통과)</span>
        <RouterLink v-for="id in interim.proof_run_ids" :key="id" :to="runLink(id)">{{ id }}</RouterLink>
      </p>
    </template>

    <template v-if="evidence.length">
      <h3>근거 실행</h3>
      <p class="small runs">
        <RouterLink v-for="id in evidence" :key="id" :to="runLink(id)">{{ id }}</RouterLink>
      </p>
    </template>
  </section>
</template>

<style scoped>
.dev {
  border-color: color-mix(in srgb, var(--warn) 35%, var(--border));
}

.head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  flex-wrap: wrap;
}

.sec {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
}

.title {
  margin: 10px 0 4px;
  font-size: var(--fs-lg);
  font-weight: 700;
}

h3 {
  margin: 18px 0 8px;
}

.para {
  margin: 0 0 8px;
  line-height: 1.6;
}

.gaps {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--fs-sm);
}

th,
td {
  padding: 6px 10px;
  border-bottom: 1px solid var(--border);
  text-align: left;
  overflow-wrap: anywhere;
}

th {
  color: var(--muted);
  font-weight: 600;
}

.tag {
  display: inline-block;
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--surface-2);
  font-size: var(--fs-xs);
  white-space: nowrap;
}

.tag.missing_value,
.tag.tool_output_missing {
  background: var(--warn-soft);
  color: var(--warn);
}

.options {
  margin: 0;
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 8px;
  font-size: var(--fs-sm);
  line-height: 1.6;
}

.options li > span {
  margin-right: 6px;
}

.options li > .small {
  display: block;
}

.paths {
  margin: 0;
  padding-left: 20px;
  font-size: var(--fs-sm);
}

.patch {
  margin: 0;
  padding: 10px 12px;
  border-radius: var(--radius-sm);
  background: var(--surface-2);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.runs {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 10px;
  margin: 8px 0 0;
}

code {
  overflow-wrap: anywhere;
}
</style>
