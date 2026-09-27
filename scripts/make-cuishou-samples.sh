#!/usr/bin/env bash
#
# 生成谛听催收通话测试样例（wav + mp3），并打印逐句时间戳供 .txt 更新。
#
# 背景：最早的三份样例是用 macOS say 的 Tingting（女声）念双方台词，再把客户侧降调 3 个半音
# 「假造」第二人声 —— 听感上仍是同一个人在变调，声学说话人聚类拿它测不出「两个人」。
# 这里改成真的两个人：催收员用女声、客户用男声（本机 `say -v '?' | grep zh_CN` 里实测过基频，
# 见 README/对话记录：Tingting 254Hz、Eddy 110Hz、Reed 102Hz、Grandpa 86Hz、Rocko 80Hz）。
#
# 用法：
#   bash scripts/make-cuishou-samples.sh              # 用默认音色重新生成三份样例
#   MALE_VOICE="Reed (中文（中国大陆）)" bash scripts/make-cuishou-samples.sh
#   bash scripts/make-cuishou-samples.sh --audition   # 生成 cuishou-voice-audition.mp3 音色对比（不覆盖样例）
#
# 只生成音频，不动 .txt 的正文：脚本会把「逐句起止时间 / 总时长 / 大小 / ID3 头」打出来，
# 需要人工把 .txt 里的元数据行（时长、大小、时间戳、生成方式）同步过来。
#
# 依赖：macOS say、ffmpeg（含 libmp3lame）。本地不引入任何第三方 TTS。
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"

FEMALE_VOICE="${FEMALE_VOICE:-Tingting (中文（中国大陆）)}"
MALE_VOICE="${MALE_VOICE:-Eddy (中文（中国大陆）)}"
SPEED="${SPEED:-180}"          # say -r：词/分（中文近似字/分）
LEAD_SILENCE=0.2               # 开头静音
GAP_SILENCE=0.25               # 轮次间静音（与 .txt 说明里的口径一致）
TAIL_SILENCE=0.3               # 结尾静音
LOUDNESS="${LOUDNESS:--16}"    # loudnorm 目标 LUFS
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# 说话人 -> 音色：F=催收员（女声）、M=客户（男声）
voice_of() {
  case "$1" in
    F) printf '%s' "$FEMALE_VOICE" ;;
    M) printf '%s' "$MALE_VOICE" ;;
    *) echo "未知说话人标记：$1" >&2; exit 1 ;;
  esac
}

duration_of() {
  ffprobe -v error -show_entries format=duration -of csv=p=0 "$1"
}

silence() { # silence <秒> <输出>
  ffmpeg -v error -y -f lavfi -i "anullsrc=r=16000:cl=mono" -t "$1" -c:a pcm_s16le "$2"
}

trim_silence() { # 掐掉句首/句尾静音，轮次间隔才能由 GAP_SILENCE 精确决定
  ffmpeg -v error -y -i "$1" -af "silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.02:detection=peak,areverse,silenceremove=start_periods=1:start_threshold=-45dB:start_silence=0.05:detection=peak,areverse" -c:a pcm_s16le "$2"
}

speak() { # speak <音色> <文本> <输出wav>：say -> 16k 单声道 -> 掐静音
  local voice="$1" text="$2" out="$3"
  say -v "$voice" -r "$SPEED" -o "$TMP/say.aiff" "$text"
  ffmpeg -v error -y -i "$TMP/say.aiff" -ar 16000 -ac 1 -c:a pcm_s16le "$TMP/say.wav"
  trim_silence "$TMP/say.wav" "$out"
}

