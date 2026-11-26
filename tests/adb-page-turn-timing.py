#!/usr/bin/env python3
"""Opt-in, destructive-to-position reader smoke test. Requires an open reading page.

Usage: python3 tests/adb-page-turn-timing.py --serial SERIAL
Enable logs first: adb -s SERIAL shell setprop log.tag.ReaderTurnTiming DEBUG
Disable afterwards: adb -s SERIAL shell setprop log.tag.ReaderTurnTiming INFO
Inputs target a 1080x2340 display and advance the current book. No book content is logged.
"""
import argparse, shutil, subprocess,time,json,pathlib,re
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--serial', required=True)
parser.add_argument('--adb', default=shutil.which('adb'))
parser.add_argument('--output', default='/tmp/reader-phone-turn-smoke')
args = parser.parse_args()
if not args.adb:
    parser.error('adb not found; provide --adb')
ADB=args.adb
SERIAL=args.serial
PKG='com.yuezhou.reader.tts.test'
OUT=pathlib.Path(args.output);OUT.mkdir(exist_ok=True)
def adb(*args):
 return subprocess.run([ADB,'-s',SERIAL,*args],capture_output=True,text=True,check=True).stdout
def require_reader():
 focus=adb('shell','dumpsys','window')
 current=next((line for line in focus.splitlines() if 'mCurrentFocus=' in line), '')
 if PKG+'/com.yuezhou.reader.ReaderActivity' not in current:
  raise SystemExit('Not on reader screen; refusing test input')
def stage(name,cmd):
 require_reader()
 adb('shell','dumpsys','gfxinfo',PKG,'reset')
 before=adb('logcat','-d','-v','epoch','-s','ReaderTurnTiming:D','*:S').splitlines()
 start=time.monotonic(); output=adb('shell',cmd); elapsed=time.monotonic()-start
 time.sleep(1)
 after=adb('logcat','-d','-v','epoch','-s','ReaderTurnTiming:D','*:S').splitlines()
 (OUT/(name+'.timing.log')).write_text('\n'.join(after[len(before):]))
 raw=adb('shell','dumpsys','gfxinfo',PKG,'framestats');(OUT/(name+'.gfx.txt')).write_text(raw)
 keys=['Total frames rendered:','Janky frames:','50th percentile:','90th percentile:','95th percentile:','99th percentile:','Number Missed Vsync:','Number Slow UI thread:','Number Slow bitmap uploads:']
 lines=[l.strip() for l in raw.splitlines() if any(l.strip().startswith(k) for k in keys)]
 return dict(stage=name,input_wall_seconds=round(elapsed,3),metrics=lines)
require_reader()
pid=adb('shell','pidof',PKG).strip()
results=[]
results.append(stage('normal_taps','for i in 1 2 3; do input tap 920 1120; sleep 0.7; done'))
results.append(stage('rapid_taps','for i in 1 2 3 4 5 6; do input tap 920 1120; sleep 0.08; done'))
results.append(stage('swipes','input swipe 900 1120 220 1120 300; sleep 0.4; input swipe 220 1120 900 1120 300; sleep 0.4; input swipe 900 1120 220 1120 150; sleep 0.2; input swipe 220 1120 900 1120 150'))
logs=adb('logcat','-d','--pid='+pid,'-v','brief','-t','300')
(OUT/'filtered.log').write_text('\n'.join(l for l in logs.splitlines() if re.search(r'FATAL EXCEPTION|ANR|Choreographer|Skipped|TimeoutCancellation|chromium.*ERROR',l)))
(OUT/'summary.json').write_text(json.dumps(results,ensure_ascii=False,indent=2));print('timing and frame logs saved:',OUT);print('filtered_log_lines',len((OUT/'filtered.log').read_text().splitlines()))
