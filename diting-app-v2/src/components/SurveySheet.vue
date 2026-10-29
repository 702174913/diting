<script setup lang="ts">
/**
 * 提交前的问询弹层。
 *
 * 题目由后台下发，通常一屏能看全，便于用户知道「就问这些」；
 * 安卓小屏也不怕，弹层内部自己滚。
 *
 * 只负责采集：答案通过 pick 事件回给页面，提交由页面决定（当前不发请求）。
 */
import { computed } from 'vue'
import type { SurveyPicks, SurveyQuestion } from '@/utils/survey'
import { answeredCount, isComplete } from '@/utils/survey'

const props = defineProps<{
  visible: boolean
  answers: SurveyPicks
  questions: SurveyQuestion[]
}>()

const emit = defineEmits<{
  pick: [questionId: number, option: string]
  submit: []
  cancel: []
}>()

const questions = computed(() => props.questions)
const answered = computed(() => answeredCount(props.answers, questions.value))
const complete = computed(() => isComplete(props.answers, questions.value))
const missing = computed(() => questions.value.length - answered.value)

function choose(questionId: number, option: string) {
  emit('pick', questionId, option)
}
</script>

<template>
  <view v-if="visible" class="mask" @click="emit('cancel')">
    <view class="sheet" @click.stop>
      <view class="head">
        <view class="head-l">
          <text class="sheet-title">
            提交前，问你 {{ questions.length }} 个问题
          </text>
          <text class="sheet-sub">只用于本次判读口径，不会对外提供。</text>
        </view>
        <text class="close" role="button" tabindex="0" @click="emit('cancel')">✕</text>
      </view>

      <view class="qs">
        <view v-for="(question, index) in questions" :key="question.id" class="q">
          <view class="q-head">
            <text class="q-no">{{ index + 1 }}</text>
            <text class="q-title">{{ question.title }}</text>
          </view>
          <view class="opts">
            <view
              v-for="option in question.options"
              :key="option.key"
              class="opt"
              :class="{ 'opt-on': answers[question.id] === option.key }"
              role="button"
              tabindex="0"
              @click="choose(question.id, option.key)"
            >
              <text class="opt-key">{{ option.key }}</text>
              <text class="opt-label">{{ option.label }}</text>
            </view>
          </view>
        </view>
      </view>

      <view class="foot">
        <text class="foot-note">
          {{ complete ? `${questions.length} 题都选好了` : `还有 ${missing} 题没选` }}
        </text>
        <button class="btn btn-main btn-block" :disabled="!complete" @click="emit('submit')">
          下一步：确认上传
        </button>
        <text class="foot-cancel" role="button" tabindex="0" @click="emit('cancel')">先不上传</text>
      </view>
    </view>
  </view>
</template>

<style scoped>
.mask {
  position: fixed;
  top: 0;
  right: 0;
  bottom: 0;
  left: 0;
  z-index: 100;
  display: flex;
  align-items: flex-end;
  background: rgba(46, 42, 38, 0.45);
}

.sheet {
  box-sizing: border-box;
  display: flex;
  flex-direction: column;
  width: 100%;
  max-height: 92vh;
  padding: 20px 20px 24px;
  /* H5 下 tabBar 会盖住弹层底部，用 uni-h5 注入的 --window-bottom 让开（小程序端为 0px） */
  padding-bottom: calc(24px + var(--window-bottom, 0px));
  background: var(--card);
  border-radius: var(--r-lg) var(--r-lg) 0 0;
}

.head {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding-bottom: 11px;
  border-bottom: 1px solid var(--line);
}

.head-l {
  flex: 1;
  min-width: 0;
}

.sheet-title {
  display: block;
  font-family: var(--serif);
  font-size: 17px;
  font-weight: 600;
}

.sheet-sub {
  display: block;
  margin-top: 3px;
  font-size: 11.5px;
  color: var(--ink-3);
}

.close {
  flex: none;
  padding: 2px 2px 6px;
  font-size: 15px;
  color: var(--ink-3);
}

/* 题目区自己滚：5 题在矮屏上放不下，页脚必须始终可见 */
.qs {
  flex: 1;
  min-height: 0;
  padding: 2px 0 6px;
  overflow-y: auto;
}

.q {
  padding: 11px 0;
  border-bottom: 1px dashed var(--line);
}

.q:last-child {
  border-bottom: none;
}

.q-head {
  display: flex;
  align-items: flex-start;
  gap: 9px;
}

.q-no {
  flex: none;
  width: 20px;
  height: 20px;
  margin-top: 1px;
  font-size: 11.5px;
  line-height: 20px;
  text-align: center;
  color: #b05526;
  background: var(--warm-soft);
  border: 1px solid var(--warm-line);
  border-radius: 7px;
}

.q-title {
  flex: 1;
  font-size: 14px;
  line-height: 1.65;
  color: var(--ink);
}

.opts {
  display: flex;
  gap: 9px;
  margin: 8px 0 0 29px;
}

.opt {
  display: flex;
  flex: 1;
  align-items: center;
  gap: 7px;
  box-sizing: border-box;
  min-height: 40px;
  padding: 6px 10px;
  background: #fdfbf8;
  border: 1px solid var(--line);
  border-radius: 12px;
  transition: border-color 0.15s, background 0.15s;
}

.opt-on {
  background: var(--warm-soft);
  border-color: var(--warm);
}

.opt-key {
  flex: none;
  width: 20px;
  height: 20px;
  font-size: 11.5px;
  line-height: 20px;
  text-align: center;
  color: var(--ink-3);
  background: #ffffff;
  border: 1px solid var(--line);
  border-radius: 50%;
}

.opt-on .opt-key {
  color: #ffffff;
  background: var(--warm);
  border-color: var(--warm);
}

.opt-label {
  font-size: 13px;
  line-height: 1.4;
  color: var(--ink-2);
}

.opt-on .opt-label {
  color: #b05526;
}

.foot {
  padding-top: 12px;
  border-top: 1px solid var(--line);
}

/* 未答完时按钮要「明显还不能点」，但文案仍然看得清（默认禁用态会把字压成灰底灰字） */
.btn-main[disabled] {
  color: #fffaf6;
  background: linear-gradient(135deg, #e8c6ac, #d9b6a0);
  box-shadow: none;
}

.foot-note {
  display: block;
  margin-bottom: 10px;
  font-size: 11.5px;
  text-align: center;
  color: var(--ink-3);
}

.foot-cancel {
  display: block;
  margin-top: 12px;
  font-size: 12.5px;
  text-align: center;
  color: var(--ink-3);
}
</style>
