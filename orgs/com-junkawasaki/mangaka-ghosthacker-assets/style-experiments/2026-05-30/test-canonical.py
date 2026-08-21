import os, json, importlib.util
os.environ['V11_LAYOUT']='1'
os.environ['V11_STYLE']='hybrid'
os.environ['V11_STORY_CANONICAL']='1'
import sys; sys.path.insert(0, '.')

spec = importlib.util.spec_from_file_location('sc','lg_mangaka/v11_story_canonical.py')
sc = importlib.util.module_from_spec(spec); spec.loader.exec_module(sc)

from pathlib import Path
mfst_path = Path('../data/ghosthacker/resources/episodes/arc0-1-origin/image-gen-manifest.json')
m = json.load(open(mfst_path))
panels = sorted([p for p in m['panels'] if p['pageNum']==0], key=lambda p: p['panelIndex'])
outline = sc.load_story_outline(mfst_path)
story = sc.get_page_story(outline, 0)
sc.attach_story_to_panels(panels, story)

from lg_mangaka import v10_panel_render as r
panel = panels[0]
print("Panel 1 story fields:")
print(f"  setting: {panel.get('_story_setting')}")
print(f"  visualNote: {panel.get('_story_visual_note')}")
print(f"  pov: {panel.get('_story_pov')}")
print(f"  sfx_texts: {panel.get('_story_sfx_texts')}")

prompt, neg, w, h = r._build_prompt(panel, 'yuto-bedroom', 'cu_ink')
print()
print("Generated prompt:")
print(prompt[:900])

spec2 = importlib.util.spec_from_file_location('sfx','scripts/sfx_overlay.py')
sfx = importlib.util.module_from_spec(spec2); spec2.loader.exec_module(sfx)
print()
print("SFX picks per panel:")
for p in panels:
    s = sfx.pick_sfx_for_panel(p)
    txt = s[0] if isinstance(s, tuple) else s
    print(f"  panel {p['panelIndex']}: {txt}")
