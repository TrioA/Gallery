#!/usr/bin/env bash
# Focused checks on a fresh install. Each step asserts the screen/state it expects and records
# PASS/FAIL in shots/results.txt; the first failed prerequisite stops the run (no blind tapping).
set -x
mkdir -p shots
R=shots/results.txt; : > $R
APK=$(ls apk/*debug.apk | head -1)
PKG=org.fossify.gallery.debug
python3 - <<'PY' >> $R 2>&1 || { echo "FAIL: fixture validation" >> $R; exit 0; }
from PIL import Image
d=lambda f: Image.open(f).size
a1,a2,a3,a4,al,m,mf,t=[d(f) for f in ['faces/armstrong_1.jpg','faces/armstrong_2.jpg','faces/armstrong_3.jpg','faces/armstrong_4.jpg','faces/aldrin_1.jpg','focus/multi.jpg','focus/multi_full.jpg','focus/tiny.jpg']]
assert a2[0] < a1[0] and a2[1] < a1[1], ("armstrong_2 must be a real downscale", a1, a2)
import hashlib
h=lambda f: hashlib.md5(open(f,"rb").read()).hexdigest()
assert len({h(f) for f in ["faces/armstrong_1.jpg","faces/armstrong_3.jpg","faces/armstrong_4.jpg","faces/aldrin_1.jpg"]}) == 4, "portraits must be 4 different files"
assert m == (1200, 600), ("multi must be two head crops side by side", m)
assert t == (1600, 1200), ("tiny canvas", t)
assert mf[0] > mf[1] and mf[0] > a1[0], ("multi_full must be two FULL portraits side by side", mf)
print("PASS: fixtures valid", dict(mf=mf,a1=a1,a2=a2,a3=a3,a4=a4,aldrin=al,multi=m,tiny=t))
PY
adb install -r "$APK"
adb shell mkdir -p /sdcard/Pictures/People
for f in faces/armstrong_1.jpg faces/armstrong_2.jpg faces/armstrong_3.jpg faces/armstrong_4.jpg faces/aldrin_1.jpg focus/multi.jpg focus/multi_full.jpg focus/tiny.jpg focus/limit_small.jpg focus/limit_rot90.jpg; do
  adb push $f /sdcard/Pictures/People/ >/dev/null
  adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d "file:///sdcard/Pictures/People/$(basename $f)" >/dev/null
done
for p in android.permission.READ_MEDIA_IMAGES android.permission.READ_MEDIA_VIDEO android.permission.POST_NOTIFICATIONS; do adb shell pm grant $PKG $p; done
adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
adb shell 'md5sum /sdcard/Pictures/People/*.jpg' > shots/md5_before.txt
sleep 4

dump() { adb shell uiautomator dump /sdcard/ui.xml >/dev/null; adb pull /sdcard/ui.xml /tmp/ui.xml >/dev/null; }
shot() { adb exec-out screencap -p > shots/$1.png; }
top() { adb shell dumpsys activity activities | grep -E 'topResumedActivity' | head -1; }
has() { dump; python3 - "$1" <<'PY'
import sys,xml.etree.ElementTree as ET
t=sys.argv[1]
sys.exit(0 if any(t in (n.get('text') or '') for n in ET.parse('/tmp/ui.xml').iter('node')) else 1)
PY
}
count_text() { dump; python3 - "$1" <<'PY'
import sys,xml.etree.ElementTree as ET
t=sys.argv[1]
print(sum(1 for n in ET.parse('/tmp/ui.xml').iter('node') if t in (n.get('text') or '')))
PY
}
tap_text() { dump; XY=$(python3 - "$1" <<'PY'
import sys,re,xml.etree.ElementTree as ET
t=sys.argv[1]
for n in ET.parse('/tmp/ui.xml').iter('node'):
    if (n.get('text') or '')==t:
        m=re.findall(r'\d+',n.get('bounds'));x1,y1,x2,y2=map(int,m)
        print((x1+x2)//2,(y1+y2)//2);break
PY
); [ -n "$XY" ] && adb shell input tap $XY; }
top_has() { top | grep -qi "$1"; }
lacks() { ! has "$1"; }
pass() { echo "PASS: $1" >> $R; }
fail() { echo "FAIL: $1" >> $R; shot "fail_$2"; echo "STOPPED at: $1" >> $R; exit 0; }
need() { # description, tag, command...
  local d="$1" tag="$2"; shift 2
  if "$@"; then pass "$d"; else fail "$d" "$tag"; fi
}
soft() { # like need, but a failure is recorded and later independent checks still run
  local d="$1" tag="$2"; shift 2
  if "$@"; then pass "$d"; else echo "FAIL: $d (independent checks continue)" >> $R; shot "fail_$tag"; fi
}
launch() { adb shell am force-stop $PKG; adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/org.fossify.gallery.activities.SplashActivity.Green >/dev/null; sleep 6; }
open_people() { launch; tap_text People; sleep 3; }
open_photo() { # file name
  ID=$(adb shell content query --uri content://media/external/images/media --projection _id:_display_name | grep "_display_name=$1" | head -1 | sed 's/.*_id=\([0-9]*\).*/\1/')
  adb shell am start -n $PKG/org.fossify.gallery.activities.ViewPagerActivity -a android.intent.action.VIEW -t image/jpeg -d content://media/external/images/media/$ID >/dev/null; sleep 5
}
sheet() { adb shell input swipe 540 1700 540 600 150; sleep 3; }
wait_scan() { for i in $(seq 1 60); do sleep 2; dump; if python3 - <<'PY'
import sys,xml.etree.ElementTree as ET
ts=[(n.get('text') or '') for n in ET.parse('/tmp/ui.xml').iter('node')]
ok=any('faces in' in t for t in ts) and not any(t.startswith('Scanning') for t in ts)
sys.exit(0 if ok else 1)
PY
then return 0; fi; done; return 1; }

