<script setup lang="ts">
/**
 * 总览看板。
 * 北极星：有效质检报告产出量 —— 唯一同时体现获客、产品有效性与交付价值的指标。
 *
 * 页面上的每个数字都来自 /api/admin/stats/overview：指标带来源表与分母，
 * 序列（按日有效报告 / 新增注册）与用户漏斗都是聚合结果。
 * 此前这里的 KPI、漏斗、渠道表、单位经济收益模型全是写死的演示值，已整体删除 ——
 * 一份能被核对的空位，比一排看起来很专业的假数字有价值。
 */
import MetricsPanel from '@/components/MetricsPanel.vue'
import Hint from '@/components/Hint.vue'
import PageHeader from '@/components/PageHeader.vue'
import Panel from '@/components/Panel.vue'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
</script>

<template>
  <div class="flex flex-col gap-4">
    <PageHeader
      title="总览看板"
      description="北极星：有效质检报告产出量 —— 唯一同时体现获客、产品有效性与交付价值的指标"
    />

    <MetricsPanel section="overview" />

    <Panel title="北极星为什么是「有效报告量」" note="指标定义">
      <Table class="text-sm [&_td]:whitespace-normal">
        <TableHeader>
          <TableRow>
            <TableHead>它的好处</TableHead>
            <TableHead>具体含义</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          <TableRow>
            <TableCell class="whitespace-normal">
              <strong>不可伪造</strong>
            </TableCell>
            <TableCell class="whitespace-normal">
              只有真正跑通「上传 → 转写 → 判读 → 发布报告」的任务才计数，按 task 取首个发布版本，
              角色重算不会重复计入
            </TableCell>
          </TableRow>
          <TableRow>
            <TableCell class="whitespace-normal">
              <strong>同时代表三方价值</strong>
            </TableCell>
            <TableCell class="whitespace-normal">
              用户拿到了可用的质检结论；产品证明管线真的跑通；交付侧产出了可导出、可留存的证据材料
            </TableCell>
          </TableRow>
          <TableRow>
            <TableCell class="whitespace-normal">
              <strong>可以拆到过程指标</strong>
            </TableCell>
            <TableCell class="whitespace-normal">
              注册 → 授权 → 上传 → 提交质检 → 产出报告，逐环都是用户口径，能定位流失在哪一步
            </TableCell>
          </TableRow>
        </TableBody>
      </Table>

      <template #hint>
        <Hint>
          <b>这页不出现的数字：</b>付费转化率、MRR、ARPU、LTV/CAC、渠道结构、毛利率 ——
          产品没有支付/订阅系统，也没有渠道归因与成本记账表，这些指标没有数据源。
          它们不会以「演示值」的形式留在这里，避免被误当成经营事实带入尽调与谈判。
        </Hint>
      </template>
    </Panel>
  </div>
</template>
