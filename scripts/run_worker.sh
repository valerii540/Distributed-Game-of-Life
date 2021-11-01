#!/usr/bin/env bash
set -e

export NODE_ROLE=worker
export ARTERY_PORT=$1
export MAX_MEMORY=$2
export UNIQUE_ID=$3

if [ -n "$4" ] && [ -n "$5" ]; then
  export OVERRIDE_HEIGHT=$4
  export OVERRIDE_WIDTH=$5
fi

sbt
