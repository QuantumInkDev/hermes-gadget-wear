#!/usr/bin/env python3
"""Private run scaffolding, deterministic packing, structural QA and approval hashes."""
import argparse
import base64
import datetime
import hashlib
import html
import io
import json
from pathlib import Path
import re
import shutil
from PIL import Image, ImageChops, ImageDraw

BASE = ['idle', 'running-right', 'running-left', 'waving', 'jumping', 'failed', 'waiting', 'running', 'review']
EXTRA = ['listening', 'talking', 'sleeping']
GATES = ['brief', 'look', 'motion', 'soul', 'voice', 'install']
W, H = 192, 208
Image.MAX_IMAGE_PIXELS = 3_200_000

def sha(p):
    return hashlib.sha256(Path(p).read_bytes()).hexdigest()

def read(p):
    return json.loads(Path(p).read_text())

def write(p, data):
    p = Path(p)
    p.parent.mkdir(parents=True, exist_ok=True)
    with p.open('x') as f:
        json.dump(data, f, indent=2)
        f.write('\n')

def inside(root, name):
    p = (root / name).resolve()
    if not p.is_relative_to(root.resolve()):
        raise ValueError('Artifact path escapes run or bundle')
    return p

def load_png(p, size=None, byte_cap=2*1024*1024):
    p = Path(p)
    if not 32 <= p.stat().st_size <= byte_cap:
        raise ValueError(f'PNG byte limit: {p.name}')
    with Image.open(p) as source:
        if source.format != 'PNG' or source.mode != 'RGBA':
            raise ValueError(f'Expected PNG RGBA: {p.name}')
        if source.width * source.height > 3_200_000 or (size and source.size != size):
            raise ValueError(f'PNG geometry: {p.name}')
        source.load()
        return source.copy()

def occupied(image):
    return image.getchannel('A').getbbox() is not None

def init_run(run, name, slug):
    if not re.fullmatch(r'[a-z0-9]+(?:-[a-z0-9]+)*', slug):
        raise ValueError('Use a lowercase hyphenated slug')
    run.mkdir(parents=True, exist_ok=False, mode=0o700)
    for d in ['references', 'portraits', 'frames', 'voice', 'previews', 'receipts']:
        (run/d).mkdir()
    for state in BASE+EXTRA:
        (run/'frames'/state).mkdir()
    brief = {'version':1, 'name':name, 'slug':slug, 'mode':'create', 'description':'',
             'appearance':'', 'protected_details':[], 'targets':[], 'soul_mode':None,
             'voice_mode':None, 'voice_direction':None, 'image_backend':None,
             'budgets':{'image_calls':None,'voice_calls':None,'voice_characters':None},
             'choreography':{s:None for s in BASE+EXTRA},
             'signature_slot':'waiting', 'mouth_box':None,
             'row_counts':{s:(3 if s=='talking' else 6) for s in BASE+EXTRA},
             'portrait_square':'portraits/square.png', 'portrait_round':'portraits/round.png',
             'gadget_base':None, 'display_mode':None}
    write(run/'brief.json', brief)
    write(run/'approvals.json', {'version':1,'gates':{}})
    return {'run':str(run), 'status':'INTAKE_REQUIRED', 'brief':str(run/'brief.json')}

