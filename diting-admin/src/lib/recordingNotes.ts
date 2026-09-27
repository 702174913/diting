/** 逐句转写统一显示「命中规则：《名称》」；完整命中说明仍保留在「命中证据」中。 */
export function displaySentenceNote(note: string): string {
  const hit = /^命中规则：?《([^》]+)》(?:：|$)/.exec(note)
  return hit ? `命中规则：《${hit[1]}》` : note
}
