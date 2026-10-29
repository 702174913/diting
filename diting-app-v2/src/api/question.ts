import { request } from './request'
import type { AnswerRecord, QuestionRecord, SurveyQuestion } from '@/utils/survey'
import { buildSurveyQuestions } from '@/utils/survey'

let cached: Promise<SurveyQuestion[]> | null = null

export function fetchQuestionRecords(): Promise<QuestionRecord[]> {
  return request<QuestionRecord[]>({ url: '/question/list' })
    .then(items => Array.isArray(items) ? items : [])
}

export function fetchAnswerRecords(): Promise<AnswerRecord[]> {
  return request<AnswerRecord[]>({ url: '/question_answer/list' })
    .then(items => Array.isArray(items) ? items : [])
}

export function fetchSurveyQuestions(): Promise<SurveyQuestion[]> {
  if (!cached) {
    const pending = Promise.all([fetchQuestionRecords(), fetchAnswerRecords()])
      .then(([questions, answers]) => {
        const list = buildSurveyQuestions(questions, answers)
        if (list.length === 0) {
          if (cached === pending) {
            cached = null
          }
          throw new Error('题目加载失败')
        }
        return list
      })
      .catch((error) => {
        if (cached === pending) {
          cached = null
        }
        throw error
      })
    cached = pending
  }
  return cached
}

export function clearSurveyCache(): void {
  cached = null
}
