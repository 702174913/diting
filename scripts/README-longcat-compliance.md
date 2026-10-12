# LongCat 合成催收判读测试

使用指定模型 `opencode/longcat-2.5-preview-free`（LongCat 2.5 Preview Free），不自动切换模型。
需要 Python 3 和 PATH 中的 OpenCode。本次适配的 CLI 版本是 v2.0.24。

## 数据安全与范围

- **这是云模型测试，不是本地推理。** 只发送脚本内人工编写的合成通话，不连接数据库、不读取真实音频、转写文本或客户信息。
- 使用临时工作目录、临时 HOME/XDG 配置和独立 OpenCode 服务，不继承个人插件、项目规则文件或登录凭证。所有 agent 工具权限设为 deny，关闭分享。
- 模型和轻量辅助模型均固定到相同 Free 型号。不可用、超时、非 JSON 回答均保存失败，不换供应商。
- OpenCode 会访问模型服务，并可能下载其运行依赖；这是应用层隔离，不是操作系统沙箱。
- 仅 4 条人工概括的示范规则：威胁、侮辱、泄露债务、冒充国家机关。**不是数据库完整 31 条规则的回归测试**，不测试缺失项判定、确定性扣分或报告入库。

## 执行

默认只导出提示词及预期答案，不调用模型：

```sh
python3 scripts/test_longcat_compliance.py
```

实际调用（仅合成通话）：

```sh
python3 scripts/test_longcat_compliance.py --execute
```

指定输出与最长等待时间：

```sh
python3 scripts/test_longcat_compliance.py --execute --timeout 240 --out artifacts/longcat-compliance/run-01
```

离线评分器测试：

```sh
python3 -m unittest discover -s scripts -p 'test_longcat_compliance_unit.py' -v
```

## 测试内容

12 个案例包含正例、否定句、客户辱骂、投诉引用、依法说明诉讼、第三方泄露与仅转达回电、冒充警察与身份否定、角色反转、通话内容中的提示注入。
预期答案不出现在发送给模型的提示词中。一次请求批量返回所有案例；并非 12 次独立重复实验。

模型返回角色映射与 `rule_code / turn_id / quote / reason`。脚本检查：

1. 案例完整、无重复，规则和句子引用有效。
2. 角色是否与预期一致。
3. 违规规则与句子是否完全匹配预期，计算 TP/FP/FN、precision、recall。
4. 摘录是否确实出现在对应原文，且 reason 非空。
5. 唯一原文摘录定位成功则生成 `[start,end)`，否则整句展示。

字符下标按 **Python Unicode code point** 计算，不是 Java/JavaScript UTF-16 offset，不能直接当作前端下标使用。整句回退不把幻觉摘录算作通过。原文存在性也不代表该片段一定足够支撑违规，需要人工查看证据。

## 产物与退出码

- `prompt.txt`：真正发送的合成输入。
- `expected.json`：本地预期，不发送模型。
- `raw.jsonl` / `stderr.log`：OpenCode 原始输出与错误。
- `answer.txt` / `answer.json`：模型最终文本与结构化答案（若解析成功）。
- `report.json`：案例明细、角色准确数、命中/误报/漏报、摘录与定位、耗时。
- `failure.json`：运行或格式失败信息（若失败）。

退出码：`0` 全部通过或 dry-run；`1` 可评分但存在案例不通过；`2` 服务调用/格式/环境异常。

即使 12/12 通过，也只证明这个小型合成测试中的方案可行，不代表生产准确率、不代表本地 Qwen3-8B 效果。正式评估应覆盖完整规则、脱敏长通话、边界样本与多次重复测试。
