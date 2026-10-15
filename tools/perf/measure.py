#!/usr/bin/env python3
"""Time a completed bash command with a monotonic clock; retain its output."""

import subprocess
import sys
import time


def main():
    output, command = sys.argv[1:]
    with open(output, "wb") as log:
        start = time.monotonic_ns()
        result = subprocess.run(["bash", "-c", command], stdout=log, stderr=subprocess.STDOUT)
        elapsed_ns = time.monotonic_ns() - start
    # Match the shell's conventional signal status without hiding termination.
    rc = result.returncode if result.returncode >= 0 else 128 - result.returncode
    print(f"{elapsed_ns}\t{rc}")


if __name__ == "__main__":
    main()
