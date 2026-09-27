/**
 * 命中说明在展示层只保留「哪条规则」。
 *
 * 判读依据是原文里被高亮的那几句，规则名足够定位；规则库里配的触发词/应答词属于内部配置，
 * 不是读报告的人需要核对的内容。说明文案由服务端在判读时落库，历史报告里仍带这些词，
 * 所以统一在这里收口，新旧报告呈现一致（`命中证据` 里的完整说明不经过这里）。
 */
const HIT_PREFIX = /^命中规则《([^》]+)》/
const MUST_HAVE_MISSING = /^本通话中未出现必需表述/
const RULE_TAIL = /\s*（规则《[^》]+》）\s*$/

/** 逐句命中：只给规则名；解析不到的结构（历史或其它类型说明）原样返回，不吞信息 */
export function displaySentenceNote(note: string): string {
  const hit = HIT_PREFIX.exec(note)
  return hit ? hit[1] : note
}

/** 通话级缺失项：规则名已在卡片标题上，说明里不再罗列规则库内部的词与重复的规则名 */
export function displayViolationNote(note: string): string {
  const hit = HIT_PREFIX.exec(note)
  if (hit) {
    return hit[1]
  }
  if (MUST_HAVE_MISSING.test(note)) {
    return '本通话中未出现必需表述'
  }
  return note.replace(RULE_TAIL, '').trim()
}