def jobs(run):
    b = read(run/'brief.json')
    if not b['appearance'] or not b['description']:
        raise ValueError('Complete description and appearance before making image jobs')
    common = f"Crisp detailed pixel art. {b['appearance']}. Fixed identity/proportions/palette; preserve {b['protected_details']}. No text, watermark, X or labels."
    values = [{'id':'anchor','depends_on':[], 'reference_roles':[],
               'prompt':common+' Full-body canonical front-facing character, entire silhouette and props visible, genuine transparent background.'},
              {'id':'square-card','depends_on':['anchor'],'reference_roles':['approved anchor: identity'],
               'prompt':common+' Square 1024px portrait card, comfortable head/prop margins, same character.'},
              {'id':'round-card','depends_on':['anchor','square-card'],'reference_roles':['approved anchor: identity','square card: palette/composition'],
               'prompt':common+' Circular portrait on a square RGBA canvas, genuine transparent outside circle, all important features inside circle, same identity.'}]
    for s in BASE+EXTRA:
        count=b['row_counts'][s]
        if not b['choreography'].get(s):
            raise ValueError(f'Approve state choreography first: {s}')
        special = ' Three identical-body mouth amplitude poses in order CLOSED, HALF, OPEN; change only the approved mouth/anatomy region.' if s=='talking' else ''
        values.append({'id':s,'depends_on':['look-approved'],'reference_roles':['approved full-body anchor: identity/scale','approved palette: colors','matching existing row: protected update pixels if any'],
                       'prompt':common+f" {count} complete separated poses in temporal order for {s}: {b['choreography'][s]}. Shared scale/body root/baseline, transparent background; no independent per-pose fitting. "+special})
    write(run/'image-jobs.json', {'jobs':values,'signature_mapping':'waiting row6','note':'Prompts only; no image call or approval performed'})
    return {'jobs':len(values),'path':str(run/'image-jobs.json')}

def assemble(run):
    b=read(run/'brief.json'); out=run/'bundle'
    if out.exists(): raise ValueError('Bundle already exists; use a new revision/run')
    # Validate every input before creating output.
    frames={}
    for s in BASE+EXTRA:
        n=b['row_counts'][s]
        if type(n)!=int or not 1<=n<=6 or (s in EXTRA and n!=(3 if s=='talking' else 6)):
            raise ValueError(f'Invalid explicit frame count: {s}')
        paths=sorted((run/'frames'/s).glob('*.png'))
        if len(paths)!=n:raise ValueError(f'{s} needs exactly {n} registered PNG cells')
        frames[s]=[load_png(p,(W,H)) for p in paths]
        if not all(map(occupied,frames[s])):raise ValueError(f'Empty frame: {s}')
    square=load_png(inside(run,b['portrait_square']),byte_cap=8*1024*1024)
    circular=load_png(inside(run,b['portrait_round']),byte_cap=8*1024*1024)
    if square.width!=square.height or circular.width!=circular.height:
        raise ValueError('Portrait masters must be square canvases')
    out.mkdir()
    try:
        for filename,states,cols in [('spritesheet.png',BASE,8),('gadget-sheet.png',EXTRA,6)]:
            atlas=Image.new('RGBA',(cols*W,len(states)*H))
            for row,s in enumerate(states):
                for col,frame in enumerate(frames[s]):atlas.paste(frame,(col*W,row*H))
            atlas.save(out/filename,optimize=True)
        square.resize((512,512),Image.Resampling.NEAREST).save(out/'card-square-512.png')
        for n in (512,256):circular.resize((n,n),Image.Resampling.NEAREST).save(out/f'card-round-{n}.png')
        meta={'id':b['slug'],'displayName':b['name'],'description':b['description'],'spritesheetPath':'spritesheet.png',
              'gadgetSheet':{'spritesheetPath':'gadget-sheet.png','rows':[{'name':s,'index':i,'frames':b['row_counts'][s]} for i,s in enumerate(EXTRA)]}}
        if b.get('gadget_base'):
            source=inside(run,b['gadget_base']);load_png(source,(1536,1872))
            shutil.copy2(source,out/'gadget-base.png');meta['gadgetBasePath']='gadget-base.png'
        write(out/'pet.json',meta)
        result=validate(run)
        write(out/'structural-validation.json',result)
        return result
    except Exception:
        shutil.rmtree(out)
        raise

def rows_of(image,states,cols):
    rows=[]
    for y,s in enumerate(states):
        cells=[image.crop((x*W,y*H,(x+1)*W,(y+1)*H)) for x in range(cols)]
        used=[occupied(c) for c in cells]
        count=sum(used)
        if count<1 or count>6 or used!=([True]*count+[False]*(cols-count)):
            raise ValueError(f'Empty/holey/unsupported row: {s}')
        rows.append((s,cells[:count]))
    return rows

