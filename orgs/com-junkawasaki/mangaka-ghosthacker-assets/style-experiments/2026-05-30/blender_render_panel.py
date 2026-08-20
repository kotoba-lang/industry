
import bpy, sys, os, mathutils, json
args = json.loads(sys.argv[sys.argv.index("--")+1])

# Clear default scene
bpy.ops.object.select_all(action="SELECT"); bpy.ops.object.delete()
for m in list(bpy.data.materials): bpy.data.materials.remove(m)

scene = bpy.context.scene

# EEVEE engine + Freestyle = proper manga line art (replaces Workbench cavity)
# Engine selection.
# V5_BASE=cinematic auto-enables Cycles (real GI + materials).
# V5_BASE=manga keeps EEVEE (faster, flat emission only).
# Explicit args.engine="CYCLES" still wins.
_want_cycles = (args.get("engine") == "CYCLES" or
                 os.environ.get("V5_BASE", "manga") == "cinematic")
# v6 NPR mode forces EEVEE (Toon BSDF cell shading via shader_to_rgb)
if os.environ.get("V5_BASE", "manga") == "v6":
    _want_cycles = False
if _want_cycles:
    engine_candidates = ["CYCLES", "BLENDER_EEVEE_NEXT", "BLENDER_EEVEE", "BLENDER_WORKBENCH"]
else:
    engine_candidates = ["BLENDER_EEVEE_NEXT", "BLENDER_EEVEE", "BLENDER_WORKBENCH"]
chosen_engine = None
for eng in engine_candidates:
    try: scene.render.engine = eng; chosen_engine = eng; break
    except: continue
if chosen_engine == "CYCLES":
    scene.cycles.samples = 32
    scene.cycles.use_denoising = True

# Pure white world background
if scene.world is None:
    scene.world = bpy.data.worlds.new("White")
scene.world.use_nodes = True
bg_node = scene.world.node_tree.nodes.get("Background")
if bg_node:
    bg_node.inputs[0].default_value = (1.0, 1.0, 1.0, 1.0)
    bg_node.inputs[1].default_value = 1.5

# Enable Freestyle line render
scene.render.use_freestyle = True
view_layer = scene.view_layers[0]
view_layer.use_freestyle = True
# Clear existing linesets and add manga ink lineset
fs = view_layer.freestyle_settings
while fs.linesets:
    fs.linesets.remove(fs.linesets[0])
lineset = fs.linesets.new("MangaInk")
lineset.select_silhouette = True
lineset.select_border = True
lineset.select_contour = True
lineset.select_crease = True
lineset.select_external_contour = True
ls = lineset.linestyle
ls.color = (0, 0, 0)
ls.thickness = 3.5
ls.use_chaining = True

scene.render.resolution_x = args["width"]
scene.render.resolution_y = args["height"]
scene.render.image_settings.file_format = "PNG"
scene.render.image_settings.color_mode = "RGB"
scene.render.film_transparent = False

# Import main scene
bpy.ops.wm.usd_import(filepath=args["usd"], import_cameras=True, import_lights=True,
                       import_materials=True, import_meshes=True, scale=100.0)

