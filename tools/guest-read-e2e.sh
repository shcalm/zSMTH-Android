#!/usr/bin/env bash
set -euo pipefail

task_device=${1:-9d75a0b7}
task_output=${2:?请指定新的测试证据目录}
mkdir -p "$task_output"
task_start=$(adb -s "$task_device" shell date '+%m-%d %H:%M:%S.000')

for task_mode in cancel verify reuse; do
    adb -s "$task_device" shell am force-stop com.zfdang.zsmth_android
    adb -s "$task_device" shell am instrument -w -e mode "$task_mode" \
        com.zfdang.zsmth_android.test/com.zfdang.zsmth_android.GuestReadE2E \
        > "$task_output/e2e-$task_mode.log" 2>&1
    adb -s "$task_device" pull \
        "/sdcard/Android/data/com.zfdang.zsmth_android/files/guest-read-e2e/$task_mode" "$task_output/"
    python3 - "$task_output/$task_mode/result.json" <<'PY'
import json
import sys
from pathlib import Path
result = json.loads(Path(sys.argv[1]).read_text())
print(json.dumps(result, ensure_ascii=False))
assert result.get('结果') == '通过', result
PY
done

adb -s "$task_device" logcat -d -T "$task_start" -v threadtime \
    GuestReadVerifier:I PostListActivity:I GuestReadE2E:E AndroidRuntime:E '*:S' \
    > "$task_output/device.log"
adb -s "$task_device" shell am start -n com.zfdang.zsmth_android/.MainActivity
