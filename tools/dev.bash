#!/usr/bin/env bash
# ============================================================================
# commerce-customer 开发环境启动脚本（bash 版入口）
#
# 与 tools/dev.sh 同一份逻辑：无论以 bash / sh / ./dev.bash 哪种方式调用，
# 统一强制以 bash 执行 dev.sh，避免 POSIX sh 环境下个别 shell 实现差异。
# ============================================================================
exec bash "$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)/dev.sh" "$@"
