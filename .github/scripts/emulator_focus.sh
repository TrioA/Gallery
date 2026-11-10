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
tap_text "Link contact"; sleep 1; adb logcat -d -s PersonLink:W > shots/personlink_launch.txt; sleep 3; shot 09_picker
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
need "restored: People screen shows same people and faces" 12 has "2 people, 10 faces"
adb shell input tap 160 650; sleep 4
soft "restored: contact link still there" 12b has "linked to contact Test_Person"
echo "NOTE: scanner behaviour with revoked access is not reachable from the UI (People needs the app open); checked by launch + DB counts only" >> $R

facehash() { python3 - shots/db_$1/people.db <<'PY'
import sqlite3,sys,hashlib
c=sqlite3.connect(sys.argv[1])
rows=c.execute("select id,path,box_l,box_t from faces order by id").fetchall()
print(len(rows),hashlib.md5(repr(rows).encode()).hexdigest())
PY
}
orphans() { python3 - shots/db_$1/people.db <<'PY'
import sqlite3,sys
c=sqlite3.connect(sys.argv[1])
print(c.execute("select count(*) from faces f left join people p on p.id=f.person_id where p.id is null or p.face_count=0").fetchone()[0])
PY
}
# 6b. bulk merge: long-press one group, tap another, merge; every face kept, all marked confirmed
open_people; adb shell am force-stop $PKG; open_people
adb shell input swipe 160 650 160 650 900; sleep 2; shot 12m_selected
soft "merge: selection bar shows after long-press" 12m has "Merge 1 into one"
adb shell input tap 540 650; sleep 2; shot 12n_two_selected
soft "merge: two groups selected" 12n has "Merge 2 into one"
tap_text "Merge 2 into one"; sleep 2; shot 12o_confirm
tap_text Merge; sleep 3; shot 12p_merged
soft "merge: one person left, all faces kept" 12p has "1 people, 10 faces"
adb shell am force-stop $PKG; sleep 2
dbsnap after_merge > shots/db_merge.txt; cat shots/db_merge.txt >> $R
soft "merge: same face ids, photos and boxes before and after (hash)" 12r test "$(facehash before_revoke)" = "$(facehash after_merge)"
soft "merge: no face points at an empty or missing group" 12s test "$(orphans after_merge)" = "0"
open_people; shot 12t_after_restart
soft "merge: still 1 person, 10 faces after restart" 12t has "1 people, 10 faces"
tap_text "Scan photos"; sleep 12; shot 12u_rescan
soft "merge: rescan keeps 1 person, 10 faces" 12u has "1 people, 10 faces"
adb shell am force-stop $PKG; sleep 2
dbsnap after_rescan > shots/db_rescan.txt; cat shots/db_rescan.txt >> $R
soft "merge: all faces still confirmed after rescan" 12v test "$(cut -d' ' -f1-3 shots/db_rescan.txt)" = "10 1 10"
soft "merge: DB shows 1 person, 10 faces, all confirmed" 12q test "$(cut -d' ' -f1-3 shots/db_merge.txt)" = "10 1 10"

# 7. originals untouched
adb shell 'md5sum /sdcard/Pictures/People/*.jpg' > shots/md5_after.txt
need "originals byte-identical after all of the above" 13 diff -q shots/md5_before.txt shots/md5_after.txt
adb logcat -d -s AndroidRuntime:E > shots/rt.txt
need "no crash in logcat" 14 test ! -s shots/rt.txt

# 8. upgrade from a populated v1 database (the schema the delivered 1015/1022 builds use)
adb shell am force-stop $PKG
adb shell 'rm -f /sdcard/Pictures/People/*.jpg'
adb shell content delete --uri content://media/external/images/media --where "_data like '%/Pictures/People/%'" >/dev/null 2>&1
python3 - <<'PY'
import sqlite3,os,struct,random
random.seed(7)
os.makedirs('shots',exist_ok=True)
f='shots/v1_fixture.db'
if os.path.exists(f): os.remove(f)
c=sqlite3.connect(f)
c.executescript("""
CREATE TABLE people (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL DEFAULT '', contact_key TEXT NOT NULL DEFAULT '', contact_name TEXT NOT NULL DEFAULT '', centroid BLOB, face_count INTEGER NOT NULL DEFAULT 0);
CREATE TABLE faces (id INTEGER PRIMARY KEY AUTOINCREMENT, path TEXT NOT NULL, box_l REAL, box_t REAL, box_r REAL, box_b REAL, emb BLOB NOT NULL, person_id INTEGER NOT NULL, confirmed INTEGER NOT NULL DEFAULT 0, thumb BLOB);
CREATE INDEX faces_person ON faces(person_id);
CREATE INDEX faces_path ON faces(path);
CREATE TABLE scanned (path TEXT PRIMARY KEY, mtime INTEGER NOT NULL, faces INTEGER NOT NULL);
""")
def vec(seed):
    r=random.Random(seed); v=[r.gauss(0,1) for _ in range(512)]; n=sum(x*x for x in v)**.5
    return struct.pack('<512f',*[x/n for x in v])