# Base mode selection:
#   V5_BASE=cinematic (NEW default): keep imported USD materials, apply
#     scene-appropriate Principled BSDF to bare meshes. Cycles GI + sun
#     light renders a Pixar-style 3D preview in COLOR. SDXL Stage 2 then
#     gets a rich semantic base to convert into manga.
#   V5_BASE=manga: flat white emission (original NPR base for clean
#     canny edges; useful for ultra-clean line-art mode).
_v5_base = os.environ.get("V5_BASE", "manga")
if _v5_base == "v6":
    # v6 NPR Toon Shader (EEVEE-fast cell shading)
    def _toon(name, base_color, shadow_threshold=0.3):
        mat = bpy.data.materials.new(name)
        mat.use_nodes = True
        nt = mat.node_tree; nt.nodes.clear()
        out = nt.nodes.new("ShaderNodeOutputMaterial")
        diffuse = nt.nodes.new("ShaderNodeBsdfDiffuse")
        diffuse.inputs["Color"].default_value = (*base_color, 1.0)
        s2rgb = nt.nodes.new("ShaderNodeShaderToRGB")
        ramp = nt.nodes.new("ShaderNodeValToRGB")
        ramp.color_ramp.interpolation = "CONSTANT"
        ramp.color_ramp.elements[0].color = tuple(c*0.45 for c in base_color) + (1.0,)
        ramp.color_ramp.elements[1].position = shadow_threshold
        ramp.color_ramp.elements[1].color = (*base_color, 1.0)
        emission = nt.nodes.new("ShaderNodeEmission")
        emission.inputs["Strength"].default_value = 1.0
        nt.links.new(diffuse.outputs[0], s2rgb.inputs[0])
        nt.links.new(s2rgb.outputs[0], ramp.inputs[0])
        nt.links.new(ramp.outputs[0], emission.inputs[0])
        nt.links.new(emission.outputs[0], out.inputs[0])
        return mat
    v6_colors = [
        ("floor",(0.62,0.50,0.38)),("wall",(0.92,0.90,0.85)),("ceiling",(0.95,0.95,0.92)),
        ("desk",(0.55,0.40,0.27)),("chair",(0.50,0.35,0.25)),("blackboard",(0.18,0.22,0.20)),
        ("window",(0.85,0.92,0.95)),("bed",(0.65,0.55,0.45)),("mattress",(0.92,0.88,0.80)),
        ("blanket",(0.40,0.45,0.65)),("phone",(0.18,0.18,0.18)),("lamp",(0.80,0.70,0.50)),
        ("road",(0.35,0.35,0.35)),("building",(0.75,0.70,0.60)),("ground",(0.50,0.45,0.40)),
    ]
    _v6_default = _toon("V6_Default", (0.70,0.70,0.72))
    _v6_cache = {}
    for obj in bpy.data.objects:
        if obj.type != "MESH": continue
        nm = obj.name.lower(); assigned = None
        for key, color in v6_colors:
            if key in nm:
                if key not in _v6_cache:
                    _v6_cache[key] = _toon(f"V6_{key}", color)
                assigned = _v6_cache[key]; break
        if assigned is None: assigned = _v6_default
        obj.data.materials.clear()
        obj.data.materials.append(assigned)
elif _v5_base == "manga":
    white_mat = bpy.data.materials.new("PaperWhite")
    white_mat.use_nodes = True
    white_mat.node_tree.nodes.clear()
    out_node = white_mat.node_tree.nodes.new("ShaderNodeOutputMaterial")
    emit_node = white_mat.node_tree.nodes.new("ShaderNodeEmission")
    emit_node.inputs[0].default_value = (1, 1, 1, 1)
    emit_node.inputs[1].default_value = 1.0
    white_mat.node_tree.links.new(emit_node.outputs[0], out_node.inputs[0])
    for obj in bpy.data.objects:
        if obj.type == "MESH":
            obj.data.materials.clear()
            obj.data.materials.append(white_mat)
