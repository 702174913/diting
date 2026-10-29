/**
 * 只为「引导图截图」服务的临时桩：模拟听悟后端契约，让 H5 页面能渲染出真实排版。
 * 不参与构建、不入库（artifacts/ 已被忽略），截图做完即可删。
 *
 * 契约来源：diting-app-v2/src/api/{request,tingwu,record,upload}.ts
 * 信封统一 { code, msg, data }，code=200 成功；token 走 query/form，不走 Authorization。
 */
const http = require('node:http')

const PORT = Number(process.env.MOCK_PORT || 8899)

const b64url = value => Buffer.from(JSON.stringify(value)).toString('base64url')
// 客户端只解码 payload 取 user_id，不校验签名
const TOKEN = `${b64url({ alg: 'HS256', typ: 'JWT' })}.${b64url({ user_id: 1, payload: '1', exp: 4102444800 })}.sig`

const dialogues = [
  { speaker: '1', content: '喂，你好，是王先生吗？我这边是××金融的。' },
  { speaker: '2', content: '是我，什么事？' },
  { speaker: '1', content: '你名下这笔三万八的账单已经逾期四十二天了，今天必须处理掉。' },
  { speaker: '2', content: '我知道逾期了，这个月确实周转不开，能不能宽限几天？' },
  { speaker: '1', content: '我们这边可以先免掉一部分违约金，你尽快处理就行。' },
  { speaker: '2', content: '免掉多少？我这边最多先还两千。' },
  { speaker: '1', content: '两千不行。你今天不处理，我们就联系你单位和家里人了。' },
  { speaker: '2', content: '你们别乱打电话，我会想办法的。' },
  { speaker: '1', content: '还有，你孩子是不是在实验中学上学？放学我们过去一趟。' },
  { speaker: '2', content: '这跟我孩子没关系吧。' },
]

const hitRules = [
  {
    model_id: 101,
    serial_number: 'R-012',
    violation_type: '不当承诺',
    violation_name: '承诺减免或豁免',
    legalProvisions: [
      {
        id: 1,
        name: '催收行为规范',
        which: '第十二条',
        content: '催收人员不得作出超出授权范围的减免、豁免或者其他还款承诺。',
      },
    ],
  },
  {
    model_id: 102,
    serial_number: 'R-018',
    violation_type: '威胁施压',
    violation_name: '以联系第三人施压',
    legalProvisions: [
      {
        id: 2,
        name: '催收行为规范',
        which: '第十八条',
        content: '不得向债务人以外的第三人透露债务信息，不得以联系第三人的方式施压。',
      },
    ],
  },
  {
    model_id: 103,
    serial_number: 'R-021',
    violation_type: '涉未成年人',
    violation_name: '提及未成年子女信息',
    legalProvisions: [
      {
        id: 3,
        name: '个人信息保护法',
        which: '第二十八条',
        content: '不满十四周岁未成年人的个人信息属于敏感个人信息，处理应当取得单独同意。',
      },
    ],
  },
]

/** 命中项才有 model_ids/models/hit_texts；未命中句子字段为空数组 */
const matches = dialogues.map((dialogue, index) => {
  if (index === 4) {
    return { ...dialogue, model_ids: [101], models: [hitRules[0]], hit_texts: ['免掉一部分'] }
  }
  if (index === 6) {
    return { ...dialogue, model_ids: [102], models: [hitRules[1]], hit_texts: ['联系你单位和家里人'] }
  }
  if (index === 8) {
    return { ...dialogue, model_ids: [103], models: [hitRules[2]], hit_texts: ['你孩子是不是在实验中学上学'] }
  }
  return { ...dialogue, model_ids: [], models: [], hit_texts: [] }
})