# id, name, contact_key, contact_name, faces: (confirmed list)
people=[(1,'Sam','','',[1,1,0]),(2,'Sam','','',[1,0]),(3,'Alex','K1','Alex',[1,0]),(4,'Al','K1','Al',[0,1]),(5,'','K2','Bo',[1]),(6,'','K3','Cy',[0]),(7,'','','',[0])]
fid=0
for pid,name,ck,cn,conf in people:
    c.execute("insert into people(id,name,contact_key,contact_name,centroid,face_count) values(?,?,?,?,?,?)",(pid,name,ck,cn,vec(pid),len(conf)))
    for i,cf in enumerate(conf):
        fid+=1
        c.execute("insert into faces(id,path,box_l,box_t,box_r,box_b,emb,person_id,confirmed) values(?,?,?,?,?,?,?,?,?)",(fid,f'/sdcard/Pictures/Old/p{pid}_{i}.jpg',.1,.1,.4,.4,vec(pid*100+i),pid,cf))
        c.execute("insert into scanned values(?,?,1)",(f'/sdcard/Pictures/Old/p{pid}_{i}.jpg',1000+fid))
c.execute("PRAGMA user_version=1"); c.commit()
print(c.execute("select count(*) from faces").fetchone())
PY
cp shots/v1_fixture.db shots/v1_fixture_untouched_copy.db
adb push shots/v1_fixture.db /data/local/tmp/people.db >/dev/null
adb shell "run-as $PKG sh -c 'mkdir -p databases; cp /data/local/tmp/people.db databases/people.db; rm -f databases/people.db-wal databases/people.db-shm'"
dbsnap v1fixture > shots/db_v1.txt; cat shots/db_v1.txt >> $R
need "upgrade test: v1 fixture installed in app (12 faces, 7 people)" 20 test "$(cut -d' ' -f1-2 shots/db_v1.txt)" = "12 7"
open_people; sleep 3; shot 20_upgraded_people
adb shell am force-stop $PKG; sleep 2
dbsnap upgraded > shots/db_up.txt; cat shots/db_up.txt >> $R
python3 - shots/db_upgraded/people.db shots/v1_fixture.db >> $R <<'PY'
import sqlite3,sys
n=sqlite3.connect(sys.argv[1]); o=sqlite3.connect(sys.argv[2])
chk=lambda d,m: print(("PASS: " if d else "FAIL: ")+m)
chk(n.execute("pragma user_version").fetchone()[0]==2,"upgrade: schema version is 2")
cols=[r[1] for r in n.execute("pragma table_info(people)")]
chk('aliases' in cols and 'merged_into' in cols,"upgrade: new columns present")
of={r[0]:(r[1],r[2]) for r in o.execute("select id,path,person_id from faces")}
nf={r[0]:(r[1],r[2],r[3]) for r in n.execute("select id,path,person_id,confirmed from faces")}
chk(set(of)==set(nf) and all(of[i][0]==nf[i][0] for i in of),"upgrade: every original face id and photo path survived")
oc={r[0] for r in o.execute("select id from faces where confirmed=1")}
chk(all(nf[i][2]==1 for i in oc),"upgrade: faces the user confirmed are still confirmed")
chk(n.execute("select count(*) from scanned").fetchone()[0]==o.execute("select count(*) from scanned").fetchone()[0],"upgrade: scan records survived")
sam=n.execute("select id,face_count from people where name='Sam' and face_count>0 order by id").fetchall()
chk(len(sam)==2,"equal names stay separate: two groups named Sam remain (faces %s)"%[x[1] for x in sam])
k1=n.execute("select id,name,aliases,face_count from people where contact_key='K1' and face_count>0").fetchall()
chk(len(k1)==1 and k1[0][3]==4,"same contact K1: consolidated to one group with all 4 faces: %s"%(k1,))
chk(len(k1)==1 and ('Al' in (k1[0][1]+'|'+k1[0][2]).split('|')) and ('Alex' in (k1[0][1]+'|'+k1[0][2]).split('|')),"same contact K1: both names kept (name or alias)")
chk(n.execute("select count(*) from people where contact_key in ('K2','K3') and face_count>0").fetchone()[0]==2,"distinct contacts K2 and K3 stay separate")
chk(n.execute("select count(*) from faces f left join people p on p.id=f.person_id where p.id is null or p.face_count=0").fetchone()[0]==0,"no orphan faces after upgrade and consolidation")
chk(n.execute("select count(*) from people where face_count>0").fetchone()[0]==6,"6 groups remain (7 minus one duplicate-contact merge)")
PY
open_people; sleep 3; shot 20c_people_after_restart
soft "upgrade: still 6 people, 12 faces after restart" 20c has "6 people, 12 faces"
press_text() { dump; XY=$(python3 - "$1" <<'PY'
import sys,re,xml.etree.ElementTree as ET
for n in ET.parse('/tmp/ui.xml').iter('node'):
    if (n.get('text') or '')==sys.argv[1]:
        m=re.findall(r'\d+',n.get('bounds'));x1,y1,x2,y2=map(int,m);print((x1+x2)//2,(y1+y2)//2);break
PY
); [ -n "$XY" ] && adb shell input swipe $XY $XY 900; }
press_text "Bo"; sleep 2
tap_text "Cy"; sleep 2; tap_text "Merge 2 into one"; sleep 2; shot 20d_conflict
soft "distinct contacts: merge refused with an explanation" 20d has "different contacts"
tap_text OK; sleep 2
adb shell am force-stop $PKG; sleep 2
dbsnap conflict > shots/db_conflict.txt; cat shots/db_conflict.txt >> $R
soft "distinct contacts: nothing changed after the refusal" 20e test "$(cat shots/db_up.txt)" = "$(cat shots/db_conflict.txt)"
adb logcat -d -s AndroidRuntime:E > shots/rt2.txt
need "no crash in logcat during upgrade test" 21 test ! -s shots/rt2.txt
echo ALL DONE >> $R
