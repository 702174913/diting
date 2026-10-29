const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

const SRC = path.join(__dirname, '../src')

function transpile(source, filename) {
  return ts.transpileModule(source, { compilerOptions: {
    module: ts.ModuleKind.CommonJS,
    target: ts.ScriptTarget.ES2020,
  } }, { fileName: filename }).outputText
}

function loadUtil(rel, injected = {}) {
  const filename = path.join(SRC, rel)
  const exports = {}
  vm.runInNewContext(transpile(fs.readFileSync(filename, 'utf8'), filename), { exports, ...injected }, { filename })
  return exports
}

/** 只装载上传页：它的依赖全部是副作用-free 的模块，可以整体打桩后直接跑 setup */
function uploadHarness(options = {}) {
  const nav = []
  const uni = {
    showToast: options => (options.title ? null : null),
    switchTab: o => nav.push(o.url),
    navigateTo: o => nav.push(o.url),
    getStorageSync: () => '',
    setStorageSync() {},
    ...options.uni,
  }
  const survey = loadUtil('utils/survey.ts')
  const deps = {
    'vue': { ...vue, onUnmounted: () => {} },
    '@dcloudio/uni-app': { onHide: () => {}, onLoad: () => {}, onShow: () => {} },
    '@/components/StatusBar.vue': {},
    '@/components/UploadSelection.vue': {},
    '@/components/SurveySheet.vue': {},
    '@/api/upload': {},
    '@/api/request': { ApiError: class ApiError extends Error {} },
    '@/utils/audioDuration': { readAudioDurationMs: async () => 60_000 },
    '@/utils/errors': { messageOf: () => '出错了' },
    '@/utils/idempotency': { newIdempotencyKey: () => 'key' },
    '@/stores/user': { useUserStore: () => ({ token: '', userInfo: null, consentRequired: false, ensureLogin: async () => true }) },
    '@/utils/choose': {
      MAX_IMAGES: 6,
      isImage: file => /\.(png|jpg)$/i.test(file.name),
      chooseAudioFile: async () => null,
      chooseImages: async () => [],
      releaseChosenFile() {},
    },
    '@/utils/h5Auth': { requireH5Login: () => true },
    '@/utils/demo': { DEMO_MODE: false },
    '@/utils/consentGate': { hasFullAccess: () => true },
    '@/utils/survey': survey,
    ...options.deps,
  }
  const filename = path.join(SRC, 'pages/upload/index.vue')
  const source = fs.readFileSync(filename, 'utf8')
  const { descriptor, errors } = parse(source, { filename })
  assert.equal(errors.length, 0)
  const script = compileScript(descriptor, { id: 'upload' })
  const template = compileTemplate({
    source: descriptor.template.content,
    filename,
    id: 'upload',
    compilerOptions: { bindingMetadata: script.bindings },
  })
  assert.equal(template.errors.length, 0, '上传页模板编译失败')
  const exports = {}
  vm.runInNewContext(transpile(script.content, filename), {
    exports,
    uni,
    console,
    require: name => deps[name] ?? {},
  }, { filename })
  const state = exports.default.setup({}, { expose() {} })
  return { state, template: descriptor.template.content, source, nav, survey }
}

test('问卷就是后台给的 5 道题，每题 A/B 两个选项', () => {
  const survey = loadUtil('utils/survey.ts')
  assert.equal(survey.SURVEY_QUESTIONS.length, 5)
  assert.deepEqual(Array.from(survey.SURVEY_QUESTIONS, q => q.id), [1, 2, 3, 4, 5])
  for (const question of survey.SURVEY_QUESTIONS) {
    assert.equal(question.options.length, 2, question.title)
    assert.deepEqual(Array.from(question.options, o => o.key), ['A', 'B'], question.title)
    assert.ok(question.title.length > 0)
  }
  // 题干与选项要和产品给的原文一致（漏字会直接变成对外口径错误）
  assert.equal(survey.SURVEY_QUESTIONS[0].title, '你接到该催收电话的时间段是哪个？')
  assert.equal(survey.SURVEY_QUESTIONS[0].options[0].label, '8:00 - 22:00')
  assert.equal(survey.SURVEY_QUESTIONS[1].options[0].label, '小于 3 次')
  assert.equal(survey.SURVEY_QUESTIONS[2].title, '你是被催收本人吗？')
  assert.equal(survey.SURVEY_QUESTIONS[3].title, '你逾期是否超过 3 个月？')
  assert.equal(survey.SURVEY_QUESTIONS[4].title, '打你电话时是否使用的是私人手机号？')
})

