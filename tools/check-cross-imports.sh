#!/usr/bin/env bash
# 断言两前端零跨引用（宪法：禁共享包/跨项目 import）
set -euo pipefail
cd "$(dirname "$0")/.."
fail=0
grep -rn "student-frontend" frontend/src 2>/dev/null && fail=1
grep -rn "from ['\"].*\.\./student-frontend" frontend/src 2>/dev/null && fail=1
# 规则 3：按 import 语句内容检测（包式引用 frontend/…）；
# 旧写法用 grep -v 过滤 grep -rn 输出的 student-frontend/ 路径前缀，导致规则永不触发（死规则），已修正
grep -rn -E "from ['\"]frontend/|from ['\"]\.\./frontend" student-frontend/src 2>/dev/null && fail=1
grep -rn "from ['\"].*\.\./frontend" student-frontend/src 2>/dev/null && fail=1
[ "$fail" -eq 0 ] && echo "cross-import check passed" || { echo "VIOLATION: 跨前端引用"; exit 1; }
