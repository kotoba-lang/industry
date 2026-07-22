import { spawnSync } from "node:child_process";
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";

const output = resolve(process.argv[2] || "80-data/system/murakumo-fleet-witness.json");
const remote = String.raw`
import collections, hashlib, json, os, pathlib, subprocess, urllib.request
root=pathlib.Path('/home/gad/.murakumo/generation')
jobs=json.loads((root/'jobs.json').read_text())
health=json.loads(urllib.request.urlopen('http://127.0.0.1:8081/healthz',timeout=5).read())
comfy=json.loads(urllib.request.urlopen('http://127.0.0.1:8188/system_stats',timeout=5).read())
pid=subprocess.check_output(['systemctl','show','murakumo-generation.service','-p','MainPID','--value'],text=True).strip()
environment={}
for item in pathlib.Path('/proc',pid,'environ').read_bytes().split(b'\0'):
 if b'=' in item:
  k,v=item.split(b'=',1); environment[k.decode(errors='ignore')]=v.decode(errors='ignore')
runner_keys=['MURAKUMO_3D_POSTPROCESSOR','HUNYUAN3D_PAINT_RUNNER','MURAKUMO_TTS_RUNNER',
 'MURAKUMO_VISEME_RUNNER','MURAKUMO_MOTION_RUNNER','MURAKUMO_EFFECT_RUNNER','MURAKUMO_SOUND_RUNNER']
runner_files={}
for key in runner_keys:
 path=pathlib.Path(environment.get(key,''))
 if path.is_file(): runner_files[key]={'path':str(path),'sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
api=pathlib.Path('/home/gad/.murakumo/bin/hunyuan3d-generation-api')
freeze=subprocess.check_output(['python3','-m','pip','freeze','--local'],text=True).splitlines()
names={'vrm':'model.vrm','glb':'model.glb','wav':'voice.wav','motion-json':'motion.json',
       'effect-json':'effect.json','sound-wav':'sound.wav','png':'image.png','mp4':'video.mp4'}
requirements={
 'image':lambda j,a:j.get('outputKind')=='png',
 '3d':lambda j,a:j.get('outputKind') in ('glb','vrm'),
 'rig':lambda j,a:'auto-rig' in a.get('capabilities',[]),
 'motion':lambda j,a:j.get('outputKind')=='motion-json',
 'effect':lambda j,a:j.get('outputKind')=='effect-json',
 'vrm-compose':lambda j,a:j.get('outputKind')=='vrm' and 'vrm-1.0' in a.get('capabilities',[]),
 'music':lambda j,a:'sound-kind:music' in a.get('capabilities',[]),
 'sfx':lambda j,a:'sound-kind:sfx' in a.get('capabilities',[]),
 'voice':lambda j,a:j.get('outputKind')=='wav' and 'voice' in a.get('capabilities',[]),
 'video':lambda j,a:j.get('outputKind')=='mp4'}
done=[]
for jid,j in jobs.items():
 if j.get('status')!='done' or not j.get('artifacts'): continue
 a=j['artifacts'][0]
 done.append((a.get('generatedAt',''),jid,j,a))
done.sort(reverse=True)
witness={}
for modality,predicate in requirements.items():
 for _,jid,j,a in done:
  if not predicate(j,a): continue
  path=root/jid/names[j['outputKind']]
  digest='sha256:'+hashlib.sha256(path.read_bytes()).hexdigest() if path.is_file() else None
  witness[modality]={'jobId':jid,'outputKind':j['outputKind'],'bytes':path.stat().st_size if path.is_file() else None,
    'contentHash':a.get('contentHash'),'artifactHash':digest,'hashVerified':digest==a.get('contentHash'),
    'generatedAt':a.get('generatedAt'),'model':a.get('model'),'capabilities':a.get('capabilities',[])}
  break
device=comfy['devices'][0]
print(json.dumps({'node':'gad','ledger':str(root/'jobs.json'),'health':health,
 'capabilityLabels':{'os':comfy['system']['os'],'device':device['name'],'vramBytes':device['vram_total'],
  'pytorch':comfy['system']['pytorch_version'],'comfyui':comfy['system']['comfyui_version']},
 'runtimeImage':{'apiSourceSha256':hashlib.sha256(api.read_bytes()).hexdigest(),
  'pipFreezeSha256':hashlib.sha256(('\n'.join(sorted(freeze))+'\n').encode()).hexdigest(),
  'pipPackages':len(freeze),'runnerFiles':runner_files},'modalities':witness},separators=(',',':')))
`;

const probe = spawnSync("ssh", ["-o", "BatchMode=yes", "-o", "ConnectTimeout=5", "gad",
  "python3", "-"], { input: remote, encoding: "utf8", maxBuffer: 8 * 1024 * 1024 });
if (probe.status !== 0) throw new Error(probe.stderr || "gad fleet witness probe failed");
const receipt = JSON.parse(probe.stdout);
const required = ["image", "3d", "rig", "motion", "effect", "vrm-compose", "music", "sfx", "voice", "video"];
const missing = required.filter(key => !receipt.modalities[key]?.hashVerified);
receipt.schema = 1;
receipt.generatedAt = new Date().toISOString();
receipt.requiredModalities = required;
receipt.complete = missing.length === 0;
receipt.missing = missing;
mkdirSync(dirname(output), { recursive: true });
writeFileSync(output, `${JSON.stringify(receipt, null, 2)}\n`);
console.log(JSON.stringify({ output, complete: receipt.complete, modalities: required.length, missing }, null, 2));
if (missing.length) process.exit(1);
