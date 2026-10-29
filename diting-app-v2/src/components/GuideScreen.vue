<script setup lang="ts">
/**
 * 引导页里的「页面长这样」——真机页面截图 + 矢量圈注。
 *
 * 为什么不是把圈和箭头烧进图片：
 * - 文案改一个字就得重新出图，且高分屏上文字会发虚；
 * - 圈注用 CSS 画，坐标按百分比存，截图换新图后只需重新量一次坐标。
 *
 * 截图按 390×844 视口截取（与 CSS 百分比一一对应），显示宽度 190px，
 * 素材为 420px 宽的 JPEG（2x），够清晰又不撑包体。
 */
import { computed } from 'vue'
import stepUpload from '@/static/guide/step-upload.jpg'
import stepRead from '@/static/guide/step-read.jpg'
import stepExport from '@/static/guide/step-export.jpg'

const props = defineProps<{ kind: 'upload' | 'read' | 'export' }>()

interface Ring {
  /** 以下均为相对截图宽高的百分比（截图原始视口 390×844） */
  x: number
  y: number
  w: number
  h: number
}

interface Shot {
  src: string
  no: string
  ring: Ring
  legend: string
}

const shots: Record<'upload' | 'read' | 'export', Shot> = {
  upload: {
    src: stepUpload,
    no: '1',
    ring: { x: 11.67, y: 43.05, w: 36.79, h: 6.4 },
    legend: '圈起来的就是入口：先点红色按钮选录音',
  },
  read: {
    src: stepRead,
    no: '2',
    ring: { x: 5.13, y: 6.68, w: 89.74, h: 29.81 },
    legend: '有问题的句子会标红，下面写着命中规则和依据',
  },
  export: {
    src: stepExport,
    no: '3',
    ring: { x: 5.13, y: 69.92, w: 89.74, h: 6.4 },
    legend: '要拿走就导出 PDF，要抹掉就删除这段',
  },
}

const shot = computed(() => shots[props.kind])

const ringStyle = computed(() => ({
  left: `${shot.value.ring.x}%`,
  top: `${shot.value.ring.y}%`,
  width: `${shot.value.ring.w}%`,
  height: `${shot.value.ring.h}%`,
}))
</script>

<template>
  <view class="shot">
    <view class="frame">
      <image class="shot-img" :src="shot.src" mode="widthFix" />
      <view class="ring" :style="ringStyle">
        <text class="badge">{{ shot.no }}</text>
      </view>
    </view>
    <text class="legend">{{ shot.legend }}</text>
  </view>
</template>

<style scoped>
.shot {
  display: flex;
  flex-direction: column;
  align-items: center;
}

/* 手机截图外框：只加圆角与阴影，让「这是一屏真实的页面」一眼可辨 */
.frame {
  position: relative;
  width: 190px;
  background: var(--card);
  border: 1px solid var(--line);
  border-radius: 16px;
  box-shadow: var(--sh-lg);
  /* 不让外圈序号被裁掉：它是标注的一部分，允许露出到手机框外 */
  overflow: visible;
}

.shot-img {
  display: block;
  width: 190px;
  border-radius: 15px;
}

.ring {
  position: absolute;
  box-sizing: border-box;
  border: 2px dashed var(--warm);
  border-radius: 10px;
  /* 只描边，不挡内容；不加动画，引导时不该抢注意力 */
  background: rgba(217, 118, 66, 0.06);
}

.badge {
  position: absolute;
  top: -9px;
  left: -9px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  font-size: 11px;
  font-weight: 600;
  line-height: 1;
  color: #ffffff;
  background: var(--warm);
  border-radius: 50%;
}

.legend {
  display: block;
  margin-top: 9px;
  font-size: 11.5px;
  line-height: 1.6;
  color: #b05526;
}
</style>
