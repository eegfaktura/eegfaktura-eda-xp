#!/usr/bin/env bash
# Runs the eda-xp test suite: with a local sbt if there is one, else in the pinned sbt build image
# (the same image the workspace builds eda-xp with). Tests only talk to loopback stand-ins (embedded
# PostgreSQL, embedded MQTT broker, fake Ponton) and write only under target/.
#
#   bash scripts/dev/test.sh                     # whole suite
#   bash scripts/dev/test.sh --only '*TenantProviderSpec'
#   bash scripts/dev/test.sh --coverage          # clean, coverage, report, floors (build.sbt)
#   bash scripts/dev/test.sh --update-golden     # rewrite protocol golden files, then review `git diff`
#   bash scripts/dev/test.sh --private <dir>     # add a private test directory for this run
#
# EDA_XP_SBT_CACHE  cache directory for the image run (default ~/.cache/eegfaktura-dev/sbt)
# EDA_XP_SBT_IMAGE  build image (default below; pinned, older than 7 days)
set -euo pipefail
cd "$(dirname "${BASH_SOURCE[0]}")/../.."

IMAGE="${EDA_XP_SBT_IMAGE:-sbtscala/scala-sbt:eclipse-temurin-17.0.19_10_1.13.0_3.8.4}"
CACHE="${EDA_XP_SBT_CACHE:-$HOME/.cache/eegfaktura-dev/sbt}"
cmds=(test); props=(); private_dir=""; coverage=""

while [ $# -gt 0 ]; do
  case "$1" in
    --only) cmds=("testOnly $2"); shift 2 ;;
    --coverage) cmds=(clean coverage test coverageReport); coverage=1; shift ;;
    --update-golden)
      if [ -n "${CI:-}" ]; then echo "--update-golden is not allowed in CI" >&2; exit 2; fi
      props+=("-Deda.golden.update=true"); shift ;;
    --private) private_dir="$(cd "$2" && pwd)"; shift 2 ;;
    -h|--help) sed -n '2,15p' "$0"; exit 0 ;;
    *) echo "unknown option $1" >&2; exit 2 ;;
  esac
done

floors() { [ -z "$coverage" ] || python3 scripts/dev/coverage-check.py; }

if command -v sbt >/dev/null 2>&1; then
  EDA_XP_EXTRA_TESTS="$private_dir" sbt -batch "${props[@]}" "${cmds[@]}"
  floors; exit 0
fi

mkdir -p "$CACHE/sbt" "$CACHE/ivy2" "$CACHE/cache"
docker_args=(--rm -u "$(id -u):$(id -g)" -e TZ=Europe/Vienna
  -e "SBT_OPTS=-Duser.home=/sbt-home -Dsbt.global.base=/sbt-home/.sbt/1.0 -Dsbt.boot.directory=/sbt-home/.sbt/boot -Dsbt.ivy.home=/sbt-home/.ivy2"
  -e COURSIER_CACHE=/sbt-home/.cache/coursier
  -v "$CACHE/sbt:/sbt-home/.sbt" -v "$CACHE/ivy2:/sbt-home/.ivy2" -v "$CACHE/cache:/sbt-home/.cache"
  -v "$PWD:/src" -w /src)
if [ -n "$private_dir" ]; then
  docker_args+=(-v "$private_dir:/private-tests:ro" -e EDA_XP_EXTRA_TESTS=/private-tests)
fi
docker run "${docker_args[@]}" "$IMAGE" sbt -batch "${props[@]}" "${cmds[@]}"
floors
