export interface SurveyOption {
  key: string
  label: string
  answerId?: number
}

export interface SurveyQuestion {
  id: number
  title: string
  options: SurveyOption[]
}

/** 一题的作答：题目 id + 选项 key */
export interface SurveyAnswer {
  questionId: number
  option: string
  answerId?: number
}

/** 页面内的选择状态：题目 id -> 选项 key */
export type SurveyPicks = Record<number, string>

/** 提交给上传接口的一题作答 */
export interface QuestionParam {
  question_id: number
  question_answer_id: number
}

/** `GET /question/list` 的单条记录 */
export interface QuestionRecord {
  question_id?: number | string
  title?: string
  content?: string
  enable?: number | string
}

/** `GET /question_answer/list` 的单条记录 */
export interface AnswerRecord {
  question_answer_id?: number | string
  question_id?: number | string
  title?: string
  content?: string
  enable?: number | string
}

function isEnabled(value: unknown): boolean {
  return value === undefined || value === null || value === '' || Number(value) === 1
}

function toId(value: unknown): number | null {
  const id = Number(value)
  return Number.isSafeInteger(id) && id > 0 ? id : null
}

const OPTION_KEYS = 'ABCDEFGHIJKLMNOPQRSTUVWXYZ'

/** 把题目标题与选项清单按 question_id 合成弹层用的题目结构；禁用或无效的记录直接跳过 */
export function buildSurveyQuestions(questions: QuestionRecord[], answers: AnswerRecord[]): SurveyQuestion[] {
  const optionsByQuestion = new Map<number, SurveyOption[]>()

  for (const answer of answers ?? []) {
    const questionId = toId(answer?.question_id)
    const label = typeof answer?.content === 'string' ? answer.content.trim() : ''
    if (questionId === null || !isEnabled(answer?.enable) || !label) {
      continue
    }
    const options = optionsByQuestion.get(questionId) ?? []
    const key = typeof answer.title === 'string' && answer.title.trim()
      ? answer.title.trim()
      : OPTION_KEYS[options.length] ?? String(options.length + 1)
    if (options.some(option => option.key === key)) {
      continue
    }
    const answerId = toId(answer.question_answer_id)
    options.push(answerId === null ? { key, label } : { key, label, answerId })
    optionsByQuestion.set(questionId, options)
  }

  const result: SurveyQuestion[] = []
  const seen = new Set<number>()

  for (const question of questions ?? []) {
    const id = toId(question?.question_id)
    if (id === null || seen.has(id) || !isEnabled(question?.enable)) {
      continue
    }
    const title = (typeof question.content === 'string' ? question.content : '').trim()
      || (typeof question.title === 'string' ? question.title.trim() : '')
    const options = optionsByQuestion.get(id)
    if (!title || !options || options.length === 0) {
      continue
    }
    seen.add(id)
    result.push({ id, title, options })
  }

  return result
}

/** 已作答数量：用于「还差 N 题」与提交按钮可用性 */
export function answeredCount(picks: SurveyPicks, questions: SurveyQuestion[]): number {
  return questions.filter(question => picks[question.id] !== undefined).length
}

export function isComplete(picks: SurveyPicks, questions: SurveyQuestion[]): boolean {
  return questions.length > 0 && answeredCount(picks, questions) === questions.length
}

/** 转成提交用的数组：按题目顺序、只带已作答项 */
export function toAnswerList(picks: SurveyPicks, questions: SurveyQuestion[]): SurveyAnswer[] {
  const answers: SurveyAnswer[] = []
  for (const question of questions) {
    const option = picks[question.id]
    if (!option) {
      continue
    }
    const hit = question.options.find(item => item.key === option)
    answers.push(hit?.answerId === undefined
      ? { questionId: question.id, option }
      : { questionId: question.id, option, answerId: hit.answerId })
  }
  return answers
}

/** 转成上传接口需要的 questions 参数：只带能从题目接口取到 answer_id 的已作答项 */
export function toQuestionParams(
  picks: SurveyPicks,
  questions: SurveyQuestion[],
): QuestionParam[] {
  const params: QuestionParam[] = []
  for (const question of questions) {
    const option = picks[question.id]
    if (!option) {
      continue
    }
    const hit = question.options.find(item => item.key === option)
    if (hit?.answerId === undefined) {
      continue
    }
    params.push({ question_id: question.id, question_answer_id: hit.answerId })
  }
  return params
}
