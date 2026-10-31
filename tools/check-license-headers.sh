#!/usr/bin/env bash
# Verifies that every source file carries the GPL-3.0-or-later copyright header.
# Usage: tools/check-license-headers.sh (run from the repository root)
set -euo pipefail

missing=0
while IFS= read -r file; do
    if ! head -n 20 "$file" | grep -q "SPDX-License-Identifier: GPL-3.0-or-later"; then
        echo "missing license header: $file"
        missing=$((missing + 1))
    fi
done < <(
    find app core device feature tools mcp eval \
        \( -name "*.kt" -o -name "*.kts" \) \
        -not -path "*/build/*" \
        -not -path "*/.gradle/*"
    find . -maxdepth 1 -name "*.gradle.kts"
    find dev -name "*.py"
)

if [ "$missing" -gt 0 ]; then
    echo ""
    echo "$missing file(s) are missing the license header."
    echo "See docs/CONTRIBUTING.md for the exact header."
    exit 1
fi

echo "All source files carry the license header."
