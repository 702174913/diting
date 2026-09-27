<script setup lang="ts" generic="TData">
/**
 * 表格底座。列定义交给 TanStack Table（v8），单元格统一走 FlexRender。
 *
 * 有意不启用的两件事：
 * - 分页模型：接口是服务端分页（只有 page / pageSize），传进来的就是当前页数据，
 *   页码控件由调用方放进 #footer 插槽；在客户端再分一次只会出现「第 1 页里只有 20 条」的错位。
 * - 排序：后端 list 接口没有排序参数，开了也只能排当前页，属于假功能。
 *   将来要真排序，加 manualSorting + 后端白名单，再补 getSortedRowModel 即可。
 */
import type { ColumnDef } from '@tanstack/vue-table'
import { FlexRender, getCoreRowModel, useVueTable } from '@tanstack/vue-table'
import { toRef } from 'vue'
import { Skeleton } from '@/components/ui/skeleton'
import {
  Table,
  TableBody,
  TableCell,
  TableEmpty,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'

const props = withDefaults(defineProps<{
  columns: ColumnDef<TData, any>[]
  data: TData[]
  loading?: boolean
  /** 表格内空行的文案；页面级空态请用 EmptyState */
  emptyText?: string
  /** 骨架屏占位行数 */
  skeletonRows?: number
}>(), {
  loading: false,
  emptyText: '没有符合条件的记录',
  skeletonRows: 4,
})

const table = useVueTable({
  data: toRef(props, 'data'),
  columns: props.columns,
  getCoreRowModel: getCoreRowModel(),
})
</script>

<template>
  <div class="flex flex-col gap-4">
    <Table>
      <TableHeader>
        <TableRow
          v-for="headerGroup in table.getHeaderGroups()"
          :key="headerGroup.id"
        >
          <TableHead
            v-for="header in headerGroup.headers"
            :key="header.id"
            class="text-xs font-medium text-muted-foreground"
          >
            <FlexRender
              v-if="!header.isPlaceholder"
              :render="header.column.columnDef.header"
              :props="header.getContext()"
            />
          </TableHead>
        </TableRow>
      </TableHeader>
      <TableBody>
        <template v-if="loading">
          <TableRow
            v-for="rowIndex in skeletonRows"
            :key="`skeleton-${rowIndex}`"
          >
            <TableCell
              v-for="(column, columnIndex) in columns"
              :key="`skeleton-cell-${column.id ?? columnIndex}`"
            >
              <Skeleton class="h-4 w-24" />
            </TableCell>
          </TableRow>
        </template>
        <TableEmpty v-else-if="data.length === 0" :colspan="columns.length">
          <span class="text-muted-foreground">{{ emptyText }}</span>
        </TableEmpty>
        <template v-else>
          <TableRow
            v-for="row in table.getRowModel().rows"
            :key="row.id"
          >
            <TableCell
              v-for="cell in row.getVisibleCells()"
              :key="cell.id"
              class="whitespace-normal"
            >
              <FlexRender
                :render="cell.column.columnDef.cell"
                :props="cell.getContext()"
              />
            </TableCell>
          </TableRow>
        </template>
      </TableBody>
    </Table>

    <div v-if="$slots.footer" class="flex flex-wrap items-center justify-between gap-3">
      <slot name="footer" />
    </div>
  </div>
</template>
