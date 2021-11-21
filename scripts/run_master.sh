#!/usr/bin/env bash
set -e

export NODE_ROLE=master
export MAX_MEMORY=2G
export UNIQUE_ID=master-0

mill -i app.runLocal
