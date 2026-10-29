/**
 * 演示模式：只用于本地预览交互（后端 / 微信登录尚未就绪时）。
 *
 * 两个条件同时成立才生效：
 * - `import.meta.env.DEV`：生产构建时恒为 false，**打包产物里不可能被打开**；
 * - `VITE_DEMO_MODE=true`：由本地 `.env.development` 提供，该文件被 .gitignore 忽略。
 *
 * 打开后：跳过登录门禁与额度预检，文件选完即可看到提交前的问卷与同意弹层；
 * 真正提交时**不发请求**，只把采集到的答案打到控制台，避免制造半真半假的任务。
 */
export const DEMO_MODE = import.meta.env.DEV && import.meta.env.VITE_DEMO_MODE === 'true'