else:
    # Cinematic 3D: assign Principled BSDF with scene-appropriate
    # colors to meshes that have no material (or only emission). This
    # gives Cycles real surface response.
    def _pbsdf(name, base_color, roughness=0.6, metallic=0.0):
        mat = bpy.data.materials.new(name)
        mat.use_nodes = True
        mat.node_tree.nodes.clear()
        out = mat.node_tree.nodes.new("ShaderNodeOutputMaterial")
        p   = mat.node_tree.nodes.new("ShaderNodeBsdfPrincipled")
        p.inputs["Base Color"].default_value = (*base_color, 1.0)
        p.inputs["Roughness"].default_value = roughness
        p.inputs["Metallic"].default_value = metallic
        mat.node_tree.links.new(p.outputs[0], out.inputs[0])
        return mat
    # Object-name → color mapping (heuristic by prim name)
    color_map = [
        ("floor",     (0.55, 0.42, 0.30), 0.7, 0.0),  # wood
        ("wall",      (0.92, 0.90, 0.85), 0.8, 0.0),  # cream wall
        ("ceiling",   (0.95, 0.95, 0.92), 0.8, 0.0),
        ("desk",      (0.50, 0.35, 0.22), 0.5, 0.0),  # wooden
        ("chair",     (0.45, 0.30, 0.20), 0.5, 0.0),
        ("blackboard",(0.15, 0.20, 0.18), 0.5, 0.0),  # chalkboard
        ("window",    (0.85, 0.92, 0.95), 0.05, 0.0), # glass
        ("bed",       (0.65, 0.55, 0.45), 0.7, 0.0),
        ("mattress",  (0.92, 0.88, 0.80), 0.9, 0.0),
        ("blanket",   (0.40, 0.45, 0.65), 0.9, 0.0),  # blue
        ("phone",     (0.10, 0.10, 0.10), 0.2, 0.6),  # dark metallic
        ("lamp",      (0.80, 0.75, 0.60), 0.4, 0.2),  # warm brass
        ("book",      (0.65, 0.25, 0.20), 0.7, 0.0),  # red cover
        ("road",      (0.30, 0.30, 0.30), 0.8, 0.0),
        ("building",  (0.75, 0.70, 0.60), 0.7, 0.0),
        ("ground",    (0.50, 0.45, 0.40), 0.8, 0.0),
    ]
    default_mat = _pbsdf("DefaultGray", (0.70, 0.70, 0.72), 0.6, 0.0)
    name_mats = {}
    for obj in bpy.data.objects:
        if obj.type != "MESH": continue
        obj_lower = obj.name.lower()
        assigned = None
        for key, color, rough, met in color_map:
            if key in obj_lower:
                if key not in name_mats:
                    name_mats[key] = _pbsdf(f"Mat_{key}", color, rough, met)
                assigned = name_mats[key]
                break
        if assigned is None:
            assigned = default_mat
        obj.data.materials.clear()
        obj.data.materials.append(assigned)

# Track the focal mannequin object for camera framing
mannequin_focal = None

# Import MULTIPLE mannequin USDs — args["mannequins"] = [{usd, pos, scale?}, ...]
# Falls back to single args["mannequin"]+args["mannequin_pos"] for compat.
mannequins_to_place = args.get("mannequins") or []
if not mannequins_to_place and args.get("mannequin"):
    mannequins_to_place = [{"usd": args["mannequin"],
                              "pos": args["mannequin_pos"]}]

for mq_idx, mq in enumerate(mannequins_to_place):
    args["mannequin"] = mq["usd"]
    args["mannequin_pos"] = mq["pos"]
    if mq.get("scale"): args["mannequin_scale"] = mq["scale"]
    if args.get("mannequin"):
        n_before = set(o.name for o in bpy.data.objects)
        bpy.ops.wm.usd_import(filepath=args["mannequin"],
                               import_cameras=False, import_lights=False,
                               import_materials=False, import_meshes=True, scale=100.0)
        n_after = set(o.name for o in bpy.data.objects)
        new_names = n_after - n_before
        px, py, pz = args["mannequin_pos"]
        mannequin_mat = bpy.data.materials.new(f"Character_{mq_idx}")
        mannequin_mat.use_nodes = True
        mannequin_mat.node_tree.nodes.clear()
        out2 = mannequin_mat.node_tree.nodes.new("ShaderNodeOutputMaterial")
        emit2 = mannequin_mat.node_tree.nodes.new("ShaderNodeEmission")
        # Mid-gray (0.6) emission — softer than pure black so SDXL has
        # latitude to render a character, but still distinct from white
        # paper background.
        emit2.inputs[0].default_value = (0.6, 0.6, 0.6, 1)
        emit2.inputs[1].default_value = 1.0
        mannequin_mat.node_tree.links.new(emit2.outputs[0], out2.inputs[0])
        MANNEQUIN_SCALE = args.get("mannequin_scale", 2.5)
        for nm in new_names:
            obj = bpy.data.objects.get(nm)
            if obj and obj.type == "MESH":
                obj.scale = (MANNEQUIN_SCALE, MANNEQUIN_SCALE, MANNEQUIN_SCALE)
                obj.location.x = obj.location.x * MANNEQUIN_SCALE + px
                obj.location.y = obj.location.y * MANNEQUIN_SCALE + -pz
                obj.location.z = obj.location.z * MANNEQUIN_SCALE + py
                obj.data.materials.clear()
                obj.data.materials.append(mannequin_mat)
                try:
                    m = obj.modifiers.new("Subsurf", "SUBSURF")
                    m.levels = 2; m.render_levels = 2
                    m.subdivision_type = "CATMULL_CLARK"
                except Exception: pass
                if nm.startswith("Torso") and mannequin_focal is None:
                    mannequin_focal = obj

