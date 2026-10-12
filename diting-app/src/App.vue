<script setup lang="ts">
import { onHide, onLaunch, onShow } from '@dcloudio/uni-app'

onLaunch(() => {
  // 应用启动
})

onShow(() => {
  // 应用进入前台
})

onHide(() => {
  // 应用进入后台
})
</script>

<!--
  全局主题：谛听 UI 方案 C「晨光 · 陪伴」
  定位：情绪安抚优先 / 上传转化最高 / 面向焦虑中的新用户
  策略：暖调、大留白、圆润、插画感，把「工具」变成「有人在场」。
  关键取舍：牺牲信息密度与权威感，换取信任与完成率。
  设计令牌与基础类直接对齐定稿 HTML（style-c-daylight），
  仅把 grid 改为 flex 以兼容小程序渲染层。
-->
<style>
page {
  /* 颜色 */
  --bg: #fbf6f0;
  --card: #ffffff;
  --ink: #2e2a26;
  --ink-2: #5c554d;
  --ink-3: #948b80;
  --line: #eee6dc;
  --warm: #d97642; /* 暖橙：行动与标记 */
  --warm-soft: #fdf1e9;
  --warm-line: #f3d9c6;
  --sage: #6e9279; /* 柔青：通过与安全 */
  --sage-soft: #eff5f0;
  --gold: #c8a45c;
  /* 圆角 / 阴影 */
  --r-lg: 22px;
  --r-md: 16px;
  --r-sm: 12px;
  --sh: 0 6px 22px -12px rgba(90, 70, 50, 0.16);
  --sh-lg: 0 18px 44px -18px rgba(90, 70, 50, 0.28);
  /* 字体：小程序无外链字体，回落到系统宋体族保留衬线气质 */
  --serif: 'Noto Serif SC', 'Songti SC', 'STSong', 'Source Han Serif SC', serif;
  --sans: 'Noto Sans SC', 'PingFang SC', 'Microsoft YaHei', sans-serif;

  background: var(--bg);
  color: var(--ink);
  font-family: var(--sans);
  font-size: 16px;
  line-height: 1.7;
}

/* ---------- 通用排版 ---------- */
.serif {
  font-family: var(--serif);
  font-weight: 600;
  letter-spacing: 0.5px;
}

.muted {
  color: var(--ink-3);
  font-size: 13px;
}

.note {
  display: block;
  margin: 16px 20px 20px;
  font-size: 11.5px;
  line-height: 1.85;
  color: var(--ink-3);
  text-align: center;
}

.note-line {
  display: block;
}

/* ---------- 按钮 ---------- */
.btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 9px;
  min-height: 54px;
  padding: 0 22px;
  border-radius: 16px;
  border: none;
  font-family: var(--sans);
  font-size: 16px;
  font-weight: 500;
  line-height: 1.2;
}

.btn::after {
  border: none;
}

.btn-main {
  color: #ffffff;
  background: linear-gradient(135deg, #e58a56, #c96434);
  box-shadow: 0 12px 28px -10px rgba(201, 100, 52, 0.55);
}

.btn-soft {
  color: #b05526;
  background: var(--warm-soft);
  border: 1px solid var(--warm-line);
}

.btn-block {
  width: 100%;
}

/* ---------- 问候区 ---------- */
.greet {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 24px 20px;
}

.greet-hi {
  display: block;
  font-size: 13px;
  color: var(--ink-3);
}

.greet-who {
  display: block;
  margin-top: 2px;
  font-family: var(--serif);
  font-size: 22px;
  font-weight: 600;
}

.avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  background: var(--warm-soft);
  border: 1px solid var(--warm-line);
}

/* ---------- Hero 卡 ---------- */
.hero {
  position: relative;
  overflow: hidden;
  margin: 0 20px 18px;
  padding: 24px 22px;
  background: var(--card);
  border-radius: var(--r-lg);
  box-shadow: var(--sh);
}

