#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOCAL_MAVEN_HOME="${SCRIPT_DIR}/.mvn/local/apache-maven-3.9.11"
FALLBACK_JAVA_HOME="/home/aphelio/.jbr/jbr-25.0.2-linux-x64-b329.117"

if [[ -z "${JAVA_HOME:-}" && -x "${FALLBACK_JAVA_HOME}/bin/java" ]]; then
  export JAVA_HOME="${FALLBACK_JAVA_HOME}"
  export PATH="${JAVA_HOME}/bin:${PATH}"
fi

filter_known_java25_maven_warning() {
  awk '
    BEGIN {
      warning1 = "WARNING: A terminally deprecated method in sun.misc.Unsafe has been called"
      warning2 = "WARNING: sun.misc.Unsafe::staticFieldBase has been called by com.google.inject.internal.aop.HiddenClassDefiner"
      warning3 = "WARNING: Please consider reporting this to the maintainers of class com.google.inject.internal.aop.HiddenClassDefiner"
      warning4 = "WARNING: sun.misc.Unsafe::staticFieldBase will be removed in a future release"
      pending = 0
    }

    function flush_pending() {
      for (i = 1; i <= pending; i++) {
        print lines[i] > "/dev/stderr"
      }
      pending = 0
    }

    {
      if (pending == 0) {
        if ($0 == warning1) {
          pending = 1
          lines[pending] = $0
        } else {
          print > "/dev/stderr"
        }
        next
      }

      pending++
      lines[pending] = $0

      if (pending == 2) {
        if (index($0, warning2) != 1) {
          flush_pending()
        }
        next
      }

      if (pending == 3) {
        if ($0 != warning3) {
          flush_pending()
        }
        next
      }

      if (pending == 4) {
        if ($0 != warning4) {
          flush_pending()
        } else {
          pending = 0
        }
      }
    }

    END {
      flush_pending()
    }
  '
}

run_maven() {
  local mvn_bin="$1"
  shift
  "$mvn_bin" "$@" 2> >(filter_known_java25_maven_warning)
}

if [[ -x "${LOCAL_MAVEN_HOME}/bin/mvn" ]]; then
  run_maven "${LOCAL_MAVEN_HOME}/bin/mvn" "$@"
  exit $?
fi

if command -v mvn >/dev/null 2>&1; then
  run_maven "$(command -v mvn)" "$@"
  exit $?
fi

echo "No Maven installation found. Expected ${LOCAL_MAVEN_HOME}/bin/mvn or mvn on PATH." >&2
exit 127
