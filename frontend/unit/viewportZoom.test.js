// frontend/src/utils/viewportZoom.js 의 화면 배율 계산과 적용을 보호한다. 기준 폭 이하는 그대로 두고,
// 넓은 창만 비례 확대하며 상한에서 멈추는지, 창 크기가 바뀌면 --app-zoom 을 다시 쓰는지 확인한다.
import { test } from 'node:test'
import assert from 'node:assert/strict'
import { installViewportZoom, zoomFor } from '../src/utils/viewportZoom.js'

const options = { baseWidth: 1440, maxZoom: 1.5 }

test('기준 폭 이하의 창은 배율 1 이다', () => {
  assert.equal(zoomFor(800, options), 1)
  assert.equal(zoomFor(1280, options), 1)
  assert.equal(zoomFor(1440, options), 1)
})

test('넓은 창은 창 폭 / 기준 폭 만큼 키우고 소수 셋째 자리에서 자른다', () => {
  assert.equal(zoomFor(1920, options), 1.333)
  assert.equal(zoomFor(1728, options), 1.2)
})

test('배율은 maxZoom 을 넘지 않는다', () => {
  assert.equal(zoomFor(2560, options), 1.5)
  assert.equal(zoomFor(3840, options), 1.5)
})

test('폭을 읽지 못하면 배율 1 이다', () => {
  assert.equal(zoomFor(undefined, options), 1)
  assert.equal(zoomFor(Number.NaN, options), 1)
})

// window 와 html 요소의 필요한 부분만 흉내 낸다.
function fakeWindow(width) {
  const listeners = new Set()
  return {
    innerWidth: width,
    addEventListener: (type, fn) => type === 'resize' && listeners.add(fn),
    removeEventListener: (type, fn) => type === 'resize' && listeners.delete(fn),
    resize(next) {
      this.innerWidth = next
      for (const fn of listeners) fn()
    },
    listenerCount: () => listeners.size,
  }
}

function fakeRoot() {
  const writes = []
  return { writes, style: { setProperty: (name, value) => writes.push([name, value]) } }
}

test('설치하면 바로 배율을 쓰고, 창 크기가 바뀌면 달라진 배율만 다시 쓴다', () => {
  const win = fakeWindow(1920)
  const root = fakeRoot()
  const uninstall = installViewportZoom(win, root, options)
  assert.deepEqual(root.writes, [['--app-zoom', '1.333']])

  win.resize(1300)
  win.resize(1200)
  assert.deepEqual(root.writes.at(-1), ['--app-zoom', '1'])
  assert.equal(root.writes.length, 2)

  uninstall()
  assert.equal(win.listenerCount(), 0)
})
