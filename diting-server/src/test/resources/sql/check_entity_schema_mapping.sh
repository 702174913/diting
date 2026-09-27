#!/usr/bin/env bash
# =====================================================================
# 实体字段 ↔ 数据库列 一致性检查（MyBatis-Plus 下划线映射）
# 只读：扫描 diting-server 的 entity 类，把驼峰字段换算成 snake_case 列名，
#       再与目标库 information_schema 的实际列比对，抓字段名拼写漂移。
# 用法：
#   MYSQL_PWD='<密码>' bash check_entity_schema_mapping.sh [库名]
#   库名默认 diting（也可传 diting_s01_test 等独立测试库）
# 退出码：0 全一致；1 存在差异
# =====================================================================
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SERVER_DIR="$(cd "$SCRIPT_DIR/../../../.." && pwd)"
ENTITY_DIR="$SERVER_DIR/src/main/java/com/diting/entity"
DB="${1:-diting}"
MYSQL_HOST="${MYSQL_HOST:-127.0.0.1}"
MYSQL_PORT="${MYSQL_PORT:-3306}"
MYSQL_USER="${MYSQL_USER:-root}"

if [ -z "${MYSQL_PWD:-}" ]; then
    echo "缺少 MYSQL_PWD：请通过环境变量提供数据库密码。" >&2
    exit 2
fi

python3 - "$ENTITY_DIR" "$DB" "$MYSQL_HOST" "$MYSQL_PORT" "$MYSQL_USER" <<'PY'
import os
import re
import subprocess
import sys

entity_dir, db, host, port, user = sys.argv[1:6]

def columns(table):
    sql = ("SELECT COLUMN_NAME FROM information_schema.COLUMNS "
           f"WHERE TABLE_SCHEMA='{db}' AND TABLE_NAME='{table}' ORDER BY COLUMN_NAME")
    out = subprocess.run(["mysql", f"-h{host}", f"-P{port}", f"-u{user}",
                          "--batch", "--skip-column-names", "-e", sql],
                         capture_output=True, text=True)
    if out.returncode != 0:
        print(f"ERROR 读取 {table} 列失败：{out.stderr.strip()}")
        sys.exit(2)
    return {line.strip() for line in out.stdout.splitlines() if line.strip()}

def snake(name):
    return re.sub(r'(?<!^)(?=[A-Z])', '_', name).lower()

bad = 0
checked = 0
for fname in sorted(os.listdir(entity_dir)):
    if not fname.endswith(".java"):
        continue
    path = os.path.join(entity_dir, fname)
    text = open(path, encoding="utf-8").read()
    m = re.search(r'@TableName\("([^"]+)"\)', text)
    if not m:
        continue
    table = m.group(1)
    cols = columns(table)
    if not cols:
        print(f"FAIL  {fname}: 表 {table} 在库 {db} 中不存在")
        bad += 1
        continue
    # 显式 @TableId(value="x") / @TableField("x") 以注解为准，其余按驼峰换算
    explicit = {snake(f): f for f in re.findall(r'@Table(?:Id|Field)\(value\s*=\s*"([^"]+)"', text)}
    fields = re.findall(r'^\s+private\s+[\w<>\[\], .]+\s+(\w+)\s*;', text, re.M)
    missing = []
    for f in fields:
        col = explicit.get(f, snake(f))
        if col not in cols:
            missing.append(f"{f}->{col}")
    checked += 1
    if missing:
        print(f"FAIL  {fname}: 表 {table} 缺列 {', '.join(missing)}")
        bad += 1
    else:
        print(f"PASS  {fname}: 表 {table} 的 {len(fields)} 个字段全部有对应列")

print(f"== 实体映射检查：类={checked} 不一致={bad}（库 {db}） ==")
sys.exit(1 if bad else 0)
PY
