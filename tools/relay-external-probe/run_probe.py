#!/usr/bin/env python3
"""Outside-module Kotlin proof for the Prompt provenance boundary (Important I1).

The probe sources in this directory form a DIFFERENT Kotlin module (-module-name relay-consumer-probe) compiled
against a protocol jar and the recorded relay-v1 vectors, using the offline Kotlin compiler already in the owned
Gradle cache. Kotlin enforces `internal` across modules, so the same valid probe must compile against the old jar
(public constructor) and fail with the visibility diagnostic against the fixed jar. Nothing is downloaded or installed.

usage: run_probe.py --jar PROTOCOL.jar --out OUTDIR --gradle-home DIR --java JAVA --vectors relay-v1-dir
Exit 0 only when every expectation of the given jar's `--expect old|new` holds.
"""
import argparse, glob, json, os, re, subprocess, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ap = argparse.ArgumentParser()
ap.add_argument('--jar', required=True)
ap.add_argument('--out', required=True)
ap.add_argument('--gradle-home', required=True)
ap.add_argument('--java', required=True)
ap.add_argument('--vectors', required=True)
ap.add_argument('--expect', choices=['old', 'new'], required=True)
a = ap.parse_args()
OUT = Path(a.out)
OUT.mkdir(parents=True, exist_ok=False)
FILES = Path(a.gradle_home) / 'caches/modules-2/files-2.1'


def one(pattern):
    found = sorted(glob.glob(str(FILES / pattern)))
    assert len(found) == 1, (pattern, found)
    return found[0]


compiler_cp = [one('org.jetbrains.kotlin/kotlin-compiler-embeddable/2.3.10/*/kotlin-compiler-embeddable-2.3.10.jar'),
               one('org.jetbrains.kotlin/kotlin-stdlib/2.3.10/*/kotlin-stdlib-2.3.10.jar'),
               one('org.jetbrains.kotlin/kotlin-script-runtime/2.3.10/*/kotlin-script-runtime-2.3.10.jar'),
               one('org.jetbrains.kotlin/kotlin-reflect/1.6.10/*/kotlin-reflect-1.6.10.jar'),
               one('org.jetbrains.kotlin/kotlin-daemon-embeddable/2.3.10/*/kotlin-daemon-embeddable-2.3.10.jar'),
               one('org.jetbrains.kotlinx/kotlinx-coroutines-core-jvm/1.10.2/*/kotlinx-coroutines-core-jvm-1.10.2.jar'),
               one('org.jetbrains/annotations/13.0/*/annotations-13.0.jar')]
lib_cp = [str(Path(a.jar).resolve()),
          one('org.jetbrains.kotlin/kotlin-stdlib/2.3.10/*/kotlin-stdlib-2.3.10.jar'),
          one('org.jetbrains.kotlinx/kotlinx-serialization-json-jvm/1.9.0/*/kotlinx-serialization-json-jvm-1.9.0.jar'),
          one('org.jetbrains.kotlinx/kotlinx-serialization-core-jvm/1.9.0/*/kotlinx-serialization-core-jvm-1.9.0.jar')]

# ---- Frames.kt from the RECORDED vectors (verbatim wire text; nothing hand-built)
vectors = Path(a.vectors)


def cases(name):
    return json.loads((vectors / name).read_text())['cases']


def wire(response):
    return json.dumps(response, separators=(',', ':'))


def pick_task(predicate):
    for case in cases('relay-task-get.json'):
        r = case['response'].get('result')
        if r and r.get('prompt') and predicate(r['prompt']):
            return case
    raise SystemExit('no recorded task.get case')


fetched = pick_task(lambda p: p['actionable'] and p['reason'] is None and p['text'] is not None)
notice = pick_task(lambda p: p['reason'] == 'too_large')
event = None
for case in cases('relay-events-read.json'):
    r = case['response'].get('result')
    if r and any(e['kind'] == 'prompt' and e['payload']['actionable'] and e['payload']['reason'] is None for e in r['events']):
        event = case
        break
assert event, 'no recorded actionable event prompt'
consts = {'FETCHED': wire(fetched['response']), 'FETCHED_CORRELATION': fetched['response']['id'], 'FETCHED_TASK_ID': fetched['request']['params']['task_id'],
          'NOTICE': wire(notice['response']), 'NOTICE_CORRELATION': notice['response']['id'], 'NOTICE_TASK_ID': notice['request']['params']['task_id'],
          'EVENT': wire(event['response']), 'EVENT_CORRELATION': event['response']['id']}
