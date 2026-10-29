const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const ts = require('typescript')
const vue = require('vue')
const { parse, compileScript, compileTemplate } = require('@vue/compiler-sfc')

const SRC = path.join(__dirname, '../src')

const QUESTIONS = [
  { id: 1, title: '第一题', options: [{ key: 'A', label: '是', answerId: 1 }, { key: 'B', label: '不是', answerId: 2 }] },
  { id: 2, title: '第二题', options: [{ key: 'A', label: '是', answerId: 3 }, { key: 'B', label: '不是', answerId: 4 }] },
  { id: 3, title: '第三题', options: [{ key: 'A', label: '是', answerId: 5 }, { key: 'B', label: '不是', answerId: 6 }] },
  { id: 4, title: '第四题', options: [{ key: 'A', label: '是', answerId: 7 }, { key: 'B', label: '不是', answerId: 8 }] },
  { id: 5, title: '第五题', options: [{ key: 'A', label: '是', answerId: 9 }, { key: 'B', label: '不是', answerId: 10 }] },
]

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
    '@dcloudio/uni-app': { onHide: () => {}, onLoad: () => {}, onShow: () => {}, onShareAppMessage: () => {} },
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
    '@/utils/share': { useShareContent: () => () => ({}) },
    '@/utils/survey': survey,
    '@/api/question': { fetchSurveyQuestions: async () => QUESTIONS },
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

test('题目接口没有数据时不生成任何题目', () => {
  const survey = loadUtil('utils/survey.ts')
  assert.deepEqual(Array.from(survey.buildSurveyQuestions([], [])), [])
  assert.equal(survey.isComplete({}, []), false)
  assert.deepEqual(Array.from(survey.toAnswerList({}, [])), [])
  assert.deepEqual(Array.from(survey.toQuestionParams({}, [])), [])
})

test('题目接口按 question_id 合并选项，禁用项不展示', () => {
  const survey = loadUtil('utils/survey.ts')
  const questions = [
    { question_id: 1, title: '问题', content: '接到该催收电话的时间段是?', enable: 1 },
    { question_id: 2, title: '问题', content: '禁用题', enable: 0 },
    { question_id: 3, title: '问题', content: '您是债务人本人吗?', enable: 1 },
    { question_id: 4, title: '问题', content: '没有选项的题', enable: 1 },
  ]
  const answers = [
    { question_answer_id: 1, question_id: 1, title: 'A', content: '8:00-22:00', enable: 1 },
    { question_answer_id: 2, question_id: 1, title: 'B', content: '其他时间', enable: 1 },
    { question_answer_id: 3, question_id: 2, title: 'A', content: '禁用选项', enable: 1 },
    { question_answer_id: 4, question_id: 3, title: 'A', content: '是', enable: 1 },
    { question_answer_id: 5, question_id: 3, title: 'B', content: '不是', enable: 0 },
    { question_answer_id: 6, question_id: 3, title: 'C', content: '不确定', enable: 1 },
  ]
  const list = survey.buildSurveyQuestions(questions, answers)
  assert.deepEqual(Array.from(list, q => q.id), [1, 3])
  assert.equal(list[0].title, '接到该催收电话的时间段是?')
  assert.deepEqual(Array.from(list[0].options, o => `${o.key}${o.label}`), ['A8:00-22:00', 'B其他时间'])
  assert.deepEqual(Array.from(list[1].options, o => `${o.key}${o.label}${o.answerId}`), ['A是4', 'C不确定6'])
  assert.deepEqual(Array.from(survey.buildSurveyQuestions([], [])), [])
})

