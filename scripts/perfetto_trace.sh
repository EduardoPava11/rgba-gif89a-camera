#!/bin/bash
# Perfetto trace collection script for M1 fast-path benchmarking

set -e

PACKAGE="com.rgbagif"
TRACE_DURATION=10  # seconds
OUTPUT_FILE="m1_fastpath_$(date +%Y%m%d_%H%M%S).perfetto-trace"

echo "📊 Starting Perfetto trace for M1 fast-path benchmark..."
echo "   Package: $PACKAGE"
echo "   Duration: ${TRACE_DURATION}s"
echo ""

# Check if device is connected
if ! adb devices | grep -q "device$"; then
    echo "❌ No Android device connected"
    exit 1
fi

# Start the app if not running
echo "Starting app..."
adb shell monkey -p $PACKAGE -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1

sleep 2

# Configure and start Perfetto trace
echo "Starting Perfetto trace..."
cat > /tmp/perfetto_config.txt << EOF
buffers: {
    size_kb: 63488
    fill_policy: DISCARD
}
buffers: {
    size_kb: 2048
    fill_policy: DISCARD
}
data_sources: {
    config {
        name: "linux.process_stats"
        target_buffer: 1
        process_stats_config {
            scan_all_processes_on_start: true
        }
    }
}
data_sources: {
    config {
        name: "android.log"
        android_log_config {
            log_ids: LID_DEFAULT
            log_ids: LID_EVENTS
            log_ids: LID_SYSTEM
            filter_tags: "M1Fast"
            filter_tags: "FastPathProcessor"
            filter_tags: "RustCBOR"
        }
    }
}
data_sources: {
    config {
        name: "linux.sys_stats"
        sys_stats_config {
            stat_period_ms: 1000
            stat_counters: STAT_CPU_TIMES
            stat_counters: STAT_FORK_COUNT
        }
    }
}
data_sources: {
    config {
        name: "android.packages_list"
    }
}
data_sources: {
    config {
        name: "linux.ftrace"
        ftrace_config {
            ftrace_events: "sched/sched_switch"
            ftrace_events: "power/suspend_resume"
            ftrace_events: "sched/sched_wakeup"
            ftrace_events: "sched/sched_wakeup_new"
            ftrace_events: "sched/sched_waking"
            ftrace_events: "sched/sched_process_exit"
            ftrace_events: "sched/sched_process_free"
            ftrace_events: "task/task_newtask"
            ftrace_events: "task/task_rename"
            atrace_categories: "camera"
            atrace_categories: "view"
            atrace_categories: "binder_driver"
            atrace_apps: "$PACKAGE"
        }
    }
}
duration_ms: ${TRACE_DURATION}000
EOF

# Push config to device
adb push /tmp/perfetto_config.txt /data/local/tmp/perfetto_config.txt

# Start trace
adb shell perfetto --config /data/local/tmp/perfetto_config.txt --out /data/local/tmp/trace.perfetto-trace &
PERFETTO_PID=$!

echo "Recording trace for ${TRACE_DURATION} seconds..."
echo ""
echo "📱 NOW: Start M1 capture in the app!"
echo "   The trace will capture JNI vs UniFFI performance"
echo ""

# Show countdown
for i in $(seq $TRACE_DURATION -1 1); do
    printf "\r   Time remaining: %2d seconds" $i
    sleep 1
done
printf "\r   Time remaining: Done!        \n"

# Wait for perfetto to finish
wait $PERFETTO_PID 2>/dev/null || true

# Pull trace file
echo ""
echo "Pulling trace file..."
adb pull /data/local/tmp/trace.perfetto-trace "$OUTPUT_FILE"

# Clean up
adb shell rm /data/local/tmp/perfetto_config.txt
adb shell rm /data/local/tmp/trace.perfetto-trace
rm /tmp/perfetto_config.txt

echo ""
echo "✅ Trace saved to: $OUTPUT_FILE"
echo ""
echo "📊 To view the trace:"
echo "   1. Open https://ui.perfetto.dev"
echo "   2. Click 'Open trace file'"
echo "   3. Select $OUTPUT_FILE"
echo ""
echo "🔍 In Perfetto UI, look for:"
echo "   • Search for 'M1Fast' to see JNI write times"
echo "   • Search for 'M1_JNI_WRITE' and 'M1_UNIFFI_WRITE' slices"
echo "   • Check CPU usage during frame writes"
echo "   • Compare durations between implementations"