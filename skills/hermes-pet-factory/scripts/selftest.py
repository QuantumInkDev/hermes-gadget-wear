#!/usr/bin/env python3
"""Offline behavioral tests with original synthetic geometry/audio; never a pet/art approval."""
import base64
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from PIL import Image, ImageDraw

HERE=Path(__file__).resolve().parent

def module(name):
    spec=importlib.util.spec_from_file_location(name,HERE/(name+'.py'));m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m

f=module('factory');v=module('voices')

class FactoryTest(unittest.TestCase):
    def setUp(self):self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.run=self.root/'run';f.init_run(self.run,'Synthetic QA only','synthetic-qa')
    def tearDown(self):self.temp.cleanup()
    def inputs(self):
        b=f.read(self.run/'brief.json');b['description']='Synthetic test fixture, not production art';b['appearance']='Synthetic block geometry';b['mouth_box']=[78,74,114,108]
        for state in f.BASE+f.EXTRA:
            b['choreography'][state]='synthetic QA only'
            for i in range(b['row_counts'][state]):
                im=Image.new('RGBA',(192,208));d=ImageDraw.Draw(im);d.rectangle((50,40,142,190),fill=(40,80,140,255))
                if state=='talking':d.rectangle((80,80,110,82+i*6),fill=(240,120,80,255))
                im.save(self.run/'frames'/state/f'{i+1:02}.png')
        square=Image.new('RGBA',(512,512),(40,80,140,255));square.save(self.run/'portraits/square.png')
        circle=Image.new('RGBA',(512,512));ImageDraw.Draw(circle).ellipse((0,0,511,511),fill=(40,80,140,255));circle.save(self.run/'portraits/round.png')
        square.save(self.run/'anchor.png');(self.run/'brief.json').write_text(json.dumps(b))
    def test_complete_pack_and_all_state_previews(self):
        self.inputs();result=f.assemble(self.run);self.assertTrue(result['structural_valid']);self.assertFalse(result['runtime_verified']);self.assertEqual(len(result['rows']),12)
        preview=f.preview(self.run);self.assertEqual(preview['frames'],69);self.assertEqual(len(list((self.run/'previews').glob('*.gif'))),12)
        self.assertTrue((self.run/'previews/contact-sheet.png').is_file());self.assertIn('simulated amplitude',(self.run/'previews/review.html').read_text())
    def test_extra_occupied_tail_refused(self):
        self.inputs();f.assemble(self.run);p=self.run/'bundle/spritesheet.png';im=Image.open(p).convert('RGBA');im.putpixel((6*192+1,40),(255,0,0,255));im.save(p)
        with self.assertRaisesRegex(ValueError,'holey'):f.validate(self.run)
    def test_talking_outside_mouth_refused(self):
        self.inputs();p=self.run/'frames/talking/02.png';im=Image.open(p).convert('RGBA');im.putpixel((10,10),(255,0,0,255));im.save(p)
        with self.assertRaisesRegex(ValueError,'outside approved'):f.assemble(self.run)
        self.assertFalse((self.run/'bundle').exists())
    def test_round_square_backdrop_refused(self):
        self.inputs();Image.new('RGBA',(512,512),(0,0,0,255)).save(self.run/'portraits/round.png')
        with self.assertRaisesRegex(ValueError,'outside the circle'):f.assemble(self.run)
    def test_approval_scope_and_change_invalidation(self):
        self.inputs();f.assemble(self.run);f.preview(self.run)
        for gate in f.GATES:f.approve(self.run,gate,'TEST ONLY: explicit fixture approval',['brief.json'])
        self.assertFalse(f.ready(self.run)['ready_for_approved_install'])
        self.assertIn('motion',f.ready(self.run)['unapproved_required_files'])
        before=f.read(self.run/'brief.json');before['description']='Changed after approval';(self.run/'brief.json').write_text(json.dumps(before))
        self.assertTrue(f.ready(self.run)['changed_artifacts'])
    def test_output_overwrite_and_traversal_refused(self):
        with self.assertRaises(ValueError):f.inside(self.run,'../escape')
        self.inputs();f.assemble(self.run)
        with self.assertRaisesRegex(ValueError,'already exists'):f.assemble(self.run)
        with self.assertRaises(ValueError):f.approve(self.run,'look','', ['brief.json'])

