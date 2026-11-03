#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
image="${1:-}"
if [[ ! "$image" =~ ^15565780467w/csgo-tradeup-proxy:run-[0-9]+-[0-9]+$ ]]; then
  printf '用法：%s 15565780467w/csgo-tradeup-proxy:run-运行ID-次数\n' "$0" >&2
  exit 2
fi
if [[ ! -f .env ]]; then
  if [[ ! -f .env.example ]] || ! command -v openssl >/dev/null 2>&1; then
    printf '自动创建 .env 需要 .env.example 和 openssl。\n' >&2
    exit 1
  fi
  umask 077
  service_key="$(openssl rand -hex 32)"
  # noclobber prevents overwriting an .env created by another deployment.
  (set -o noclobber; sed "s/^STEAM_INTERNAL_KEY=$/STEAM_INTERNAL_KEY=${service_key}/" .env.example > .env)
  unset service_key
  printf '已自动创建 .env 和服务密钥；国内后端需要配置相同的服务密钥。\n'
fi
if ! command -v curl >/dev/null 2>&1; then
  printf '缺少 curl，无法验证健康检查。\n' >&2
  exit 1
fi
if [[ "$(uname -m)" != x86_64 ]]; then
  printf '该镜像为 linux/amd64，当前服务器架构不是 x86_64。\n' >&2
  exit 1
fi
if ! grep -Eq '^STEAM_INTERNAL_KEY=[[:alnum:]]{32,}$' .env; then
  printf '请在 .env 中设置至少 32 位字母数字的 STEAM_INTERNAL_KEY（可用 openssl rand -hex 32 生成）。\n' >&2
  exit 1
fi
export PROXY_IMAGE="$image"
compose=(docker compose --project-name csgo-tradeup-proxy --env-file .env -f compose.yaml)
"${compose[@]}" config --quiet
"${compose[@]}" pull proxy
"${compose[@]}" up -d --no-deps --no-build proxy
for attempt in $(seq 1 45); do
  if curl -fsS --max-time 2 http://127.0.0.1:8092/health | grep -q '"application":"csgo-tradeup-proxy"'; then
    printf '服务启动成功：%s\n' "$image"
    exit 0
  fi
  sleep 2
done
printf '健康检查未通过，请检查 docker compose 日志；当前未自动回滚。\n' >&2
"${compose[@]}" ps
exit 1
