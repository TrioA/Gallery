#!/usr/bin/env bash
set -x
mkdir -p shots
APK=$(ls apk/*x86_64*debug.apk | head -1)
PKG=org.fossify.gallery.debug
adb install -r "$APK"
adb shell mkdir -p /sdcard/DCIM/Camera /sdcard/Pictures/Trips /sdcard/Pictures/Family
for i in 1 2 3 4; do adb push photos/img_$i.png /sdcard/DCIM/Camera/; done
for i in 5 6 7 8; do adb push photos/img_$i.png /sdcard/Pictures/Trips/; done
for i in 9 10 11 12; do adb push photos/img_$i.png /sdcard/Pictures/Family/; done
adb shell mkdir -p /sdcard/Pictures/People
for f in faces/*.jpg; do adb push $f /sdcard/Pictures/People/; done
for f in /sdcard/Pictures/People/*.jpg /sdcard/DCIM/Camera/*.png /sdcard/Pictures/Trips/*.png /sdcard/Pictures/Family/*.png; do
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file://$f" >/dev/null
done
for p in android.permission.READ_MEDIA_IMAGES android.permission.READ_MEDIA_VIDEO android.permission.POST_NOTIFICATIONS; do
  adb shell pm grant $PKG $p
done
adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
sleep 5

dismiss_anr() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null
  if grep -q "isn't responding" /tmp/ui.xml; then tap_raw Wait; fi
}
tap_raw() {
  XY=$(python3 - "$1" <<'PY'
import sys,re,xml.etree.ElementTree as ET
t=sys.argv[1]
for n in ET.parse('/tmp/ui.xml').iter('node'):
    if n.get('text')==t:
        m=re.findall(r'\d+',n.get('bounds'));x1,y1,x2,y2=map(int,m)
        print((x1+x2)//2,(y1+y2)//2);break
PY
)
  [ -n "$XY" ] && adb shell input tap $XY
}
tap_text() {
  dismiss_anr
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null
  adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null
  XY=$(python3 - "$1" <<'PY'
import sys,re,xml.etree.ElementTree as ET
t=sys.argv[1]
for n in ET.parse('/tmp/ui.xml').iter('node'):
    if n.get('text')==t and n.get('class','').endswith('TextView'):
        m=re.findall(r'\d+',n.get('bounds'));x1,y1,x2,y2=map(int,m)
        print((x1+x2)//2,(y1+y2)//2);break
PY
)
  [ -n "$XY" ] && adb shell input tap $XY
}

set_theme() { # name text bg primary
  adb shell am force-stop $PKG
  python3 - "$2" "$3" "$4" > /tmp/Prefs.xml <<'PY'
import sys
def s(h):
    v=int(h,16)|0xFF000000
    return v-(1<<32) if v>=(1<<31) else v
t,b,p=[s(a) for a in sys.argv[1:4]]
print('<?xml version="1.0" encoding="utf-8" standalone="yes" ?>\n<map>')
for k,v in (('text_color',t),('background_color',b),('primary_color_2',p),('accent_color',p)):
    print(f'<int name="{k}" value="{v}" />')
print('<boolean name="is_using_system_theme" value="false" />\n<boolean name="is_global_theme_enabled" value="false" />\n</map>')
PY
  adb push /tmp/Prefs.xml /data/local/tmp/Prefs.xml
  adb shell "run-as $PKG sh -c 'mkdir -p shared_prefs && cp /data/local/tmp/Prefs.xml shared_prefs/Prefs.xml'"
}

shoot_theme() {
  NAME=$1
  # Fossify swaps launcher aliases when app_icon_color changes, which closes the app once. Launch, wait, relaunch via the resolved alias.
  adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/org.fossify.gallery.activities.SplashActivity.Green
  sleep 12
  COMP=$(adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER $PKG | tail -1 | tr -d '\r')
  echo "resolved launcher: $COMP"
  case "$COMP" in */*) adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n "$COMP";; *) adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/org.fossify.gallery.activities.SplashActivity.Green;; esac
  sleep 10
  adb logcat -d -t 400 > shots/${NAME}_log.txt || true
  adb shell dumpsys activity activities | grep -E 'mResumedActivity|topResumed' > shots/${NAME}_top.txt || true
  adb shell run-as $PKG ls -la shared_prefs > shots/${NAME}_prefs.txt 2>&1 || true
  adb exec-out screencap -p > shots/${NAME}_1_start.png
  tap_text Pictures; sleep 4; adb exec-out screencap -p > shots/${NAME}_2_pictures.png
  tap_text People; sleep 4; adb exec-out screencap -p > shots/${NAME}_3_people.png
  tap_text Albums; sleep 4; adb exec-out screencap -p > shots/${NAME}_4_albums.png
  tap_text Family; sleep 4; adb exec-out screencap -p > shots/${NAME}_5_folder.png
  adb shell input tap 180 420; sleep 4; adb exec-out screencap -p > shots/${NAME}_6_viewer.png
  adb shell input keyevent KEYCODE_BACK; sleep 3; adb exec-out screencap -p > shots/${NAME}_7_back_to_folder.png
  adb shell input keyevent KEYCODE_BACK; sleep 3; adb exec-out screencap -p > shots/${NAME}_8_back_to_albums.png
  tap_text Pictures; sleep 4
  adb shell input tap 400 300; sleep 8; adb exec-out screencap -p > shots/${NAME}_9_pictures_viewer.png
  adb shell dumpsys activity activities | grep -E 'topResumedActivity' > shots/${NAME}_top_pv.txt || true
  adb shell input keyevent KEYCODE_BACK; sleep 3; adb exec-out screencap -p > shots/${NAME}_10_pictures_back.png
  adb shell dumpsys activity activities | grep -E 'topResumedActivity' > shots/${NAME}_top_end.txt || true
  adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/org.fossify.gallery.activities.SplashActivity.Green >/dev/null; sleep 6
  tap_text People; sleep 3
  tap_text "Scan photos"; sleep 45; adb exec-out screencap -p > shots/${NAME}_11_people_scan.png
  tap_text "Person 1"; sleep 5; adb exec-out screencap -p > shots/${NAME}_12_person.png
  adb logcat -d -s FaceScan:I > shots/${NAME}_facescan.txt || true
  adb logcat -d -s AndroidRuntime:E > shots/${NAME}_rt.txt || true
  adb logcat -d -b crash > shots/${NAME}_crash.txt || true
  adb logcat -d -s ActivityTaskManager:I ActivityManager:I AndroidRuntime:E DEBUG:F libc:F > shots/${NAME}_am.txt || true
}

set_theme darkred FFFFFF 121212 B71C1C
shoot_theme darkred
set_theme light 202020 FFFFFF B71C1C
shoot_theme light
