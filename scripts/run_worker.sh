#!/usr/bin/env bash
set -e

export NODE_ROLE=worker
export ARTERY_PORT=$1
export MAX_MEMORY=$2
export UNIQUE_ID=$3

sbt run