def usd_to_blender(v):
    return (v[0], -v[2], v[1])

cam_data = bpy.data.cameras.new("V5Cam"); cam_data.lens = args.get("lens", 35)
cam_obj = bpy.data.objects.new("V5Cam", cam_data)
scene.collection.objects.link(cam_obj)
cam_obj.location = usd_to_blender(args["cam_origin"])
# Auto-target mannequin torso if available, else use cam_target arg
if mannequin_focal is not None:
    target = mannequin_focal.location.copy()
    # Aim slightly above torso center for head visibility
    target.z += 0.3
else:
    target = mathutils.Vector(usd_to_blender(args["cam_target"]))
direction = target - cam_obj.location
rot_quat = direction.to_track_quat('-Z', 'Y')
cam_obj.rotation_euler = rot_quat.to_euler()
scene.camera = cam_obj

# Sun light:
#   V5_BASE=cinematic: ALWAYS enabled (real lighting for materials)
#   V5_BASE=manga + V5_MANGA_MODE=1: skipped (flat emission only)
#   Otherwise: respect V5_MANGA_MODE
import math, os as _os
_cinematic = _os.environ.get("V5_BASE", "manga") == "cinematic"
_v6 = _os.environ.get("V5_BASE", "manga") == "v6"
if _cinematic or _v6 or _os.environ.get("V5_MANGA_MODE", "1") != "1":
    sun_alt = math.radians(args.get("sun_altitude_deg", 45))
    sun_az  = math.radians(args.get("sun_azimuth_deg", 240))
    sun_intensity = args.get("sun_intensity", 5.0)
    sun_data = bpy.data.lights.new("Sun", "SUN")
    sun_data.energy = sun_intensity
    sun_data.angle = 0.05
    sun_obj = bpy.data.objects.new("Sun", sun_data)
    scene.collection.objects.link(sun_obj)
    sun_obj.rotation_euler = (math.pi/2 - sun_alt, 0, sun_az - math.pi)
    fill_data = bpy.data.lights.new("Fill", "SUN")
    fill_data.energy = sun_intensity * 0.25
    fill_data.angle = 1.0
    fill_obj = bpy.data.objects.new("Fill", fill_data)
    scene.collection.objects.link(fill_obj)
    fill_obj.rotation_euler = (math.pi/2 - sun_alt * 0.5, 0, sun_az)

scene.render.filepath = args["out"]

# OpenEXR multi-pass output (for Blender Compositor downstream).
# V5_MULTIPASS=1 enables EXR with Z/Normal/Diffuse passes.
if os.environ.get("V5_MULTIPASS") == "1":
    scene.render.image_settings.file_format = "OPEN_EXR_MULTILAYER"
    scene.render.image_settings.exr_codec = "ZIP"
    scene.render.use_compositing = True
    view_layer.use_pass_z = True
    view_layer.use_pass_normal = True
    view_layer.use_pass_diffuse_color = True
    try:
        view_layer.cycles.use_pass_diffuse_direct = True
        view_layer.cycles.use_pass_diffuse_indirect = True
    except Exception: pass
    scene.render.filepath = args["out"].replace(".png", ".exr")

bpy.ops.render.render(write_still=True)
print(f"OK render: {args['out']} engine={chosen_engine}")
