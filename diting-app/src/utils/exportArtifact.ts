/**
 * 导出产物的下载文件名：`报告编号-v版本.扩展名`（如 `DT-2026-001046-v2.zip`）。
 *
 * 与服务端 `ExportService.artifactFileName` 同一口径，必须保持一致：
 * - H5 另存时以服务端 `Content-Disposition` 为准，这里的名字是提示值（跨域时 download 属性会被忽略）；
 * - 小程序端没有响应头可读，转发/保存用的就是这里的名字。
 *
 * 不得包含用户敏感姓名（开发文档 §9）。
 */
export function exportArtifactName(reportNo: string, version: number, fileType: string): string {
  return `${reportNo}-v${version}.${fileType}`
}
