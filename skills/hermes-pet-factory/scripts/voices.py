#!/usr/bin/env python3
"""Bounded ElevenLabs casting/design helpers. Mutations plan by default; never retry."""
import argparse
import base64
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.error
import urllib.parse
import urllib.request
import uuid

API='https://api.elevenlabs.io'
CAP=16*1024*1024

class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,*args,**kwargs):
        raise RuntimeError('Redirect refused; request not retried')

def load(p):return json.loads(Path(p).read_text())
def dump(p,value):
    with Path(p).open('x') as f:json.dump(value,f,indent=2);f.write('\n')

def request(method,path,payload=None,authenticated=True):
    key=os.environ.get('ELEVENLABS_API_KEY') or os.environ.get('ELEVENLABS_API')
    if authenticated and not key:raise ValueError('Set ElevenLabs credentials privately in the environment; never paste a key in chat')
    headers={'Accept':'application/json'}
    if authenticated:headers['xi-api-key']=key
    body=None
    if payload is not None:body=json.dumps(payload).encode();headers['Content-Type']='application/json'
    req=urllib.request.Request(API+path,data=body,headers=headers,method=method)
    try:
        with urllib.request.build_opener(NoRedirect()).open(req,timeout=45) as response:
            data=response.read(CAP+1)
            if len(data)>CAP:raise ValueError('Response exceeds16MiB; not retried')
            return data
    except urllib.error.HTTPError as exc:
        raise RuntimeError(f'ElevenLabs HTTP {exc.code}; not retried') from None
    except urllib.error.URLError:
        raise RuntimeError('ElevenLabs network outcome uncertain; not retried') from None

def charge(ledger,calls,characters,approval,text):
    if not approval.strip():raise ValueError('Explicit paid approval required')
    if calls is None or characters is None or calls<1 or characters<1:raise ValueError('Approved --max-calls and --max-characters required')
    if not ledger:raise ValueError('--spend-ledger required for paid sends')
    p=Path(ledger);p.parent.mkdir(parents=True,exist_ok=True)
    # Exclusive lock prevents concurrent calls from both spending the same remaining budget.
    lock=p.with_name(p.name+'.lock')
    try:fd=os.open(lock,os.O_CREAT|os.O_EXCL|os.O_WRONLY,0o600)
    except FileExistsError:raise ValueError('Spend ledger locked; inspect prior outcome, never auto-retry')
    try:
        os.close(fd)
        rows=[json.loads(line) for line in p.read_text().splitlines() if line] if p.exists() else []
        attempts=[r for r in rows if r.get('state')=='attempted']
        if len(attempts)>=calls or sum(r['characters'] for r in attempts)+len(text)>characters:raise ValueError('Approved voice budget exhausted')
        event={'attempt_id':str(uuid.uuid4()),'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),
               'state':'attempted','characters':len(text),'approval':approval,'text_sha256':hashlib.sha256(text.encode()).hexdigest()}
        with p.open('a') as f:f.write(json.dumps(event)+'\n');f.flush();os.fsync(f.fileno())
        return event['attempt_id']
    finally:lock.unlink()

def finish(ledger,attempt,state):
    if ledger and attempt:
        with Path(ledger).open('a') as f:f.write(json.dumps({'attempt_id':attempt,'state':state,'at':datetime.datetime.now(datetime.timezone.utc).isoformat()})+'\n');f.flush();os.fsync(f.fileno())

def voice_id(value):
    if not re.fullmatch(r'[A-Za-z0-9_-]{1,256}',value or ''):raise ValueError('Invalid voice identifier')
    return value

def search(query,library=False,pages=1):
    if not 1<=pages<=10:raise ValueError('Use1..10 bounded pages')
    voices=[];cursor=None;more=False
    for page in range(pages):
        params={'search':query,'page':page,'page_size':20} if library else {'search':query,'page_size':20,'include_total_count':'false'}
        if cursor and not library:params['next_page_token']=cursor
        path=('/v1/shared-voices' if library else '/v2/voices')+'?'+urllib.parse.urlencode(params)
        data=json.loads(request('GET',path));more=bool(data.get('has_more'))
        for v in data.get('voices',[]):
            voices.append({k:v.get(k) for k in ['voice_id','name','description','category','labels','preview_url','public_owner_id','public_user_id']})
        cursor=data.get('next_page_token')
        if not more:break
        if not library and not cursor:break
    return {'voices':voices,'has_more':more,'next_page_token':cursor,'catalog_exhausted':not more,'synthesis_calls':0}

