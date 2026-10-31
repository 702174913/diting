import antfu from '@antfu/eslint-config'

export default antfu(
  {
    vue: true,
    typescript: true,
    ignores: ['dist', 'unpackage', 'src/static'],
  },
  {
    languageOptions: {
      globals: {
        uni: 'readonly',
        wx: 'readonly',
        getCurrentPages: 'readonly',
        getApp: 'readonly',
        UniApp: 'readonly',
      },
    },
  },
  {
    // antfu 的 TS 解析对 .cjs 脚本会误报 unused-imports（把每个顶层常量都判成“只作类型使用”），
    // 这些是测试/构建脚本，保留 no-unused-vars 即可。
    files: ['**/*.cjs'],
    rules: {
      'unused-imports/no-unused-vars': 'off',
    },
  },
)