lines = ['package probe', '', '/** Generated from the recorded relay-v1 vectors by run_probe.py. */', 'object Frames {']
for key, value in consts.items():
    assert '"""' not in value and '$' not in value
    lines.append('    const val %s = """%s"""' % (key, value))
lines.append('}')
(OUT / 'Frames.kt').write_text('\n'.join(lines) + '\n')
(OUT / 'frames-provenance.json').write_text(json.dumps({'fetched': fetched['id'], 'notice': notice['id'], 'event': event['id'],
                                                      'sources': [fetched['source'], notice['source'], event['source']]}, indent=1) + '\n')


def run(argv, log):
    p = subprocess.run(argv, capture_output=True, text=True)
    noise = lambda s: ''.join(l + '\n' for l in s.splitlines() if 'clocale' not in l)
    Path(log).write_text('$ ' + ' '.join(argv) + '\n--- stdout ---\n' + noise(p.stdout) + '--- stderr ---\n' + noise(p.stderr) + '--- exit %d ---\n' % p.returncode)
    return p.returncode, noise(p.stdout), noise(p.stderr)


def compile_probe(name, main_file):
    classes = OUT / ('classes-' + name)
    argv = [a.java, '-cp', os.pathsep.join(compiler_cp), 'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler', '-no-stdlib', '-no-reflect', '-module-name', 'relay-consumer-probe',
            '-jvm-target', '17', '-cp', os.pathsep.join(lib_cp), '-d', str(classes), str(HERE / 'Real.kt'), str(OUT / 'Frames.kt'), str(HERE / main_file)]
    code, out, err = run(argv, OUT / (name + '.compile.log'))
    return code, out + err, classes


def run_main(name, classes, main_class):
    return run([a.java, '-cp', os.pathsep.join([str(classes)] + lib_cp), main_class], OUT / (name + '.run.log'))


results = {}
ok = True


def expect(label, condition, detail=''):
    global ok
    results[label] = {'ok': bool(condition), 'detail': detail}
    ok &= bool(condition)


VIS = re.compile(r"error:.*(Cannot access|Cannot call|Cannot resolve|Unresolved|invisible)", re.I)
# positive control: must compile and pass for both jars
code, text, classes = compile_probe('positive', 'Positive.kt')
expect('positive compiles', code == 0 and 'error:' not in text, text[-300:])
if code == 0:
    rc, out, err = run_main('positive', classes, 'probe.PositiveKt')
    expect('positive runs: fetched actionable, event and notice refused', rc == 0 and out.count('POSITIVE') == 3, out + err)

# the same valid probe: the exact reconstruction
code, text, classes = compile_probe('fabricate', 'FabricateEventAsFetched.kt')
errors = [l for l in text.splitlines() if 'error:' in l]
if a.expect == 'old':
    expect('fabrication compiles against the old public constructor', code == 0 and not errors, text[-400:])
    if code == 0:
        rc, out, err = run_main('fabricate', classes, 'probe.FabricateEventAsFetchedKt')
        expect('fabrication RUNS: an EVENT prompt became actionable and reached respond()', rc == 0 and 'FABRICATED' in out, out + err)
else:
    constructor_errors = [l for l in errors if 'internal' in l.lower() and ('constructor' in l.lower() or "'Prompt'" in l)]
    expect('fabrication refused with the internal-visibility diagnostic only', code != 0 and errors and len(constructor_errors) == len(errors), '\n'.join(errors))

# controls: internal diagnostics exist for already-internal members in BOTH jars
for name, main_file in (('control-actionable', 'ControlActionablePrompt.kt'), ('control-decode', 'ControlDecode.kt')):
    code, text, _ = compile_probe(name, main_file)
    errors = [l for l in text.splitlines() if 'error:' in l]
    expect(name + ' refused as internal', code != 0 and errors and all('internal' in l.lower() for l in errors), '\n'.join(errors))

(OUT / 'results.json').write_text(json.dumps({'jar': str(a.jar), 'expect': a.expect, 'results': results, 'compiler_classpath': compiler_cp, 'library_classpath': lib_cp}, indent=1) + '\n')
print(json.dumps({k: v['ok'] for k, v in results.items()}, indent=1))
sys.exit(0 if ok else 1)