def validate(run):
    b=read(run/'brief.json');out=run/'bundle';meta=read(out/'pet.json')
    if meta['id']!=b['slug']:raise ValueError('Pet identity changed')
    base=load_png(inside(out,meta['spritesheetPath']),(1536,1872),byte_cap=16*1024*1024)
    delivered=load_png(inside(out,meta.get('gadgetBasePath',meta['spritesheetPath'])),(1536,1872))
    companion=load_png(inside(out,meta['gadgetSheet']['spritesheetPath']),(1152,624))
    regular_rows=rows_of(base,BASE,8);rows=rows_of(delivered,BASE,8)+rows_of(companion,EXTRA,6)
    for i,(s,fs) in enumerate(rows[9:]):
        expected={'name':s,'index':i,'frames':len(fs)}
        if meta['gadgetSheet']['rows'][i]!=expected:raise ValueError('Companion row metadata mismatch')
        if len(fs)!=(3 if s=='talking' else 6):raise ValueError('Wrong companion count')
    box=b.get('mouth_box')
    if not (isinstance(box,list) and len(box)==4 and all(type(v)==int for v in box) and 0<=box[0]<box[2]<=W and 0<=box[1]<box[3]<=H):
        raise ValueError('Derive and review mouth_box in registered cell coordinates')
    talk=dict(rows)['talking']; variants=set()
    for im in talk:
        variants.add(hashlib.sha256(im.tobytes()).hexdigest())
        diff=ImageChops.difference(im,talk[0]);diff.paste((0,0,0,0),box)
        if any(channel.getbbox() for channel in diff.split()):raise ValueError('Talking changes outside approved mouth/anatomy box')
    if len(variants)!=3:raise ValueError('Talking needs three distinct amplitude poses')
    for n in (256,512):
        im=load_png(out/f'card-round-{n}.png',(n,n));a=im.getchannel('A')
        for y in range(n):
            for x in range(n):
                if (x+.5-n/2)**2+(y+.5-n/2)**2>(n/2+.5)**2 and a.getpixel((x,y)):
                    raise ValueError('Round card has opaque pixels outside the circle')
        if not occupied(im):raise ValueError('Round card is empty')
    square=load_png(out/'card-square-512.png',(512,512))
    if not occupied(square):raise ValueError('Square card is empty')
    return {'structural_valid':True,'aesthetic_approved':False,'runtime_verified':False,
            'rows':{s:len(fs) for s,fs in rows},'desktop_rows':{s:len(fs) for s,fs in regular_rows},
            'signature_mapping':'waiting row6','talking':'three amplitude poses, not a live-playback proof',
            'mouth_box':box,'files':{p.name:{'sha256':sha(p),'bytes':p.stat().st_size} for p in sorted(out.iterdir()) if p.is_file() and p.suffix in ('.png','.json') and p.name!='structural-validation.json'}}

