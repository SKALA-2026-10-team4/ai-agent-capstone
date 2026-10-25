<script setup>
// 낮은 대비의 가는 궤도 선 장식이다. 동심원 몇 겹과 구(球) 격자(경선·위선)를 겹쳐 그리고, 바깥 궤도에
// 작은 크림색 점 하나를 띄운다. 그림은 장식일 뿐이라 aria-hidden 이고 클릭을 받지 않는다.
// frontend/src/components/PageHeader.vue 가 제목 오른쪽 뒤에, frontend/src/views/LoginView.vue 가
// 첫 화면 큰 원 뒤에 둔다. 천천히 돌고, 움직임 줄이기 설정이면 멈춘다.
// variant 가 'sphere' 면 경선과 위선까지 그리고, 'rings' 면 동심원만 그린다.
defineProps({
  variant: { type: String, default: 'sphere' },
})

// 경선(세로 타원)의 가로 반지름 비율이다. 1 이면 바깥 원과 같다.
const MERIDIANS = [0.18, 0.42, 0.66, 0.88]
// 위선(가로선)의 높이 비율이다. 0 이 적도다.
const PARALLELS = [-0.62, -0.32, 0, 0.32, 0.62]
const R = 200
</script>

<template>
  <svg class="orbit-art" viewBox="-240 -240 480 480" aria-hidden="true" focusable="false">
    <g class="rings">
      <circle r="236" />
      <circle r="120" />
      <circle r="62" />
    </g>
    <g v-if="variant === 'sphere'" class="sphere">
      <circle :r="R" />
      <ellipse v-for="m in MERIDIANS" :key="`m${m}`" :rx="R * m" :ry="R" />
      <ellipse
        v-for="p in PARALLELS"
        :key="`p${p}`"
        :cy="R * p"
        :rx="R * Math.sqrt(1 - p * p)"
        :ry="R * Math.sqrt(1 - p * p) * 0.16"
      />
    </g>
    <g class="satellite">
      <circle cx="0" cy="-236" r="3.5" />
    </g>
  </svg>
</template>

<style scoped>
.orbit-art {
  display: block;
  overflow: visible;
  fill: none;
  stroke: var(--orbit-line);
  stroke-width: 1;
  vector-effect: non-scaling-stroke;
  pointer-events: none;
}

.rings circle:first-child {
  stroke-dasharray: 2 6;
}

.sphere,
.satellite {
  transform-origin: 0 0;
  transform-box: view-box;
}

.sphere {
  animation: orbit-spin 180s linear infinite;
}

.satellite {
  animation: orbit-spin 60s linear infinite;
}

.satellite circle {
  fill: var(--accent);
  stroke: none;
  opacity: 0.55;
}

@keyframes orbit-spin {
  to {
    transform: rotate(360deg);
  }
}

@media (prefers-reduced-motion: reduce) {
  .sphere,
  .satellite {
    animation: none;
  }
}
</style>