def mutation(command,payload,out,execute=False,approval='',ledger=None,max_calls=None,max_characters=None,preview_manifest=None):
    text='';path='';binary=False
    if command=='design':
        description=payload.get('voice_description','');text=payload.get('text','')
        if not 20<=len(description)<=1000 or not 100<=len(text)<=1000:raise ValueError('Design requires description20..1000 and explicit sample text100..1000 characters')
        if payload.get('auto_generate_text'):raise ValueError('Use explicit preview text so character spend is bounded')
        path='/v1/text-to-voice/design'
    elif command=='save':
        selected=voice_id(payload.get('generated_voice_id'))
        if not payload.get('voice_name') or not payload.get('voice_description'):raise ValueError('voice_name and voice_description required')
        if execute:
            if not preview_manifest or selected not in [x['generated_voice_id'] for x in load(preview_manifest)['previews']]:raise ValueError('Selected preview must belong to the saved design result')
        path='/v1/text-to-voice'
    elif command=='add-library':
        owner=voice_id(payload.get('public_user_id'));v=voice_id(payload.get('voice_id'))
        if not payload.get('new_name'):raise ValueError('new_name required')
        path=f'/v1/voices/add/{owner}/{v}';payload={'new_name':payload['new_name']}
    elif command=='tts':
        v=voice_id(payload.get('voice_id'));text=payload.get('text','')
        if not isinstance(text,str) or not 1<=len(text)<=2500:raise ValueError('Short explicit audition text1..2500 characters required')
        path=f'/v1/text-to-speech/{v}';payload={k:v for k,v in payload.items() if k!='voice_id'};binary=True
    else:raise ValueError('Unknown mutation')
    if execute and not approval.strip():raise ValueError('Explicit approval required before voice/account mutation')
    out=Path(out);out.mkdir(parents=True,exist_ok=False,mode=0o700)
    dump(out/'request-plan.json',{'method':'POST','url':API+path,'payload':payload,'execute':execute,'approval':approval if execute else None,'automatic_retries':0})
    if not execute:return {'planned':True,'path':str(out/'request-plan.json'),'no_api_call':True}
    attempt=None
    if command in ('design','tts'):attempt=charge(ledger,max_calls,max_characters,approval,text)
    try:
        raw=request('POST',path,payload)
        if binary:
            (out/'audition.mp3').write_bytes(raw);data={'audio_file':'audition.mp3','bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()}
        else:
            data=json.loads(raw)
            if command=='design':
                previews=[]
                for i,item in enumerate(data.get('previews',[])):
                    v=voice_id(item.get('generated_voice_id'));encoded=item.get('audio_base_64')
                    if not encoded:raise ValueError('Design returned no embedded preview audio; no automatic resend')
                    audio=base64.b64decode(encoded,validate=True)
                    if not 1<=len(audio)<=CAP:raise ValueError('Preview audio too large/empty')
                    name=f'candidate-{i+1}.mp3';(out/name).write_bytes(audio)
                    previews.append({'generated_voice_id':v,'audio_file':name,'sha256':hashlib.sha256(audio).hexdigest(),'media_type':item.get('media_type'),'duration_secs':item.get('duration_secs')})
                if not previews:raise ValueError('No voice previews returned')
                data={'previews':previews,'text':data.get('text'),'synthesis_attempt_id':attempt}
            elif not data.get('voice_id'):raise ValueError('No persistent voice_id returned; account outcome uncertain')
        dump(out/'result.json',data);finish(ledger,attempt,'response_observed')
        return {'executed':command,'result':str(out/'result.json'),'automatic_retries':0}
    except Exception:
        finish(ledger,attempt,'uncertain_or_failed_no_retry')
        raise

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('command',choices=['search','design','save','add-library','tts']);p.add_argument('--query',default='');p.add_argument('--library',action='store_true');p.add_argument('--pages',type=int,default=1);p.add_argument('--request',type=Path);p.add_argument('--out',type=Path);p.add_argument('--execute',action='store_true');p.add_argument('--approval',default='');p.add_argument('--spend-ledger',type=Path);p.add_argument('--max-calls',type=int);p.add_argument('--max-characters',type=int);p.add_argument('--preview-manifest',type=Path);a=p.parse_args()
    if a.command=='search':value=search(a.query,a.library,a.pages)
    else:
        if not a.request or not a.out:p.error('Mutations require --request and NEW --out directory; omit --execute to plan')
        value=mutation(a.command,load(a.request),a.out,a.execute,a.approval,a.spend_ledger,a.max_calls,a.max_characters,a.preview_manifest)
    print(json.dumps(value,indent=2))

if __name__=='__main__':
    try:main()
    except (ValueError,OSError,RuntimeError,KeyError) as e:raise SystemExit(str(e))
