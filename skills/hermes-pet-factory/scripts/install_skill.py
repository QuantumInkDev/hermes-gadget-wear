#!/usr/bin/env python3
"""Install only this skill into explicit/local skill roots; no profile configuration changes."""
import argparse
import datetime
import hashlib
import json
import os
from pathlib import Path
import shutil
import tarfile
import tempfile

SOURCE=Path(__file__).resolve().parents[1]
NAME='hermes-pet-factory'
EXCLUDED={'.venv','__pycache__','.git','.DS_Store'}

def source_files():
    return sorted(p for p in SOURCE.rglob('*') if p.is_file() and not any(x in EXCLUDED for x in p.relative_to(SOURCE).parts))

def discover(home):
    roots=[home/'.agents/skills',home/'.codex/skills',home/'.claude/skills',home/'.hermes/skills']
    profiles=home/'.hermes/profiles'
    if profiles.is_dir():roots.extend(p/'skills' for p in sorted(profiles.iterdir()) if p.is_dir() and (p/'config.yaml').is_file())
    return roots

def outside_source(destination):
    origin=SOURCE.stat()
    for ancestor in [Path(destination),*Path(destination).parents]:
        try:
            ancestor.lstat()
        except FileNotFoundError:
            continue
        actual=ancestor.stat()
        if (origin.st_dev,origin.st_ino)==(actual.st_dev,actual.st_ino):
            raise ValueError('Destination aliases or lies inside source skill')
        if not ancestor.is_dir() and ancestor!=Path(destination):
            raise ValueError('A destination ancestor is not a directory')

def install(roots,mode='link',apply=False):
    # Preflight the whole batch before writing any destination.
    plans=[];seen=set()
    for root in roots:
        root=Path(root).expanduser();dest=root/NAME
        identity=str(dest.resolve())
        if identity in seen:continue
        seen.add(identity)
        if dest.exists() or dest.is_symlink():
            if dest.exists() and dest.samefile(SOURCE):plans.append({'target':str(dest),'action':'already-same-skill'});continue
            raise ValueError(f'Refusing to replace existing skill: {dest}')
        outside_source(dest)
        plans.append({'target':str(dest),'action':mode})
    if apply:
        created=[]
        try:
            for plan in plans:
                if plan['action']=='already-same-skill':continue
                dest=Path(plan['target']);dest.parent.mkdir(parents=True,exist_ok=True)
                if mode=='link':dest.symlink_to(SOURCE,target_is_directory=True)
                else:
                    tmp=Path(tempfile.mkdtemp(prefix='.'+NAME+'-',dir=dest.parent))
                    try:
                        for source in source_files():
                            target=tmp/source.relative_to(SOURCE);target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
                        tmp.rename(dest)
                    except Exception:shutil.rmtree(tmp);raise
                created.append(dest)
                if (dest/'SKILL.md').read_bytes()!=(SOURCE/'SKILL.md').read_bytes():raise ValueError('Installed skill bytes mismatch')
                plan['verified']=True
        except Exception:
            for dest in reversed(created):
                if dest.is_symlink():dest.unlink()
                else:shutil.rmtree(dest)
            raise
    return {'applied':apply,'source':str(SOURCE),'targets':plans,'files':{str(p.relative_to(SOURCE)):hashlib.sha256(p.read_bytes()).hexdigest() for p in source_files()}}

def archive(path):
    path=Path(path);outside_source(path);path.parent.mkdir(parents=True,exist_ok=True)
    with path.open('xb') as raw:
        with tarfile.open(fileobj=raw,mode='w:gz') as out:
            for p in source_files():out.add(p,arcname=str(Path(NAME)/p.relative_to(SOURCE)),recursive=False)
    return {'archive':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'excluded':['dependencies/cache/personal runs']}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--root',type=Path,action='append',default=[]);p.add_argument('--discover-local',action='store_true');p.add_argument('--mode',choices=['link','copy'],default='copy' if os.name=='nt' else 'link');p.add_argument('--apply',action='store_true');p.add_argument('--archive',type=Path);p.add_argument('--receipt',type=Path);a=p.parse_args()
    roots=a.root+(discover(Path.home()) if a.discover_local else [])
    result=install(roots,a.mode,a.apply) if roots else {}
    if a.archive:result.update(archive(a.archive))
    if not result:p.error('Specify explicit --root, --discover-local or --archive')
    result['at']=datetime.datetime.now(datetime.timezone.utc).isoformat()
    if a.receipt:
        a.receipt.parent.mkdir(parents=True,exist_ok=True)
        with a.receipt.open('x') as f:json.dump(result,f,indent=2);f.write('\n')
    print(json.dumps({k:v for k,v in result.items() if k!='files'},indent=2))

if __name__=='__main__':
    try:main()
    except (ValueError,OSError) as e:raise SystemExit(str(e))