def preview(run):
    result=validate(run);out=run/'bundle';dest=run/'previews'
    meta=read(out/'pet.json'); base=load_png(inside(out,meta.get('gadgetBasePath',meta['spritesheetPath'])),(1536,1872)); extra=load_png(out/'gadget-sheet.png',(1152,624))
    data=[]
    all_rows=rows_of(base,BASE,8)+rows_of(extra,EXTRA,6)
    contact=Image.new('RGBA',(6*W,len(all_rows)*(H+20)),(18,20,28,255))
    pen=ImageDraw.Draw(contact)
    for row,(s,frames) in enumerate(all_rows):
        pen.text((4,row*(H+20)+2),s,fill=(240,240,255,255))
        for col,frame in enumerate(frames):contact.alpha_composite(frame,(col*W,row*(H+20)+20))
        urls=[];gif=[]
        for frame in frames:
            buf=io.BytesIO();frame.save(buf,format='PNG');urls.append('data:image/png;base64,'+base64.b64encode(buf.getvalue()).decode())
            bg=Image.new('RGBA',(W,H),(18,20,28,255));bg.alpha_composite(frame);gif.append(bg.convert('RGB'))
        demo=gif+[gif[1]] if s=='talking' else gif
        path=dest/(s+'.gif')
        if path.exists():raise ValueError('Preview exists; use a new revision/run')
        demo[0].save(path,save_all=True,append_images=demo[1:],duration=max(80,1100//len(demo)),loop=0)
        data.append({'state':s,'label':'Signature / waiting' if s=='waiting' else s,'frames':urls})
    contact.save(dest/'contact-sheet.png')
    cards=[]
    for name in ['card-square-512.png','card-round-256.png']:
        cards.append({'name':name,'url':'data:image/png;base64,'+base64.b64encode((out/name).read_bytes()).decode()})
    # All art is embedded original frame pixels; canvas only plays/resizes with nearest-neighbour.
    page='''<!doctype html><meta charset="utf-8"><title>Pet Factory motion review</title><style>
    body{font:16px system-ui;background:#10131e;color:#eef0ff;margin:24px}button,select,input{font:inherit;margin:6px}main{display:flex;flex-wrap:wrap;gap:20px}.cell{background:#202536;padding:14px;border-radius:16px}canvas,img{image-rendering:pixelated}canvas{display:block}.stage{width:192px;height:208px;display:flex;align-items:center;justify-content:center}.round .stage{width:256px;height:256px;border-radius:50%;overflow:hidden}.round canvas{width:236.307692px;height:256px}.light{background:#eee;color:#151722}.paused{outline:2px solid #bda9ff}</style>
    <h1>Pet Factory — all-state review</h1><p>Use normal/half speed and frame steps. Talking is a simulated amplitude demo; it does not prove live lip sync. No installation or approval is implied.</p>
    <button id="play">Pause</button><button id="step">Step frame</button><button id="bg">Light / dark</button><button id="round">Watch / desktop</button><select id="speed"><option value="1">Normal speed</option><option value="2">Half speed</option></select><section id="cards"></section><main></main><script>
    const DATA=__DATA__,CARDS=__CARDS__;let paused=false,elapsed=0,slow=1;const actors=[];
    for(const card of CARDS){const im=new Image();im.src=card.url;im.width=256;im.alt=card.name;document.querySelector('#cards').append(im)}
    for(const d of DATA){const box=document.createElement('section');box.className='cell';const h=document.createElement('h2');h.textContent=d.label;box.append(h);const c=document.createElement('canvas');c.width=192;c.height=208;const stage=document.createElement('div');stage.className='stage';stage.append(c);box.append(stage);const label=document.createElement('p');box.append(label);document.querySelector('main').append(box);const images=d.frames.map(src=>{const i=new Image();i.src=src;return i});actors.push({box,c,label,images,state:d.state,step:0});}
    function draw(){for(const a of actors){const seq=a.state==='talking'?[0,1,2,1]:a.images.map((_,i)=>i);const pos=paused?a.step:Math.floor(((elapsed/slow)%1100)/(1100/seq.length));a.step=pos;const index=seq[pos%seq.length];const ctx=a.c.getContext('2d');ctx.imageSmoothingEnabled=false;ctx.clearRect(0,0,192,208);if(a.images[index].complete)ctx.drawImage(a.images[index],0,0);a.label.textContent=`Frame ${index+1} / ${a.images.length}`;}}
    document.querySelector('#play').onclick=()=>{paused=!paused;document.querySelector('#play').textContent=paused?'Play':'Pause'};
    document.querySelector('#step').onclick=()=>{paused=true;document.querySelector('#play').textContent='Play';actors.forEach(a=>a.step++);draw()};
    document.querySelector('#bg').onclick=()=>actors.forEach(a=>a.box.classList.toggle('light'));
    document.querySelector('#round').onclick=()=>actors.forEach(a=>a.box.classList.toggle('round'));
    document.querySelector('#speed').onchange=e=>slow=Number(e.target.value);setInterval(()=>{if(!paused)actors.forEach(a=>a.step++);draw()},183);draw();
    </script>'''.replace('__DATA__',json.dumps(data)).replace('__CARDS__',json.dumps(cards))
    p=dest/'review.html'
    with p.open('x') as f:f.write(page)
    write(dest/'preview-manifest.json',{'review_sha256':sha(p),'states':len(data),'frames':sum(len(d['frames']) for d in data),'source_files':result['files'],'live_audio_test':False})
    return {'review':str(p),'states':len(data),'frames':sum(len(d['frames']) for d in data)}

def approve(run,gate,message,names):
    if not message.strip() or not names:raise ValueError('Explicit real user approval and nonempty shown file list required')
    p=run/'approvals.json';a=read(p)
    files={n:sha(inside(run,n)) for n in names}
    a['gates'][gate]={'message':message,'at':datetime.datetime.now(datetime.timezone.utc).isoformat(),'files':files}
    temporary=run/'approvals.next.json'
    write(temporary,a);temporary.replace(p)
    return {'recorded':gate,'artifacts':len(files),'note':'Records the agent-supplied real user message; does not itself obtain consent'}

def ready(run):
    a=read(run/'approvals.json');missing=[];changed=[]
    for gate in GATES:
        item=a['gates'].get(gate)
        if not item or not item['files']:missing.append(gate);continue
        for n,h in item['files'].items():
            p=inside(run,n)
            if not p.is_file() or sha(p)!=h:changed.append({'gate':gate,'file':n})
    b=read(run/'brief.json')
    if not b.get('targets'):missing.append('explicit_install_targets')
    plan=run/'install-plan.json'
    if plan.is_file() and not read(plan).get('targets'):missing.append('install_plan_targets')
    required={
        'brief':{'brief.json'},
        'look':{'anchor.png',b['portrait_square'],b['portrait_round']},
        'motion':{'bundle/pet.json','bundle/spritesheet.png','bundle/gadget-sheet.png','bundle/structural-validation.json','previews/review.html','previews/contact-sheet.png','previews/preview-manifest.json'}|{f'previews/{s}.gif' for s in BASE+EXTRA},
        'soul':{'SOUL.proposed.md','SOUL.diff'},
        'voice':{'voice/selection.json'},
        'install':{'install-plan.json'},
    }
    if b.get('gadget_base'):required['motion'].add('bundle/gadget-base.png')
    for n in ['card-square-512.png','card-round-256.png','card-round-512.png']:required['motion'].add('bundle/'+n)
    selection=run/'voice/selection.json'
    if selection.is_file():
        voice=read(selection)
        if not voice.get('voice_id'):missing.append('voice_id')
        if voice.get('preview_file'):required['voice'].add(voice['preview_file'])
        elif voice.get('mode')!='preserve':missing.append('voice_audition')
    coverage={g:sorted(names-set(a['gates'].get(g,{}).get('files',{}))) for g,names in required.items()}
    coverage={g:names for g,names in coverage.items() if names}
    if missing or changed or coverage:return {'ready_for_approved_install':False,'missing_gates':missing,'changed_artifacts':changed,'unapproved_required_files':coverage}
    return {'ready_for_approved_install':True,'validation':validate(run),'note':'Installation and runtime verification are still separate actions'}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('command',choices=['init','jobs','assemble','validate','preview','approve','ready']);p.add_argument('--run',type=Path,required=True);p.add_argument('--name');p.add_argument('--slug');p.add_argument('--gate',choices=GATES);p.add_argument('--message');p.add_argument('--files',nargs='+');a=p.parse_args();r=a.run.expanduser().resolve()
    if a.command=='init':
        if not a.name or not a.slug:p.error('init requires --name and --slug')
        value=init_run(r,a.name,a.slug)
    elif a.command=='approve':
        if not a.gate or a.message is None:p.error('approve requires --gate and --message')
        value=approve(r,a.gate,a.message,a.files)
    else:value={'jobs':jobs,'assemble':assemble,'validate':validate,'preview':preview,'ready':ready}[a.command](r)
    print(json.dumps(value,indent=2))

if __name__=='__main__':
    try:main()
    except (ValueError,OSError,KeyError) as e:raise SystemExit(str(e))