# 音色对比试听：同一句话依次用各候选音色读一遍，前面用女声报段落名，方便盲听时对照。
audition() {
  local -a voices=(
    "$FEMALE_VOICE|女声·现用催收员"
    "$MALE_VOICE|男声·现用客户"
    "Reed (中文（中国大陆）)|男声候选"
    "Rocko (中文（中国大陆）)|男声候选"
    "Grandpa (中文（中国大陆）)|男声候选"
    "Sandy (中文（中国大陆）)|女声候选"
    "Flo (中文（中国大陆）)|女声候选"
  )
  local -a ordinals=(一 二 三 四 五 六 七 八 九 十)
  local list="$TMP/audition.list" i=0
  : > "$list"
  for entry in "${voices[@]}"; do
    local voice="${entry%%|*}" label="${entry#*|}"
    speak "$FEMALE_VOICE" "第${ordinals[$i]}段，${label}。" "$TMP/a-$i-label.wav"
    speak "$voice" "您的借款已逾期三天，请尽快还款。" "$TMP/a-$i-line.wav"
    printf "file '%s'\nfile '%s'\n" "$TMP/a-$i-label.wav" "$TMP/a-$i-line.wav" >> "$list"
    i=$((i + 1))
  done
  ffmpeg -v error -y -f concat -safe 0 -i "$list" -c copy "$TMP/audition.wav"
  ffmpeg -v error -y -i "$TMP/audition.wav" -af "loudnorm=I=$LOUDNESS:TP=-1.5:LRA=11" -ar 16000 -ac 1 -c:a pcm_s16le "$ROOT/cuishou-voice-audition.wav"
  ffmpeg -v error -y -i "$ROOT/cuishou-voice-audition.wav" -c:a libmp3lame -b:a 64k -ar 16000 -ac 1 -id3v2_version 4 "$ROOT/cuishou-voice-audition.mp3"
  echo "试听文件：cuishou-voice-audition.mp3（依次：Tingting 女声 / Eddy 男声 / Reed 男声 / Rocko 男声 / Grandpa 男声 / Sandy 女声 / Flo 女声）"
}

# make_sample <样例名> <目标总时长秒> <<'EOF'  ... F|台词 ... EOF
make_sample() {
  local name="$1" target="$2"
  local -a roles=() texts=()
  local role text
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    role="${line%%|*}"
    text="${line#*|}"
    roles+=("$role")
    texts+=("$text")
  done

  local n="${#texts[@]}" i
  local -a segs=() durs=()
  local list="$TMP/$name.list"
  : > "$list"

  for ((i = 0; i < n; i++)); do
    local seg="$TMP/$name-$i.wav"
    speak "$(voice_of "${roles[$i]}")" "${texts[$i]}" "$seg"
    segs+=("$seg")
    durs+=("$(duration_of "$seg")")
    printf "file '%s'\n" "$seg" >> "$list"
  done

  # 自然时间轴（先按原速拼接，最后整体 atempo 到目标时长）
  local lead="$TMP/$name-lead.wav" gap="$TMP/$name-gap.wav" tail="$TMP/$name-tail.wav"
  silence "$LEAD_SILENCE" "$lead"
  silence "$GAP_SILENCE" "$gap"
  silence "$TAIL_SILENCE" "$tail"

  local timeline="$TMP/$name.timeline.list"
  : > "$timeline"
  printf "file '%s'\n" "$lead" >> "$timeline"
  for ((i = 0; i < n; i++)); do
    printf "file '%s'\n" "${segs[$i]}" >> "$timeline"
    if [ "$i" -lt $((n - 1)) ]; then
      printf "file '%s'\n" "$gap" >> "$timeline"
    fi
  done
  printf "file '%s'\n" "$tail" >> "$timeline"

  local natural_wav="$TMP/$name.natural.wav"
  ffmpeg -v error -y -f concat -safe 0 -i "$timeline" -c copy "$natural_wav"
  local natural
  natural="$(duration_of "$natural_wav")"

  # 目标时长对齐：整段变速（atempo 保音高，所以男女声的基频差不会被吃掉）
  local tempo
  tempo="$(python3 -c "n,t=$natural,$target; f=n/t; print('%.4f' % min(max(f,0.8),1.25))")"
  local out_wav="$ROOT/$name.wav"
  ffmpeg -v error -y -i "$natural_wav" -af "atempo=$tempo,loudnorm=I=$LOUDNESS:TP=-1.5:LRA=11" -ar 16000 -ac 1 -c:a pcm_s16le "$out_wav"
  # ID3v2.4 头 + 16kHz 单声道 64kbps：与 AudioFormatSniffer 的 mp3 分支/上传上限对齐
  ffmpeg -v error -y -i "$out_wav" -c:a libmp3lame -b:a 64k -ar 16000 -ac 1 -id3v2_version 4 "$ROOT/$name.mp3"

  local total
  total="$(duration_of "$out_wav")"
  echo "── $name ｜ 目标 ${target}s ｜ 自然 ${natural}s ｜ atempo $tempo ｜ 实际 ${total}s"
  printf '   逐句时间（已按 atempo 折算，可直接抄进 .txt）：\n'
  local cursor="$LEAD_SILENCE" i2
  for ((i2 = 0; i2 < n; i2++)); do
    local start end
    start="$(python3 -c "print('%.1f' % ($cursor/$tempo))")"
    end="$(python3 -c "print('%.1f' % (($cursor+${durs[$i2]})/$tempo))")"
    printf '     [00:%04.1f-00:%04.1f] %s：%s\n' "$start" "$end" \
      "$([ "${roles[$i2]}" = F ] && echo 催收员 || echo 客户)" "${texts[$i2]}"
    cursor="$(python3 -c "print($cursor+${durs[$i2]}+$GAP_SILENCE)")"
  done
  printf '   mp3 头 3 字节：%s ｜ 大小 %s KB ｜ 采样率/声道：%s\n' \
    "$(head -c 3 "$ROOT/$name.mp3")" \
    "$(( $(wc -c < "$ROOT/$name.mp3") / 1024 ))" \
    "$(ffprobe -v error -show_entries stream=sample_rate,channels -of csv=p=0 "$ROOT/$name.mp3")"
  echo
}