test('作答转成上传参数：只带 question_id 与 question_answer_id', () => {
  const survey = loadUtil('utils/survey.ts')
  const questions = survey.buildSurveyQuestions([
    { question_id: 1, content: '第一题', enable: 1 },
    { question_id: 2, content: '第二题', enable: 1 },
  ], [
    { question_answer_id: 7, question_id: 1, title: 'A', content: '是', enable: 1 },
    { question_answer_id: 8, question_id: 1, title: 'B', content: '不是', enable: 1 },
    { question_answer_id: 9, question_id: 2, title: 'A', content: '是', enable: 1 },
  ])
  assert.equal(
    JSON.stringify(survey.toQuestionParams({ 1: 'B', 2: 'A' }, questions)),
    '[{"question_id":1,"question_answer_id":8},{"question_id":2,"question_answer_id":9}]',
  )
  assert.equal(JSON.stringify(survey.toQuestionParams({}, questions)), '[]')
  assert.equal(JSON.stringify(survey.toQuestionParams({ 1: 'A' }, questions)), '[{"question_id":1,"question_answer_id":7}]')
})

test('答案按题目顺序收敛成数组，未作答的不伪造', () => {
  const survey = loadUtil('utils/survey.ts')
  assert.equal(survey.answeredCount({}, QUESTIONS), 0)
  assert.equal(survey.isComplete({}, QUESTIONS), false)
  assert.deepEqual(Array.from(survey.toAnswerList({}, QUESTIONS)), [])

  const partial = { 3: 'A', 1: 'B' }
  assert.equal(survey.answeredCount(partial, QUESTIONS), 2)
  assert.equal(survey.isComplete(partial, QUESTIONS), false)
  // 顺序以题目为准，不跟随用户点击顺序
  assert.deepEqual(
    Array.from(survey.toAnswerList(partial, QUESTIONS), a => `${a.questionId}${a.option}`),
    ['1B', '3A'],
  )

  const all = { 1: 'A', 2: 'B', 3: 'A', 4: 'B', 5: 'A' }
  assert.equal(survey.isComplete(all, QUESTIONS), true)
  assert.deepEqual(Array.from(survey.toAnswerList(all, QUESTIONS), a => a.option), ['A', 'B', 'A', 'B', 'A'])
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
  const props = { visible: true, answers: {}, questions: QUESTIONS }
  const state = component.setup(props, { expose() {}, emit: (name, ...args) => events.push([name, ...args]) })
  assert.equal(state.complete.value, false)
  assert.equal(state.missing.value, 5)
  state.choose(2, 'B')
  assert.deepEqual(events[0], ['pick', 2, 'B'])

  // 选项高亮与提交可用性都由 answers 驱动，不能在组件里另存一份
  const filled = component.setup({ visible: true, answers: { 1: 'A', 2: 'B', 3: 'A', 4: 'B', 5: 'A' }, questions: QUESTIONS }, { expose() {}, emit() {} })
  assert.equal(filled.complete.value, true)
  assert.equal(filled.missing.value, 0)
  assert.match(template.code, /answers\[question\.id\] === option\.key/)
})

test('上传页：先问卷、再同意，取消同意不丢答案', async () => {
  const h = uploadHarness()
  assert.equal(h.state.surveyVisible.value, false)

  // 没选文件不该弹问卷（避免空手进问卷再被退回）
  await h.state.openSurvey()
  assert.equal(h.state.surveyVisible.value, false)

  h.state.pendingFiles.value = [{ name: 'a.mp3', path: 'p1', size: 1024 }]
  await h.state.openSurvey()
  assert.equal(h.state.surveyVisible.value, true)
  assert.equal(h.state.surveyQuestions.value.length, 5)

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
  assert.match(h.template, /:questions="surveyQuestions"/)
})

test('题目接口失败时不打开问卷，无法进行下一步', async () => {
  const h = uploadHarness({
    deps: {
      '@/api/question': { fetchSurveyQuestions: async () => { throw new Error('network') } },
    },
  })
  h.state.pendingFiles.value = [{ name: 'a.mp3', path: 'p1', size: 1024 }]
  await h.state.openSurvey()
  assert.equal(h.state.surveyVisible.value, false)
  assert.equal(h.state.sheetVisible.value, false)
  assert.equal(h.state.surveyQuestions.value.length, 0)
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