.hero::after {
  content: '';
  position: absolute;
  right: -40px;
  top: -40px;
  width: 180px;
  height: 180px;
  border-radius: 50%;
  background: radial-gradient(circle, var(--warm-soft), rgba(253, 241, 233, 0) 70%);
}

.hero-h1 {
  position: relative;
  z-index: 2;
  display: block;
  font-family: var(--serif);
  font-size: 23px;
  font-weight: 600;
  line-height: 1.6;
  letter-spacing: 1px;
}

.hero-p {
  position: relative;
  z-index: 2;
  display: block;
  margin-top: 10px;
  font-size: 13.5px;
  line-height: 1.85;
  color: var(--ink-2);
}

/* ---------- 提示条 ---------- */
.tip {
  display: flex;
  align-items: flex-start;
  gap: 11px;
  margin: 0 20px 18px;
  padding: 14px 16px;
  background: var(--sage-soft);
  border: 1px solid #dce8df;
  border-radius: var(--r-md);
}

.tip-ic {
  flex-shrink: 0;
  width: 22px;
  height: 22px;
  margin-top: 1px;
}

.tip-p {
  flex: 1;
  font-size: 12.5px;
  line-height: 1.75;
  color: #4c6b57;
}

/* ---------- 区块标题 ---------- */
.sec {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 6px 24px 12px;
}

.sec-h2 {
  font-family: var(--serif);
  font-size: 17px;
  font-weight: 600;
}

.sec-all {
  font-size: 12.5px;
  color: var(--ink-3);
}

/* ---------- 录音卡 ---------- */
.rec {
  display: flex;
  align-items: center;
  gap: 14px;
  margin: 0 20px 12px;
  padding: 16px 18px;
  background: var(--card);
  border-radius: var(--r-lg);
  box-shadow: var(--sh);
}

.rec-ic {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 48px;
  height: 48px;
  border-radius: 15px;
  background: var(--warm-soft);
  border: 1px solid var(--warm-line);
}

.rec-ic.pass {
  background: var(--sage-soft);
  border-color: #dce8df;
}

.rec-ic.run {
  background: #f4f1ea;
  border-color: var(--line);
}

.rec-mid {
  flex: 1;
  min-width: 0;
}

.rec-ti {
  display: block;
  font-size: 15px;
  font-weight: 500;
}

.rec-mt {
  display: block;
  margin-top: 3px;
  font-size: 12px;
  color: var(--ink-3);
}

.pill {
  display: inline-block;
  margin-top: 7px;
  padding: 4px 9px;
  font-size: 11.5px;
  border-radius: 9px;
  background: var(--warm-soft);
  color: #b05526;
  border: 1px solid var(--warm-line);
}

.pill-pass {
  background: var(--sage-soft);
  color: #4c6b57;
  border-color: #dce8df;
}

.pill-run {
  background: #f4f1ea;
  color: var(--ink-2);
  border-color: var(--line);
}

.dots3 {
  flex-shrink: 0;
  font-size: 15px;
  letter-spacing: 2px;
  color: var(--ink-3);
}

/* ---------- 上传区 ---------- */
.up {
  margin: 0 20px 16px;
  padding: 38px 24px 32px;
  text-align: center;
  background: var(--card);
  border: 1.5px dashed var(--warm-line);
  border-radius: 26px;
  box-shadow: var(--sh);
}