const task = {
  audio_translation_id: 1046,
  user_id: 1,
  status: 7,
  task_id: '20261017-demo',
  task_status: 'COMPLETED',
  task_result: null,
  translation_result: null,
  handle_result: JSON.stringify(dialogues),
  match_result: JSON.stringify(matches),
  picture_handle_results: null,
  picture_match_results: null,
  picture_names: null,
  pictures: null,
  audio_name: '催收通话录音.mp3',
  audio: 'demo.mp3',
  audio_duration: 214,
  createtime: '2026-10-17 18:20:31',
}

const routes = {
  'POST /user/login': () => TOKEN,
  'GET /user/get': () => ({ user_id: 1, username: 'demo' }),
  'GET /configure/list': () => [
    { key: '每日免费份数', value: '3' },
    { key: '最长录音时长', value: '300' },
    { key: '风险阈值', value: '0' },
    { key: 'OSS_BASE_URL', value: 'http://127.0.0.1:8891/oss' },
    // 5 份协议：名称命中协议关键词即被识别，正文里带版本编号
    { key: '软件许可及服务协议', value: '<p>版本编号：V1.0</p><p>谛听用于催收通话录音的转写与质检。使用本服务即表示你确认对上传内容拥有合法处理权限。</p>' },
    { key: '隐私保护指引', value: '<p>版本编号：V1.0</p><p>我们只在完成本次转写与质检所需的范围内处理你的录音与文字内容，不用于训练模型。</p>' },
    { key: '个人信息收集与授权书', value: '<p>版本编号：V1.0</p><p>你授权我们在服务期间处理本次上传的音频、图片及其转写结果，用于生成质检记录。</p>' },
    { key: '通话录音与语音识别授权协议', value: '<p>版本编号：V1.0</p><p>你确认已取得通话各方的必要授权，方可上传该段录音。</p>' },
    { key: '第三方共享与SDK清单', value: '<p>版本编号：V1.0</p><p>本服务不向第三方共享你的录音与文字内容；小程序基础库由微信提供。</p>' },
  ],
  'GET /audio_translation/list': () => [task],
  'GET /audio_translation/get': () => task,
}

const { createReadStream, statSync } = require('node:fs')
const path = require('node:path')
const AUDIO_FILE = path.join(__dirname, '../../example/cuishou-sample-48s.mp3')

http.createServer((req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`)
  // 原件预览要能试听：给一个真实 mp3，带上 Range 支持（audio 元素会请求分段）
  if (req.method === 'GET' && url.pathname === '/oss/demo.mp3') {
    const size = statSync(AUDIO_FILE).size
    const range = req.headers.range
    res.setHeader('Access-Control-Allow-Origin', '*')
    res.setHeader('Accept-Ranges', 'bytes')
    res.setHeader('Content-Type', 'audio/mpeg')
    if (range) {
      const [startText, endText] = range.replace(/bytes=/, '').split('-')
      const start = Number(startText) || 0
      const end = endText ? Number(endText) : size - 1
      res.writeHead(206, { 'Content-Range': `bytes ${start}-${end}/${size}`, 'Content-Length': end - start + 1 })
      createReadStream(AUDIO_FILE, { start, end }).pipe(res)
      return
    }
    res.writeHead(200, { 'Content-Length': size })
    createReadStream(AUDIO_FILE).pipe(res)
    return
  }
  const key = `${req.method} ${url.pathname}`
  const handler = routes[key]
  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Access-Control-Allow-Headers', 'content-type')
  res.setHeader('Access-Control-Allow-Methods', 'GET,POST,OPTIONS')
  if (req.method === 'OPTIONS') {
    res.writeHead(204).end()
    return
  }
  if (!handler) {
    res.writeHead(404, { 'content-type': 'application/json' })
    res.end(JSON.stringify({ code: 404, msg: `未桩接口 ${key}`, data: null }))
    console.log(`[404] ${key}`)
    return
  }
  res.writeHead(200, { 'content-type': 'application/json' })
  res.end(JSON.stringify({ code: 200, msg: 'ok', data: handler() }, null, 2))
  console.log(`[200] ${key}`)
}).listen(PORT, '127.0.0.1', () => console.log(`mock tingwu on http://127.0.0.1:${PORT}`))
