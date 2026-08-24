#!/usr/bin/env bash
# 断言两前端零跨引用（宪法：禁共享包/跨项目 import）
set -euo pipefail
cd "$(dirname "$0")/.."
fail=0
grep -rn "student-frontend" frontend/src 2>/dev/null && fail=1
grep -rn "from ['\"].*\.\./student-frontend" frontend/src 2>/dev/null && fail=1
grep -rn "'frontend/" student-frontend/src 2>/dev/null | grep -v "student-frontend" && fail=1
grep -rn "from ['\"].*\.\./frontend" student-frontend/src 2>/dev/null && fail=1
[ "$fail" -eq 0 ] && echo "cross-import check passed" || { echo "VIOLATION: 跨前端引用"; exit 1; }
