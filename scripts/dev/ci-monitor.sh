#!/usr/bin/env bash
# Resource monitor for the CI test steps: one line every CI_MONITOR_INTERVAL seconds (default 120) in the step
# log and in $RUNNER_TEMP/ci-monitor.log, which the workflows upload with `if: always()` — so the numbers survive
# a job that runs into its timeout (GitHub keeps no log of a cancelled job). GitHub-hosted runners report no
# CPU or memory per job; this fills the gap without an external action.
#
#   scripts/dev/ci-monitor.sh start   # in the test step, before sbt (runs in the background)
#   scripts/dev/ci-monitor.sh stop    # in the same step on exit (trap): stops it and prints the peaks
#
# Columns: load (1/5/15 min), memory and swap used (MB), processes, threads, java and postgres processes, TCP
# sockets on the embedded broker port 18831 (clients the tests left behind) and the largest process.
set -u

dir="${RUNNER_TEMP:-/tmp}"
log="$dir/ci-monitor.log"
pidfile="$dir/ci-monitor.pid"
interval="${CI_MONITOR_INTERVAL:-120}"

sample() {
  local mem swap top
  mem=$(free -m | awk '/^Mem:/{print $3}')
  swap=$(free -m | awk '/^Swap:/{print $3}')
  top=$(ps -eo rss=,comm= --sort=-rss | head -1 | awk '{printf "%s:%dMB", $2, $1/1024}')
  echo "ci-monitor $(date -u +%T) load=$(cut -d' ' -f1-3 /proc/loadavg | tr ' ' '/') mem=${mem}MB swap=${swap}MB" \
    "procs=$(ps -e --no-headers | wc -l) threads=$(ps -eLf --no-headers | wc -l)" \
    "java=$(pgrep -c java) postgres=$(pgrep -c postgres) tcp18831=$(ss -tan 2>/dev/null | grep -c ':18831 ') top=$top"
}

case "${1:-}" in
  start)
    : > "$log"
    ( while true; do sample | tee -a "$log"; sleep "$interval"; done ) &
    echo $! > "$pidfile"
    ;;
  stop)
    [ -f "$pidfile" ] && kill "$(cat "$pidfile")" 2>/dev/null
    sample | tee -a "$log"
    awk '{ for (i = 1; i <= NF; i++) { split($i, kv, "="); v = kv[2] + 0
             if (kv[1] == "mem" && v > m) m = v; if (kv[1] == "threads" && v > t) t = v
             if (kv[1] == "postgres" && v > p) p = v; if (kv[1] == "tcp18831" && v > c) c = v } }
         END { printf "ci-monitor peaks: mem=%dMB threads=%d postgres=%d tcp18831=%d (%d samples)\n", m, t, p, c, NR }' "$log"
    mkdir -p target/test-reports && cp "$log" target/test-reports/ci-monitor.log
    ;;
  *)
    echo "usage: $0 start|stop" >&2
    exit 2
    ;;
esac
