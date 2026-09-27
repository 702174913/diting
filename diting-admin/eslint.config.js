import antfu from '@antfu/eslint-config'

export default antfu({
  vue: true,
  typescript: true,
  ignores: [
    'dist',
    // 注：src/components/ui/** （shadcn-vue 注册表拷入的源码）曾经整目录豁免，
    // 理由是「上游拥有其代码风格」。实际量过之后只有 738 条纯格式差异
    // （引号 711 + import 顺序 24 + quote-props 3，全部可自动修、无结构规则），
    // 于是一次性统一到本仓库风格并取消豁免 —— 全库一个 lint 结论更省心。
    // 代价：将来从注册表拉新组件或做 --diff 合并时，会看到一层引号级噪音，重跑 lint --fix 即可。
  ],
})