test('答案按题目顺序收敛成数组，未作答的不伪造', () => {
  const survey = loadUtil('utils/survey.ts')
  assert.equal(survey.answeredCount({}), 0)
  assert.equal(survey.isComplete({}), false)
  assert.deepEqual(Array.from(survey.toAnswerList({})), [])

  const partial = { 3: 'A', 1: 'B' }
  assert.equal(survey.answeredCount(partial), 2)
  assert.equal(survey.isComplete(partial), false)
  // 顺序以题目为准，不跟随用户点击顺序
  assert.deepEqual(
    Array.from(survey.toAnswerList(partial), a => `${a.questionId}${a.option}`),
    ['1B', '3A'],
  )

  const all = { 1: 'A', 2: 'B', 3: 'A', 4: 'B', 5: 'A' }
  assert.equal(survey.isComplete(all), true)
  assert.deepEqual(Array.from(survey.toAnswerList(all), a => a.option), ['A', 'B', 'A', 'B', 'A'])
})

test('问卷弹层：5 题全在，未答完不能提交，取消/提交各自冒泡', () => {
  const filename = path.join(SRC, 'components/SurveySheet.vue')
  const source = fs.readFileSync(filename, 'utf8')
  const { descriptor, errors } = parse(source, { filename })
  assert.equal(errors.length, 0)
  const script = compileScript(descriptor, { id: 'survey' })
  const template = compileTemplate({
    source: descriptor.template.content,
    filename,
    id: 'survey',
    compilerOptions: { bindingMetadata: script.bindings },
  })
  assert.equal(template.errors.length, 0)

  const events = []
  const survey = loadUtil('utils/survey.ts')
  const exports = {}
  vm.runInNewContext(transpile(script.content, filename), {
    exports,
    require: name => (name === 'vue' ? vue : name === '@/utils/survey' ? survey : {}),
  }, { filename })
  const component = exports.default
  const props = { visible: true, answers: {} }
  const state = component.setup(props, { expose() {}, emit: (name, ...args) => events.push([name, ...args]) })
  assert.equal(state.complete.value, false)
  assert.equal(state.missing.value, 5)
  state.choose(2, 'B')
  assert.deepEqual(events[0], ['pick', 2, 'B'])

  // 选项高亮与提交可用性都由 answers 驱动，不能在组件里另存一份
  const filled = component.setup({ visible: true, answers: { 1: 'A', 2: 'B', 3: 'A', 4: 'B', 5: 'A' } }, { expose() {}, emit() {} })
  assert.equal(filled.complete.value, true)
  assert.equal(filled.missing.value, 0)
  assert.match(template.code, /answers\[question\.id\] === option\.key/)
})

test('上传页：先问卷、再同意，取消同意不丢答案', () => {
  const h = uploadHarness()
  assert.equal(h.state.surveyVisible.value, false)

  // 没选文件不该弹问卷（避免空手进问卷再被退回）
  h.state.openSurvey()
  assert.equal(h.state.surveyVisible.value, false)

  h.state.pendingFiles.value = [{ name: 'a.mp3', path: 'p1', size: 1024 }]
  h.state.openSurvey()
  assert.equal(h.state.surveyVisible.value, true)

  h.state.pickAnswer(1, 'A')
  h.state.pickAnswer(4, 'B')
  assert.deepEqual({ ...h.state.surveyAnswers.value }, { 1: 'A', 4: 'B' })

  h.state.submitSurvey()
  assert.equal(h.state.surveyVisible.value, false)
  assert.equal(h.state.sheetVisible.value, true)

  // 同意弹层点「再想想」：只关同意层，问卷答案留着
  h.state.sheetVisible.value = false
  assert.deepEqual({ ...h.state.surveyAnswers.value }, { 1: 'A', 4: 'B' })

  assert.match(h.template, /@show-consent="openSurvey"/)
  assert.match(h.template, /<SurveySheet/)
  assert.match(h.template, /:answers="surveyAnswers"/)
})

test('演示模式：绕过登录/额度，但仍不真的提交', () => {
  const h = uploadHarness()
  // 非演示模式下源码里必须有 DEMO_MODE 开关，且提交前短路
  assert.match(h.source, /DEMO_MODE/)
  assert.match(h.source, /\[demo\] 问卷答案/)
  const demoBlock = h.source.indexOf('if (DEMO_MODE)')
  const loginGate = h.source.indexOf('if (!requireH5Login(userStore.token, \'upload\'))', demoBlock)
  assert.ok(demoBlock > 0 && loginGate > demoBlock, '演示分支必须在登录门禁之前短路')
  // 开关本身必须同时受 DEV 与环境变量约束：生产包里不可能被打开
  const demo = fs.readFileSync(path.join(SRC, 'utils/demo.ts'), 'utf8')
  assert.match(demo, /import\.meta\.env\.DEV/)
  assert.match(demo, /VITE_DEMO_MODE === 'true'/)
  assert.match(h.source, /precheckQuota/)
})