# 1. first scan (manual)
open_people
need "People screen open" 01 has "Scan photos"
tap_text "Scan photos"
need "scan finished" 02 wait_scan
shot 02_scanned
open_photo limit_small.jpg; sheet; shot 03a_limit_small
has "No faces found yet" && echo "KNOWN LIMIT (expected miss, as before): 160px face in 1600x1200 not detected" >> $R || echo "LIMIT CHANGED: small-face fixture now detected" >> $R
open_photo limit_rot90.jpg; sheet; shot 03b_limit_rot90
has "No faces found yet" && echo "KNOWN LIMIT (expected miss, as before): 90-degree rotated face not detected" >> $R || echo "LIMIT CHANGED: rotated fixture now detected" >> $R
launch; tap_text People; sleep 3
need "2 people, 9 faces after scan (Armstrong x4 files incl. a real downscale, Aldrin, 2 in multi.jpg head crops, 2 in multi_full.jpg full portraits via tiled detection; tiny.jpg none)" 03 has "2 people, 9 faces"

# 2. multi-person photo
open_photo multi.jpg; sheet; shot 04_multi_sheet
need "multi-person photo lists 2 faces" 04 test "$(count_text 'Guessed, not confirmed')" = 2

# 2b. regression: original full-portrait group photo (whole-frame detector alone misses both faces)
open_photo multi_full.jpg; sheet; shot 04b_multifull_sheet
need "full-portrait group photo lists 2 faces (needs tiled detection)" 04b test "$(count_text 'Guessed, not confirmed')" = 2

# 3. confirm one guess
open_photo multi.jpg; sheet
tap_text Correct; sleep 2; shot 05_confirmed
need "Correct marks a face as Confirmed by you" 05 has "Confirmed by you"
need "other face still a guess" 05 has "Guessed, not confirmed"