class VoiceTest(unittest.TestCase):
    def setUp(self):self.temp=tempfile.TemporaryDirectory();self.root=Path(self.temp.name);self.payload={'voice_description':'An original warm patient fictional guide voice.','text':'A synthetic offline audition script. '*4}
    def tearDown(self):self.temp.cleanup()
    def test_default_design_only_plans_and_missing_approval_never_sends(self):
        with patch.object(v,'request') as request:
            self.assertTrue(v.mutation('design',self.payload,self.root/'plan')['no_api_call'])
            with self.assertRaises(ValueError):v.mutation('design',self.payload,self.root/'no-consent',True)
            request.assert_not_called()
    def test_budget_accounts_success_and_blocks_second_paid_send(self):
        fake={'previews':[{'generated_voice_id':'qa-preview','audio_base_64':base64.b64encode(b'OFFLINE AUDIO FIXTURE').decode(),'media_type':'audio/mpeg'}],'text':self.payload['text']}
        ledger=self.root/'spend.jsonl'
        with patch.object(v,'request',return_value=json.dumps(fake).encode()) as request:
            v.mutation('design',self.payload,self.root/'one',True,'TEST approved',ledger,1,1000)
            with self.assertRaisesRegex(ValueError,'exhausted'):v.mutation('design',self.payload,self.root/'two',True,'TEST approved',ledger,1,1000)
            self.assertEqual(request.call_count,1)
        self.assertEqual(len([json.loads(x) for x in ledger.read_text().splitlines() if json.loads(x)['state']=='attempted']),1)
    def test_uncertain_paid_call_is_counted_and_never_retried(self):
        ledger=self.root/'spend.jsonl'
        with patch.object(v,'request',side_effect=RuntimeError('synthetic uncertain transport')) as request:
            with self.assertRaises(RuntimeError):v.mutation('design',self.payload,self.root/'one',True,'TEST approved',ledger,1,1000)
            self.assertEqual(request.call_count,1)
        self.assertIn('uncertain_or_failed_no_retry',ledger.read_text())
    def test_unselected_preview_cannot_be_saved(self):
        manifest=self.root/'previews.json';manifest.write_text(json.dumps({'previews':[{'generated_voice_id':'qa-known'}]}))
        with patch.object(v,'request') as request:
            with self.assertRaisesRegex(ValueError,'belong'):v.mutation('save',{'generated_voice_id':'qa-other','voice_name':'QA','voice_description':'QA fixture'},self.root/'save',True,'TEST approved',preview_manifest=manifest)
            request.assert_not_called()

class ApprovalCompleteTest(unittest.TestCase):
    setUp=FactoryTest.setUp
    tearDown=FactoryTest.tearDown
    inputs=FactoryTest.inputs
    def test_fully_approved_pack_then_mutation(self):
        self.inputs();f.assemble(self.run);f.preview(self.run)
        (self.run/'SOUL.proposed.md').write_text('Synthetic fixture persona only')
        (self.run/'SOUL.diff').write_text('Synthetic fixture diff only')
        (self.run/'voice/selection.json').write_text(json.dumps({'mode':'preserve','voice_id':'offline-fixture-id'}))
        target={'box':'OFFLINE-TEST-FIXTURE','profile':'qa','home':'NONEXECUTABLE-FIXTURE'}
        brief=f.read(self.run/'brief.json');brief['targets']=[target];(self.run/'brief.json').write_text(json.dumps(brief))
        (self.run/'install-plan.json').write_text(json.dumps({'targets':[target],'test_only':True}))
        packets={'brief':['brief.json'],'look':['anchor.png','portraits/square.png','portraits/round.png'],
                 'motion':[str(p.relative_to(self.run)) for folder in ['bundle','previews'] for p in (self.run/folder).iterdir() if p.is_file()],
                 'soul':['SOUL.proposed.md','SOUL.diff'],'voice':['voice/selection.json'],'install':['install-plan.json']}
        for gate,files in packets.items():f.approve(self.run,gate,'TEST ONLY fixture approval',files)
        self.assertTrue(f.ready(self.run)['ready_for_approved_install'])
        p=self.run/'bundle/spritesheet.png';im=Image.open(p).convert('RGBA');im.putpixel((60,60),(55,88,155,255));im.save(p)
        self.assertFalse(f.ready(self.run)['ready_for_approved_install'])

class InstallerTest(unittest.TestCase):
    def test_preflight_refuses_existing_without_partial_install(self):
        installer=module('install_skill')
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);existing=root/'existing/hermes-pet-factory';existing.mkdir(parents=True);(existing/'SKILL.md').write_text('unrelated existing skill')
            with self.assertRaises(ValueError):installer.install([root/'new',root/'existing'],apply=True)
            self.assertFalse((root/'new/hermes-pet-factory').exists())
            self.assertEqual((existing/'SKILL.md').read_text(),'unrelated existing skill')
    def test_source_alias_destination_refused_before_write(self):
        installer=module('install_skill')
        with tempfile.TemporaryDirectory() as temp:
            alias=Path(temp)/'source-alias';alias.symlink_to(installer.SOURCE,target_is_directory=True)
            with self.assertRaisesRegex(ValueError,'inside source'):installer.install([alias/'never-create'],apply=True)
            self.assertFalse((installer.SOURCE/'never-create').exists())
            with self.assertRaisesRegex(ValueError,'inside source'):installer.archive(alias/'never-create.tar.gz')
            self.assertFalse((installer.SOURCE/'never-create.tar.gz').exists())
            case_alias=installer.SOURCE.with_name(installer.SOURCE.name.upper())
            if case_alias.exists() and case_alias.samefile(installer.SOURCE):
                with self.assertRaisesRegex(ValueError,'inside source'):installer.install([case_alias/'never-create'],apply=True)
    def test_portable_copy_excludes_local_environment(self):
        installer=module('install_skill')
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp);result=installer.install([root],'copy',True);p=root/'hermes-pet-factory'
            self.assertTrue(result['targets'][0]['verified']);self.assertFalse((p/'.venv').exists());self.assertTrue((p/'scripts/factory.py').is_file())

if __name__=='__main__':unittest.main(verbosity=2)