if [ "${1:-}" = "--audition" ]; then
  audition
  exit 0
fi

echo "催收员=${FEMALE_VOICE}（女声）  客户=${MALE_VOICE}（男声）  say -r=$SPEED"
echo

make_sample cuishou-sample-10s 10.3 <<'EOF'
F|您好，这里是安信金融客服中心。
M|是我，什么事？
F|您的借款已逾期三天，请尽快还款。
M|好的，我马上处理。
EOF

make_sample cuishou-sample-45s 45.3 <<'EOF'
F|您好，请问是王先生本人吗？这里是安信金融客服中心。
M|是我，你们哪位？
F|您在我司的一笔消费分期已经逾期五天了，目前未还金额是三千二百元。
M|我这个月手头确实有点紧，能不能再宽限几天？
F|我理解您的难处，不过逾期会产生罚息，同时也会影响您的信用记录。
M|那我先还一部分可以吗？
F|可以的，您可以通过我们的App或者对公账户还款，剩余部分请您本周五之前处理掉。
M|行，那我今天先还一半进去。
F|好的，那我这边为您登记一下，请您留意还款到账。感谢您的配合，再见。
EOF

make_sample cuishou-sample-48s 48.2 <<'EOF'
F|王先生是吧？您的这笔借款已经逾期十天了，今天就是最后的处理期限。
M|我最近刚失业，实在一下子还不上。
F|还不上的话，今天下午五点之前必须把钱凑齐，不然我们这边就要走下一步流程了。
M|你们能不能再给我一点时间？
F|机会我们已经给过你了，再这么拖下去，我们就要联系你预留的紧急联系人了。
M|你别给我家里人打电话，我再想想办法。
F|那就今天先还一部分，剩下的明天中午之前必须到账，不然我们还会继续跟进。
M|好吧，我尽量想办法凑一下。
F|你记住是这个时间，逾期的后果你自己承担，到时候别说我们没提醒你。
EOF

echo "完成。元数据行（时长/大小/时间戳/生成方式）需要同步进三份 .txt。"
