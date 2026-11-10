#!/usr/bin/env bash
set -x
mkdir -p shots
APK=$(ls apk/*debug.apk | head -1)
PKG=org.fossify.gallery.debug
adb install -r "$APK"
adb shell mkdir -p /sdcard/DCIM/Camera /sdcard/Pictures/Trips
for i in 1 2 3; do adb push photos/img_$i.png /sdcard/DCIM/Camera/; done
for i in 4 5 6; do adb push photos/img_$i.png /sdcard/Pictures/Trips/; done
for f in /sdcard/DCIM/Camera/*.png /sdcard/Pictures/Trips/*.png; do
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$f" >/dev/null
done
for p in android.permission.READ_MEDIA_IMAGES android.permission.READ_MEDIA_VIDEO android.permission.POST_NOTIFICATIONS; do
  adb shell pm grant $PKG $p
done
adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
adb shell monkey -p $PKG -c android.intent.category.LAUNCHER 1
sleep 8; adb exec-out screencap -p > shots/1_launch.png
SIZE=$(adb shell wm size | grep -o '[0-9]*x[0-9]*' | tail -1)
W=${SIZE%x*}; H=${SIZE#*x}
Y=$((H-200))
adb shell input tap $((W/2)) $Y; sleep 4; adb exec-out screencap -p > shots/2_people.png
adb shell input tap $((W*5/6)) $Y; sleep 4; adb exec-out screencap -p > shots/3_albums.png
adb shell input tap $((W/6)) $Y; sleep 4; adb exec-out screencap -p > shots/4_pictures.png
adb shell uiautomator dump /sdcard/ui.xml; adb pull /sdcard/ui.xml shots/ui.xml || true
