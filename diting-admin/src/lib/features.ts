/**
 * 前端功能开关（只控制"入口是否可见"，不改变服务端判读逻辑）。
 *
 * ROLE_TARGETING_ENABLED = false：规则「目标角色」功能暂时隐藏。
 *   - 规则编辑器不再显示「目标角色」「触发角色」字段，保存时固定下发「任意角色」；
 *   - 规则列表与版本历史不再展示角色列；批量导入模板保留列以兼容已有 CSV。
 *
 * 与服务端 `RuleMatchService.ROLE_TARGETING_HIDDEN` 是一对：
 *   那边决定判读口径（隐藏态下角色限定一律按「任意角色」处理，不区分说话人），
 *   这里决定界面入口。后期重新启用：两处一起改回来（true / false），
 *   并恢复角色确认入口（`ReportService.saveMapping` 目前没有 HTTP 路由）。
 */
export const ROLE_TARGETING_ENABLED = false
