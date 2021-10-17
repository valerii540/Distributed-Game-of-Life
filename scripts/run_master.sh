#!/usr/bin/env bash
set -e

export NODE_ROLE=master
export MAX_MEMORY=2G

sbt run
