/**
 * 提交前的 5 道问询。
 *
 * 现阶段题目写在前端：后台的配置接口与返回格式还没定，先把样式和交互跑通。
 * 接入后台时只需要把 SURVEY_QUESTIONS 换成接口下发（同结构即可），
 * 采集结果也已经收敛成 toAnswerList() 的稳定结构，换传输方式不影响页面。
 */
export interface SurveyOption {
  key: 'A' | 'B'
  label: string
}

export interface SurveyQuestion {
  id: number
  title: string
  options: SurveyOption[]
}

/** 一题的作答：题目 id + 选项 key */
export interface SurveyAnswer {
  questionId: number
  option: 'A' | 'B'
}

/** 页面内的选择状态：题目 id -> 选项 key */
export type SurveyPicks = Record<number, 'A' | 'B'>

export const SURVEY_QUESTIONS: SurveyQuestion[] = [
  {
    id: 1,
    title: '你接到该催收电话的时间段是哪个？',
    options: [
      { key: 'A', label: '8:00 - 22:00' },
      { key: 'B', label: '其他时间' },
    ],
  },
  {
    id: 2,
    title: '你一天接收到催收电话的次数是多少？',
    options: [
      { key: 'A', label: '小于 3 次' },
      { key: 'B', label: '其他' },
    ],
  },
  {
    id: 3,
    title: '你是被催收本人吗？',
    options: [
      { key: 'A', label: '是' },
      { key: 'B', label: '不是' },
    ],
  },
  {
    id: 4,
    title: '你逾期是否超过 3 个月？',
    options: [
      { key: 'A', label: '是' },
      { key: 'B', label: '不是' },
    ],
  },
  {
    id: 5,
    title: '打你电话时是否使用的是私人手机号？',
    options: [
      { key: 'A', label: '是' },
      { key: 'B', label: '不是' },
    ],
  },
]

/** 已作答数量：用于「还差 N 题」与提交按钮可用性 */
export function answeredCount(picks: SurveyPicks): number {
  return SURVEY_QUESTIONS.filter(question => picks[question.id] !== undefined).length
}

export function isComplete(picks: SurveyPicks): boolean {
  return answeredCount(picks) === SURVEY_QUESTIONS.length
}

/**
 * 转成提交用的数组：按题目顺序、只带已作答项。
 * 后端格式未定，这里先给一个不带业务语义的结构（questionId + 选项 key），
 * 接入时在 http 层做一次映射即可，页面不用改。
 */
export function toAnswerList(picks: SurveyPicks): SurveyAnswer[] {
  const answers: SurveyAnswer[] = []
  for (const question of SURVEY_QUESTIONS) {
    const option = picks[question.id]
    if (option) {
      answers.push({ questionId: question.id, option })
    }
  }
  return answers
}