# 4. missed face: tiny photo, detector misses, user taps
open_photo tiny.jpg; sheet; shot 06_tiny_sheet
need "tiny photo has no detected face" 06 has "No faces found yet"
tap_text "Mark a missed face"; sleep 3
need "mark screen open" 07 top_has MarkFaceActivity
adb shell input tap 540 1160; sleep 10; shot 07_after_mark
open_photo tiny.jpg; sheet; shot 08_tiny_after
need "manually marked face now listed" 08 lacks "No faces found yet"
adb shell am force-stop $PKG; open_photo tiny.jpg; sheet
need "marked face persists after restart" 08b lacks "No faces found yet"

# 5. contact link
adb shell content insert --uri content://com.android.contacts/raw_contacts --bind account_type:n: --bind account_name:n:
adb shell content insert --uri content://com.android.contacts/data --bind raw_contact_id:i:1 --bind mimetype:s:vnd.android.cursor.item/name --bind data1:s:Test_Person
adb shell content query --uri content://com.android.contacts/contacts --projection display_name > shots/contacts_list.txt
open_people; adb shell input tap 160 650; sleep 4
need "person page open" 09 has "Link contact"
tap_text "Link contact"; sleep 4; shot 09_picker
soft "contact picker opened" 09b top_has contacts
soft "synthetic contact present in picker" 09c has "Test_Person"
tap_text Test_Person; sleep 4; shot 10_linked; adb logcat -d -s PersonLink:W > shots/personlink.txt
soft "person page shows linked contact" 10 has "linked to contact Test_Person"
open_people; adb shell input tap 160 650; sleep 4
soft "contact link persists after restart" 10b has "linked to contact Test_Person"

# 6. permission revoked then restored: stored people must be untouched (checked in the DB itself)
dbsnap() { mkdir -p shots/db_$1; for f in people.db people.db-wal people.db-shm; do adb exec-out run-as $PKG cat databases/$f > shots/db_$1/$f 2>/dev/null; done
  python3 - shots/db_$1/people.db <<'PY'
import sqlite3,sys
c=sqlite3.connect(sys.argv[1])
q=lambda s:c.execute(s).fetchone()[0]
print(q("select count(*) from faces"),q("select count(*) from people where face_count>0"),q("select count(*) from faces where confirmed=1"),q("select count(*) from people where contact_name<>''"),q("select count(*) from scanned"))
PY
}
need_db_equal() { [ "$(dbsnap $1)" = "$(dbsnap $2)" ]; }
adb shell am force-stop $PKG; sleep 2
dbsnap before_revoke > shots/db_before.txt; cat shots/db_before.txt >> $R
adb shell pm revoke $PKG android.permission.READ_MEDIA_IMAGES
adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE deny
adb shell am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -n $PKG/org.fossify.gallery.activities.SplashActivity.Green >/dev/null; sleep 8; shot 11_revoked_launch
need "revoked: app process still alive (no crash on launch)" 11 sh -c "adb shell pidof $PKG | grep -q ."
adb shell am force-stop $PKG; sleep 2
dbsnap during_revoke > shots/db_revoked.txt
need "revoked: stored faces/people/confirmed/contacts/scanned counts unchanged" 11b test "$(cat shots/db_before.txt)" = "$(cat shots/db_revoked.txt)"
adb shell pm grant $PKG android.permission.READ_MEDIA_IMAGES
adb shell appops set --uid $PKG MANAGE_EXTERNAL_STORAGE allow
open_people; sleep 5; shot 12_restored_people
need "restored: People screen shows same people and faces" 12 has "2 people, 9 faces"
adb shell input tap 160 650; sleep 4
soft "restored: contact link still there" 12b has "linked to contact Test_Person"
echo "NOTE: scanner behaviour with revoked access is not reachable from the UI (People needs the app open); checked by launch + DB counts only" >> $R

# 7. originals untouched
adb shell 'md5sum /sdcard/Pictures/People/*.jpg' > shots/md5_after.txt
need "originals byte-identical after all of the above" 13 diff -q shots/md5_before.txt shots/md5_after.txt
adb logcat -d -s AndroidRuntime:E > shots/rt.txt
need "no crash in logcat" 14 test ! -s shots/rt.txt
echo ALL DONE >> $R
