<script setup lang="ts">
/**
 * 导出任务：异步导出（PDF / 含原件 ZIP）的进度与下载入口。
 * 作业挂在 store 上，所以关掉这个弹窗不会中断跟踪；完成时由 store 发 toast。
 */
import { FileDownIcon, RefreshCwIcon } from '@lucide/vue'
import { ref, watch } from 'vue'
import { toast } from 'vue-sonner'
import EmptyState from '@/components/EmptyState.vue'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { EXPORT_STATUS_TEXT, labelOf, variantOf } from '@/lib/enums'
import { saveToDisk, useExportTaskStore } from '@/stores/exportTask'
import { Switch } from '@/components/ui/switch'
import type { ExportJob } from '@/api/admin'

const open = defineModel<boolean>('open', { required: true })
const store = useExportTaskStore()
const downloadingId = ref<number | null>(null)

/**
 * 下载：完成态才调 /download 签发一次短时地址。
 * 状态轮询不再下发地址、不写审计，点下载才签发并留痕。
 */
async function downloadJob(exportId: number) {
  if (downloadingId.value !== null) {
    return
  }
  downloadingId.value = exportId
  try {
    // 手动与自动走同一个落地方式：服务端的 Content-Disposition 决定文件名
    saveToDisk(await store.download(exportId))
  }
  catch (e) {
    toast.error(e instanceof Error ? e.message : '获取下载地址失败')
  }
  finally {
    downloadingId.value = null
  }
}

/** 导出作业状态配色：完成绿、失败红、其余（排队/生成中）黄 */
const EXPORT_STATUS_VARIANT: Record<string, 'up' | 'down' | 'warn'> = {
  COMPLETED: 'up',
  FAILED: 'down',
  RUNNING: 'warn',
  QUEUED: 'warn',
}

function fileLabel(job: ExportJob) {
  return `${job.fileType.toUpperCase()}${job.withAudio ? '（含原件）' : ''}`
}

// 每次打开都回查一次：刷新页面后回来能立刻看到最新状态
watch(open, (visible) => {
  if (visible) {
    void store.refresh()
  }
})
</script>

<template>
  <Dialog v-model:open="open">
    <DialogContent class="sm:max-w-2xl">
      <DialogHeader>
        <DialogTitle>导出任务</DialogTitle>
        <DialogDescription>
          PDF 与含原件 ZIP 都是异步生成；作业一完成就自动开始下载，顶栏也会提示。
          若浏览器拦下了自动下载，在这里点「下载」重签一次短时地址。
        </DialogDescription>
      </DialogHeader>

      <div v-if="store.jobs.length" class="flex max-h-[55vh] flex-col gap-2 overflow-y-auto">
        <div
          v-for="job in store.jobs"
          :key="job.exportId"
          class="flex items-center gap-3 rounded-md border border-border px-3 py-2.5"
        >
          <div class="min-w-0 flex-1">
            <div class="truncate text-sm">
              {{ job.reportNo }} · 第 {{ job.reportVersion ?? '—' }} 版
            </div>
            <div class="text-xs text-muted-foreground">
              #{{ job.recordingId }} · {{ fileLabel(job) }} · 作业 #{{ job.exportId }}
              <span v-if="job.createdAt"> · {{ job.createdAt.slice(5, 16).replace('T', ' ') }}</span>
              <span v-if="job.status === 'FAILED' && job.errorCode"> · {{ job.errorCode }}</span>
              <span v-if="job.status === 'COMPLETED' && job.autoDownloaded"> · 已自动下载</span>
            </div>
          </div>

          <Badge :variant="variantOf(EXPORT_STATUS_VARIANT, job.status)" size="chip">
            {{ labelOf(EXPORT_STATUS_TEXT, job.status) }}
          </Badge>

          <Button
            v-if="job.status === 'COMPLETED'"
            variant="outline"
            size="sm"
            :disabled="downloadingId === job.exportId"
            @click="downloadJob(job.exportId)"
          >
            <FileDownIcon data-icon="inline-start" />
            {{ downloadingId === job.exportId ? '签发中…' : '下载' }}
          </Button>
          <Button
            v-else-if="job.status === 'FAILED'"
            variant="ghost"
            size="sm"
            @click="store.forget(job.exportId)"
          >
            移除
          </Button>
        </div>
      </div>

      <EmptyState
        v-else
        title="还没有导出任务"
        description="在录音详情里点「导出报告」或「导出含原件 ZIP」。"
        :icon="FileDownIcon"
      />

      <DialogFooter class="items-center gap-3 sm:justify-between">
        <label class="flex items-center gap-2 text-sm text-muted-foreground">
          <Switch
            :model-value="store.autoDownloadEnabled"
            @update:model-value="value => store.setAutoDownload(Boolean(value))"
          />
          完成后自动下载
        </label>
        <Button variant="outline" @click="store.refresh()">
          <RefreshCwIcon data-icon="inline-start" />
          刷新状态
        </Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
