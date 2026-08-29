#!/bin/bash

set -e

APK_PATH="app/build/outputs/apk/debug/app-debug.apk"

LOG_FILE="$HOME/Desktop/log.txt"

echo "==> بناء المشروع..."

./gradlew assembleDebug

echo "==> تثبيت التطبيق..."

adb install -r "$APK_PATH"

PACKAGE_NAME=$(aapt dump badging "$APK_PATH" | grep package: | awk -F"'" '{print $2}' | head -n1)

LAUNCH_ACTIVITY=$(aapt dump badging "$APK_PATH" | grep "launchable-activity" | awk -F"'" '{print $2}' | head -n1)

echo "==> منح صلاحية تعديل الإعدادات (مرة واحدة، آمنة التكرار)..."

adb shell pm grant "$PACKAGE_NAME" android.permission.WRITE_SECURE_SETTINGS 2>/dev/null || true

echo "==> إيقاف التطبيقات الأخرى وتفريغ الكاش..."

for pkg in $(adb shell pm list packages -3 | sed 's/package://'); do

    if [ "$pkg" != "$PACKAGE_NAME" ]; then

        adb shell am force-stop "$pkg" 2>/dev/null

        adb shell pm clear "$pkg" 2>/dev/null || true

    fi

done

echo "==> رفع الصوت إلى 30..."

adb shell media volume --stream 3 --set 30

echo "==> مسح اللوق القديم وبدء التسجيل..."

adb logcat -c

adb logcat > "$LOG_FILE" &

LOGCAT_PID=$!

trap "kill $LOGCAT_PID 2>/dev/null; echo; echo تم حفظ اللوق في: $LOG_FILE; exit 0" INT

sleep 1

echo "==> تشغيل التطبيق..."

adb shell am start -n "$PACKAGE_NAME/$LAUNCH_ACTIVITY"

echo "==> يسجل اللوق الآن... اضغط Ctrl+C للإيقاف والحفظ"

wait $LOGCAT_PID
