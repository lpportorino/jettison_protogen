#!/usr/bin/env bash
# Preserve deliberate path-boundary fixtures in one portable artifact filename.
set -euo pipefail

if [ "$#" -ne 2 ]; then
  echo 'usage: pack-evidence.sh <evidence-directory> <archive.tar.gz>' >&2
  exit 2
fi

evidence=$1
archive=$2
if [ ! -d "$evidence" ]; then
  if [ -e "$evidence" ]; then
    echo 'press: evidence path exists but is not a directory' >&2
    exit 2
  fi
  # A startup refusal can leave only the separately captured command log.
  echo 'press: no browser directory to archive; retain the command log' >&2
  exit 0
fi

tar -C "$evidence" -czf "$archive" .
