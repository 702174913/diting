# PDF 字体

- `NotoSansSC-Regular.ttf`：正文常规字重。
- `NotoSansSC-Bold.ttf`：商务模板标题与强调文字的 700 字重。
- `OFL-NotoSansSC.txt`：两份字体及衍生版本的 SIL OFL 1.1 授权。

Bold 从 Noto Sans SC 可变字体的 `wght=700` 实例化，使用 FontTools 生成静态 TrueType。
为避免 PDFBox 的 ToUnicode 将“用、音、示”等汉字反向映射成兼容部首字符，
对共享同一 glyph 的 Unicode 别名复制相同的 `glyf` 字形与 `hmtx` / `vmtx` 度量，
并在所有 Unicode cmap 子表中给别名分配独立 glyph ID（共 497 处）。
字形外观和度量不变；既保留汉字，也保留兼容字符的编码支持，不通过删除 cmap 条目规避问题。

两份字体均在服务器渲染时从 classpath 加载，并以子集嵌入 PDF；
运行时不依赖 FontTools，不下载远端字体。
`BusinessReportTemplateTest` 的封面与免责声明文本抽取断言覆盖该 Unicode 回归问题。