.orb {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 112px;
  height: 112px;
  margin: 0 auto 20px;
  border-radius: 50%;
  background: linear-gradient(160deg, #fff4ea, #fbe3d0);
  box-shadow: 0 10px 30px -12px rgba(201, 100, 52, 0.35);
}

.orb::before {
  content: '';
  position: absolute;
  top: -10px;
  right: -10px;
  bottom: -10px;
  left: -10px;
  border-radius: 50%;
  border: 1.5px solid var(--warm-line);
  animation: breathe 3.6s ease-in-out infinite;
}

@keyframes breathe {
  0%,
  100% {
    transform: scale(1);
    opacity: 0.85;
  }
  50% {
    transform: scale(1.06);
    opacity: 0.35;
  }
}

.up-h3 {
  display: block;
  margin-bottom: 8px;
  font-family: var(--serif);
  font-size: 19px;
  font-weight: 600;
}

.up-p {
  display: block;
  margin-bottom: 20px;
  font-size: 13px;
  line-height: 1.85;
  color: var(--ink-2);
}

/* ---------- 步骤 ---------- */
.steps {
  margin: 0 20px 18px;
  padding: 18px;
  background: var(--card);
  border-radius: var(--r-lg);
  box-shadow: var(--sh);
}

.step {
  display: flex;
  gap: 13px;
  padding: 11px 0;
}

.step-n {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 28px;
  height: 28px;
  border-radius: 10px;
  background: var(--warm-soft);
  border: 1px solid var(--warm-line);
  color: #b05526;
  font-size: 13px;
  font-weight: 500;
}

.step-t {
  display: block;
  font-size: 14px;
}

.step-d {
  display: block;
  margin-top: 2px;
  font-size: 12.5px;
  line-height: 1.75;
  color: var(--ink-3);
}

/* ---------- 判读中 ---------- */
.proc {
  margin: 0 20px 16px;
  padding: 20px 18px;
  background: var(--card);
  border-radius: var(--r-lg);
  box-shadow: var(--sh);
}

.proc-top {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 14px;
}

.proc-cap {
  display: block;
  font-size: 12.5px;
  color: var(--ink-3);
}

.proc-sub {
  display: block;
  margin-top: 2px;
  font-size: 14px;
}

.proc-pct {
  font-family: var(--serif);
  font-size: 26px;
  color: #b05526;
}

.track {
  height: 8px;
  margin-bottom: 16px;
  background: #f4eee7;
  border-radius: 4px;
  overflow: hidden;
}

.track-i {
  display: block;
  height: 100%;
  border-radius: 4px;
  background: linear-gradient(90deg, #f0b47f, #c96434);
  transition: width 0.45s cubic-bezier(0.3, 0.8, 0.3, 1);
}

.lines {
  font-size: 13.5px;
  line-height: 2;
  color: var(--ink-2);
}

.lines-ln {
  display: block;
  animation: lin 0.5s ease forwards;
}

@keyframes lin {
  from {
    opacity: 0;
    transform: translateY(5px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.lines-ts {
  display: inline-block;
  width: 44px;
  font-size: 11.5px;
  color: var(--ink-3);
}

.lines-hl {
  padding: 2px 5px;
  border-radius: 6px;
  background: #fdebde;
  color: #b05526;
}

/* ---------- 报告 ---------- */
.rep {
  margin: 0 20px 16px;
  padding: 22px 20px;
  background: var(--card);
  border-radius: var(--r-lg);
  box-shadow: var(--sh);
}

.rep-top {
  display: flex;
  align-items: center;
  gap: 14px;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--line);
}

.rep-badge {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 46px;
  height: 46px;
  border-radius: 15px;
  background: var(--warm-soft);
  border: 1px solid var(--warm-line);
}

.rep-num {
  display: block;
  font-family: var(--serif);
  font-size: 19px;
  font-weight: 600;
}

.rep-sub {
  display: block;
  font-size: 12px;
  color: var(--ink-3);
}

.rep-stats {
  display: flex;
  gap: 10px;
  margin-top: 14px;
}

.rs {
  flex: 1;
  padding: 12px 8px;
  text-align: center;
  background: #fbf6f0;
  border-radius: 14px;
}

.rs-b {
  display: block;
  font-family: var(--serif);
  font-size: 19px;
  font-weight: 600;
  color: #b05526;
}

.rs-s {
  display: block;
  font-size: 11px;
  color: var(--ink-3);
}

.lead {
  display: block;
  margin: 0 20px 14px;
  padding: 15px 17px;
  font-size: 13px;
  line-height: 1.9;
  color: var(--ink-2);
  background: #fffbf6;
  border: 1px solid var(--line);
  border-radius: var(--r-md);
}

/* ---------- 问题卡 ---------- */
.find {
  margin: 0 20px 12px;
  padding: 18px;
  background: var(--card);
  border-radius: var(--r-lg);
  border-left: 4px solid var(--warm);
  box-shadow: var(--sh);
}

.find.pass {
  border-left-color: var(--sage);
}

.f-ts {
  display: inline-block;
  margin-bottom: 10px;
  padding: 3px 9px;
  font-size: 11.5px;
  border-radius: 8px;
  background: var(--warm-soft);
  border: 1px solid var(--warm-line);
  color: #b05526;
}

.find.pass .f-ts {
  background: var(--sage-soft);
  border-color: #dce8df;
  color: #4c6b57;
}

.f-q {
  display: block;
  font-size: 14.5px;
  line-height: 1.9;
}

.f-hl {
  padding: 1px 4px;
  border-radius: 5px;
  background: #fdebde;
  color: #a8501f;
}

.f-note {
  display: block;
  margin-top: 10px;
  font-size: 13px;
  line-height: 1.8;
  color: var(--ink-2);
}

.f-src {
  display: block;
  margin-top: 10px;
  padding-top: 10px;
  font-size: 11.5px;
  line-height: 1.75;
  color: var(--ink-3);
  border-top: 1px dashed var(--line);
}

/* ---------- 安心页 ---------- */
.care {
  margin: 0 20px 14px;
  padding: 24px 22px;
  background: linear-gradient(160deg, #fff8f1, #fbeee2);
  border: 1px solid var(--warm-line);
  border-radius: var(--r-lg);
}

.care-p {
  display: block;
  font-family: var(--serif);
  font-size: 19px;
  font-weight: 600;
  line-height: 1.85;
  letter-spacing: 1px;
}

.care-fine {
  display: block;
  margin-top: 11px;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--ink-2);
}

.list {
  margin: 0 20px 14px;
  padding: 6px 18px;
  background: var(--card);
  border-radius: var(--r-lg);
  box-shadow: var(--sh);
}

.list-t {
  display: block;
  padding: 12px 0 4px;
  font-family: var(--serif);
  font-size: 15px;
  font-weight: 600;
}

.li {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 0;
  border-bottom: 1px dashed var(--line);
}

.li-last {
  border-bottom: none;
}

.li-k {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  width: 24px;
  height: 24px;
  border-radius: 9px;
  background: var(--sage-soft);
  color: #4c6b57;
  font-size: 12px;
}

.li-k.no {
  background: #fbefec;
  color: #b05526;
}

.li-t {
  flex: 1;
  font-size: 13.5px;
  line-height: 1.75;
  color: var(--ink-2);
}

.li-b {
  color: var(--ink);
  font-weight: 500;
}

/* ---------- 空态 ---------- */
.empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  padding-top: 100px;
}

.empty-text {
  font-size: 13.5px;
  color: var(--ink-3);
}

/* ---------- 适用条款（命中项下折叠的条文） ---------- */
.clause {
  display: block;
  margin-top: 8px;
}

.clause-t {
  display: block;
  font-size: 11.5px;
  letter-spacing: 0.5px;
  color: var(--ink-3);
}

.clause-row {
  margin-top: 6px;
  padding: 8px 10px;
  background: var(--warm-soft);
  border: 1px solid var(--warm-line);
  border-radius: var(--r-sm);
}

.clause-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.clause-no {
  flex: 1;
  font-size: 13px;
  font-weight: 500;
  color: #8a4a1f;
}

.clause-toggle {
  flex-shrink: 0;
  margin-left: 8px;
  font-size: 11.5px;
  color: var(--ink-3);
}

.clause-body {
  display: block;
  margin-top: 7px;
  padding-top: 7px;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--ink-2);
  border-top: 1px dashed var(--warm-line);
}
</style>
