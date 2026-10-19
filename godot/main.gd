extends Node3D
## Kingdoms AI em Godot: o mundo e a sociedade vivem no Core Java (ponte); aqui só se desenha e se pede.
## WASD move o marcador do rei · Q/E ou botão direito gira · roda aproxima · clique age conforme o modo.

const CHUNK := 16
const SECTIONS := 8
const VIEW_CHUNKS := 8
const NPC_LERP := 8.0
const LABEL_DIST := 42.0

var bridge: BridgeClient
var terrain: VoxelTerrain
var voxel_tool: VoxelTool
var viewer: VoxelViewer
var block_table: Array = []
var block_by_id: Dictionary = {}
var chunks_loaded: Dictionary = {}
var chunk_queue: Array = []
var npc_nodes: Dictionary = {}   # id -> NpcView
var npc_info: Dictionary = {}
var selected_id := ""
var kingdom_colors: Dictionary = {}
var fire_nodes: Dictionary = {}

var focus := Vector3(0, 72, 0)
var last_view_focus := Vector3(1e9, 0, 0)
var yaw := 0.6
var pitch := -0.75
var dist := 38.0
var marker: MeshInstance3D
var rain_fx: GPUParticles3D
var weather := "CLEAR"
var cam: Camera3D
var sun: DirectionalLight3D
var moon: DirectionalLight3D
var env: Environment
var sky_mat: ProceduralSkyMaterial
var sent_player_at := 0.0
var mode := "look"
var place_block := "minecraft:cobblestone"
var game_time := 6000.0

var realm_label: Label
var clock_label: Label
var stock_label: Label
var info_label: Label
var log_box: RichTextLabel
var input: LineEdit
var ai_panel: AiPanel
var ai_label: Label
var ai_flash := 0.0
var mode_buttons := {}

var smoke := false
var smoke_stage := 0
var smoke_people := ""
var smoke_start := 0.0
var shot_path := ""
var shot_at := 0.0
var last_state: Dictionary = {}
var fire_particles: ParticleProcessMaterial
var fire_mesh: QuadMesh


func _ready() -> void:
	var args := OS.get_cmdline_user_args()
	smoke = "--smoke" in args
	var si := args.find("--shot")
	if si >= 0 and si + 1 < args.size():
		shot_path = args[si + 1]
	var di := args.find("--dist")
	if di >= 0 and di + 1 < args.size():
		dist = float(args[di + 1])
	_build_world()
	_build_ui()
	bridge = BridgeClient.new()
	var pi := args.find("--port")
	if pi >= 0 and pi + 1 < args.size():
		bridge.port = int(args[pi + 1])
	add_child(bridge)
	bridge.hello.connect(_on_hello)
	bridge.state.connect(_on_state)
	bridge.lines.connect(_on_lines)
	bridge.chunk.connect(_on_chunk)
	bridge.blocks.connect(_on_blocks)
	bridge.fx.connect(_on_fx)
	ai_panel.setup(bridge)
	if "--ai-panel" in args:
		ai_panel.toggle()
	ai_panel.fallback_seen.connect(func(_n): ai_flash = 12.0)
	bridge.ai.connect(_on_ai_status)
	bridge.connection_changed.connect(_on_connection)


func _on_connection(up: bool) -> void:
	if up:
		_log("[color=#8fd48f]ponte conectada[/color]")
	else:
		_log("[color=#e08a8a]ponte caiu — tentando de novo[/color]")


# ---- mundo: céu, luz, câmera, chuva

func _build_world() -> void:
	sky_mat = ProceduralSkyMaterial.new()
	sky_mat.sky_curve = 0.12
	sky_mat.ground_curve = 0.05
	sky_mat.sun_angle_max = 28.0
	sky_mat.sun_curve = 0.08
	var sky := Sky.new()
	sky.sky_material = sky_mat
	env = Environment.new()
	env.background_mode = Environment.BG_SKY
	env.sky = sky
	env.ambient_light_source = Environment.AMBIENT_SOURCE_COLOR
	env.ambient_light_color = Color(0.72, 0.8, 0.95)
	env.reflected_light_source = Environment.REFLECTION_SOURCE_SKY
	env.tonemap_mode = Environment.TONE_MAPPER_ACES
	env.tonemap_exposure = 1.0
	env.tonemap_white = 6.0
	env.glow_enabled = true
	env.glow_intensity = 0.5
	env.glow_bloom = 0.04
	env.ssao_enabled = true
	env.ssao_radius = 1.2
	env.ssao_intensity = 1.6
	env.adjustment_enabled = true
	env.adjustment_saturation = 1.12
	env.adjustment_contrast = 1.05
	env.fog_enabled = true
	env.fog_light_color = Color(0.72, 0.82, 0.93)
	env.fog_density = 0.0028
	env.fog_sky_affect = 0.35
	var we := WorldEnvironment.new()
	we.environment = env
	add_child(we)
	sun = DirectionalLight3D.new()
	sun.rotation_degrees = Vector3(-50, 35, 0)
	sun.shadow_enabled = true
	sun.directional_shadow_max_distance = 140.0
	sun.directional_shadow_mode = DirectionalLight3D.SHADOW_PARALLEL_4_SPLITS
	sun.shadow_blur = 1.4
	sun.light_energy = 1.1
	add_child(sun)
	moon = DirectionalLight3D.new()
	moon.light_color = Color(0.55, 0.65, 1.0)
	moon.light_energy = 0.0
	moon.shadow_enabled = false
	moon.rotation_degrees = Vector3(-35, -140, 0)
	add_child(moon)
	cam = Camera3D.new()
	cam.far = 600.0
	cam.fov = 62.0
	add_child(cam)
	rain_fx = GPUParticles3D.new()
	var pm := ParticleProcessMaterial.new()
	pm.direction = Vector3(0.1, -1, 0)
	pm.spread = 3.0
	pm.initial_velocity_min = 28.0
	pm.initial_velocity_max = 34.0
	pm.gravity = Vector3(0, -20, 0)
	pm.emission_shape = ParticleProcessMaterial.EMISSION_SHAPE_BOX
	pm.emission_box_extents = Vector3(40, 1, 40)
	rain_fx.process_material = pm
	var drop := QuadMesh.new()
	drop.size = Vector2(0.03, 0.9)
	var dm := StandardMaterial3D.new()
	dm.albedo_color = Color(0.75, 0.85, 1.0, 0.45)
	dm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	dm.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
	dm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	drop.material = dm
	rain_fx.draw_pass_1 = drop
	rain_fx.amount = 6000
	rain_fx.lifetime = 1.4
	rain_fx.visibility_aabb = AABB(Vector3(-50, -30, -50), Vector3(100, 60, 100))
	rain_fx.emitting = false
	add_child(rain_fx)
	marker = MeshInstance3D.new()
	var cm := CylinderMesh.new()
	cm.top_radius = 0.0
	cm.bottom_radius = 0.55
	cm.height = 1.6
	marker.mesh = cm
	var mm := StandardMaterial3D.new()
	mm.albedo_color = Color(1, 0.82, 0.2)
	mm.emission_enabled = true
	mm.emission = Color(1, 0.7, 0.1)
	mm.emission_energy_multiplier = 0.8
	marker.material_override = mm
	marker.cast_shadow = GeometryInstance3D.SHADOW_CASTING_SETTING_OFF
	add_child(marker)
	fire_particles = ParticleProcessMaterial.new()
	fire_particles.direction = Vector3(0, 1, 0)
	fire_particles.spread = 18.0
	fire_particles.initial_velocity_min = 1.2
	fire_particles.initial_velocity_max = 2.4
	fire_particles.gravity = Vector3(0, 0.6, 0)
	fire_particles.scale_min = 0.5
	fire_particles.scale_max = 1.1
	var grad := Gradient.new()
	grad.colors = PackedColorArray([Color(1, 0.9, 0.35, 1), Color(1, 0.4, 0.05, 0.8), Color(0.2, 0.05, 0.02, 0)])
	grad.offsets = PackedFloat32Array([0.0, 0.5, 1.0])
	var gt := GradientTexture1D.new()
	gt.gradient = grad
	fire_particles.color_ramp = gt
	fire_mesh = QuadMesh.new()
	fire_mesh.size = Vector2(0.55, 0.55)
	var fm := StandardMaterial3D.new()
	fm.vertex_color_use_as_albedo = true
	fm.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	fm.billboard_mode = BaseMaterial3D.BILLBOARD_PARTICLES
	fm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	fm.blend_mode = BaseMaterial3D.BLEND_MODE_ADD
	fire_mesh.material = fm
	_update_camera()


# ---- interface

func _panel_style(alpha := 0.82) -> StyleBoxFlat:
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.06, 0.07, 0.09, alpha)
	sb.set_corner_radius_all(10)
	sb.border_color = Color(0.85, 0.7, 0.3, 0.35)
	sb.set_border_width_all(1)
	sb.content_margin_left = 12
	sb.content_margin_right = 12
	sb.content_margin_top = 8
	sb.content_margin_bottom = 8
	return sb


func _button(text: String, cb: Callable, toggle := false) -> Button:
	var b := Button.new()
	b.text = text
	b.toggle_mode = toggle
	b.focus_mode = Control.FOCUS_NONE
	b.pressed.connect(cb)
	var n := StyleBoxFlat.new()
	n.bg_color = Color(0.16, 0.18, 0.22, 0.95)
	n.set_corner_radius_all(7)
	n.content_margin_left = 12
	n.content_margin_right = 12
	n.content_margin_top = 6
	n.content_margin_bottom = 6
	var h: StyleBoxFlat = n.duplicate()
	h.bg_color = Color(0.28, 0.3, 0.36, 1.0)
	var p: StyleBoxFlat = n.duplicate()
	p.bg_color = Color(0.78, 0.6, 0.18, 1.0)
	b.add_theme_stylebox_override("normal", n)
	b.add_theme_stylebox_override("hover", h)
	b.add_theme_stylebox_override("pressed", p)
	b.add_theme_stylebox_override("focus", n)
	return b


func _build_ui() -> void:
	var theme := Theme.new()
	theme.default_font_size = 15
	var layer := CanvasLayer.new()
	add_child(layer)
	var root := Control.new()
	root.theme = theme
	root.set_anchors_preset(Control.PRESET_FULL_RECT)
	root.mouse_filter = Control.MOUSE_FILTER_IGNORE
	layer.add_child(root)

	# barra de cima: reino, relógio, recursos
	var top := PanelContainer.new()
	top.add_theme_stylebox_override("panel", _panel_style(0.78))
	top.set_anchors_preset(Control.PRESET_TOP_WIDE)
	top.offset_left = 10
	top.offset_right = -10
	top.offset_top = 8
	root.add_child(top)
	var th := HBoxContainer.new()
	th.add_theme_constant_override("separation", 26)
	top.add_child(th)
	realm_label = Label.new()
	realm_label.text = "Conectando…"
	realm_label.add_theme_color_override("font_color", Color(1, 0.86, 0.45))
	realm_label.add_theme_font_size_override("font_size", 18)
	th.add_child(realm_label)
	clock_label = Label.new()
	th.add_child(clock_label)
	ai_label = Label.new()
	ai_label.add_theme_color_override("font_color", Color(0.65, 0.78, 1.0))
	th.add_child(ai_label)
	stock_label = Label.new()
	stock_label.size_flags_horizontal = Control.SIZE_EXPAND_FILL
	stock_label.horizontal_alignment = HORIZONTAL_ALIGNMENT_RIGHT
	th.add_child(stock_label)

	# direita: ações e ferramentas
	var side := PanelContainer.new()
	side.add_theme_stylebox_override("panel", _panel_style(0.78))
	side.set_anchors_preset(Control.PRESET_TOP_RIGHT)
	side.offset_top = 64
	side.offset_right = -10
	side.grow_horizontal = Control.GROW_DIRECTION_BEGIN
	root.add_child(side)
	var sv := VBoxContainer.new()
	sv.add_theme_constant_override("separation", 6)
	side.add_child(sv)
	sv.add_child(_section("MÃOS DO REI"))
	var mr := HBoxContainer.new()
	mr.add_theme_constant_override("separation", 4)
	sv.add_child(mr)
	for m in [["look", "Olhar"], ["break", "Quebrar"], ["place", "Colocar"]]:
		var key: String = m[0]
		var mb := _button(m[1], func(): _set_mode(key), true)
		mb.button_pressed = key == "look"
		mode_buttons[key] = mb
		mr.add_child(mb)
	sv.add_child(_section("POVO"))
	sv.add_child(_button("Chamar todos", func(): bridge.send({"op": "call_all"})))
	var sr := HBoxContainer.new()
	sr.add_theme_constant_override("separation", 4)
	sv.add_child(sr)
	for sp in [1, 5, 20]:
		var mult: int = sp
		sr.add_child(_button("%dx" % mult, func(): bridge.send({"op": "speed", "mult": mult})))
	sv.add_child(_section("MUNDO"))
	var wr := HBoxContainer.new()
	wr.add_theme_constant_override("separation", 4)
	sv.add_child(wr)
	for tdef in [["Chuva", "rain"], ["Sol", "clear"], ["Fogo", "ignite"], ["Boom", "explode"], ["Água", "water"]]:
		var kind: String = tdef[1]
		wr.add_child(_button(tdef[0], func(): _tool(kind)))

	sv.add_child(_section("IA"))
	sv.add_child(_button("Painel de IA", func(): ai_panel.toggle()))
	ai_panel = AiPanel.new()
	ai_panel.set_anchors_preset(Control.PRESET_TOP_RIGHT)
	ai_panel.offset_top = 372
	ai_panel.offset_right = -10
	ai_panel.grow_horizontal = Control.GROW_DIRECTION_BEGIN
	root.add_child(ai_panel)

	# baixo à esquerda: seleção, log e comando
	var bottom := PanelContainer.new()
	bottom.add_theme_stylebox_override("panel", _panel_style(0.8))
	bottom.set_anchors_preset(Control.PRESET_BOTTOM_LEFT)
	bottom.offset_left = 10
	bottom.offset_bottom = -10
	bottom.grow_vertical = Control.GROW_DIRECTION_BEGIN
	bottom.custom_minimum_size = Vector2(500, 0)
	root.add_child(bottom)
	var bv := VBoxContainer.new()
	bv.add_theme_constant_override("separation", 6)
	bottom.add_child(bv)
	info_label = Label.new()
	info_label.text = "Clique num súdito para ver quem é · @frase para falar com ele"
	info_label.add_theme_color_override("font_color", Color(0.85, 0.85, 0.7))
	info_label.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	bv.add_child(info_label)
	log_box = RichTextLabel.new()
	log_box.bbcode_enabled = true
	log_box.scroll_following = true
	log_box.custom_minimum_size = Vector2(480, 150)
	bv.add_child(log_box)
	input = LineEdit.new()
	input.placeholder_text = "Ordem ou comando (ex.: construam uma casa · proíbo camas vermelhas · status)"
	input.text_submitted.connect(_on_submit)
	bv.add_child(input)


func _section(t: String) -> Label:
	var l := Label.new()
	l.text = t
	l.add_theme_font_size_override("font_size", 11)
	l.add_theme_color_override("font_color", Color(0.75, 0.62, 0.3))
	return l


func _set_mode(m: String) -> void:
	mode = m
	for k in mode_buttons:
		mode_buttons[k].button_pressed = k == m


func _tool(kind: String) -> void:
	var p := Vector3i(int(floor(focus.x)), int(focus.y), int(floor(focus.z)))
	match kind:
		"rain": bridge.send({"op": "weather", "w": "storm"})
		"clear": bridge.send({"op": "weather", "w": "clear"})
		"ignite": bridge.send({"op": "ignite", "x": p.x, "y": p.y, "z": p.z})
		"explode": bridge.send({"op": "explode", "x": p.x, "y": p.y, "z": p.z, "r": 5})
		"water": bridge.send({"op": "water", "x": p.x, "y": p.y + 2, "z": p.z})


func _flat(c: Color, alpha := 1.0) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.albedo_color = Color(c.r, c.g, c.b, alpha)
	if alpha < 1.0:
		m.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	return m


func _log(bb: String) -> void:
	if log_box:
		log_box.append_text(bb + "\n")


func _on_submit(text: String) -> void:
	input.clear()
	input.release_focus()
	if text.strip_edges() == "":
		return
	_log("[color=#aab]> %s[/color]" % text)
	if text.begins_with("@") and selected_id != "" and npc_info.has(selected_id):
		bridge.command("npc talk %s %s" % [npc_info[selected_id].name, text.substr(1).strip_edges()])
	else:
		bridge.command(text)


func _on_lines(msg: Dictionary) -> void:
	for l in msg.get("lines", []):
		var s := str(l)
		var col := "#e6e6e6"
		if s.begins_with("✓"):
			col = "#8fd48f"
		elif s.begins_with("✗"):
			col = "#e89090"
		elif s.begins_with("⚠"):
			col = "#ecd37a"
		elif s.begins_with("#"):
			col = "#f0c860"
		elif s.begins_with("«"):
			col = "#a8c8ff"
		_log("[color=%s]%s[/color]" % [col, s])


# ---- terreno voxel

func _block_height(id: String) -> float:
	if id.ends_with("_bed"):
		return 0.35
	if id.ends_with("wheat") or id.ends_with("torch") or id.ends_with("sapling") or id.ends_with("fern"):
		return 0.6
	if id == "minecraft:fire" or id == "minecraft:ash":
		return 0.12
	return 1.0


func _is_cross(id: String) -> bool:
	return id.ends_with("short_grass") or id.ends_with("dandelion") or id.ends_with("poppy") or id.ends_with("cornflower") 		or id.ends_with("fern") or id.ends_with("sapling") or id.ends_with("wheat") or id.ends_with("torch")


func _plant_material(c: Color, id: String) -> StandardMaterial3D:
	var m := StandardMaterial3D.new()
	m.albedo_color = c
	m.cull_mode = BaseMaterial3D.CULL_DISABLED
	m.roughness = 1.0
	if id.ends_with("torch"):
		m.emission_enabled = true
		m.emission = Color(1.0, 0.6, 0.2)
		m.emission_energy_multiplier = 2.5
	return m


## Dois quadros cruzados (capim, flores, mudas, trigo, tocha): o que o blocky não faz com cubos.
func _cross_mesh(id: String) -> ArrayMesh:
	var hgt := 0.62
	var w := 0.5
	if id.ends_with("short_grass"):
		hgt = 0.7
	elif id.ends_with("torch"):
		hgt = 0.55
		w = 0.07
	elif id.ends_with("wheat"):
		hgt = 0.85
	var verts := PackedVector3Array()
	var norms := PackedVector3Array()
	var uvs := PackedVector2Array()
	var idx := PackedInt32Array()
	for q in range(2):
		var ax := Vector3(1, 0, 1).normalized() if q == 0 else Vector3(1, 0, -1).normalized()
		var base := verts.size()
		var n := Vector3(0, 1, 0)
		verts.append(-ax * w + Vector3(0, -0.5, 0))
		verts.append(ax * w + Vector3(0, -0.5, 0))
		verts.append(ax * w + Vector3(0, -0.5 + hgt, 0))
		verts.append(-ax * w + Vector3(0, -0.5 + hgt, 0))
		for i in range(4):
			norms.append(n)
		uvs.append(Vector2(0, 1))
		uvs.append(Vector2(1, 1))
		uvs.append(Vector2(1, 0))
		uvs.append(Vector2(0, 0))
		idx.append_array(PackedInt32Array([base, base + 1, base + 2, base, base + 2, base + 3]))
	var arrays := []
	arrays.resize(Mesh.ARRAY_MAX)
	arrays[Mesh.ARRAY_VERTEX] = verts
	arrays[Mesh.ARRAY_NORMAL] = norms
	arrays[Mesh.ARRAY_TEX_UV] = uvs
	arrays[Mesh.ARRAY_INDEX] = idx
	var mesh := ArrayMesh.new()
	mesh.add_surface_from_arrays(Mesh.PRIMITIVE_TRIANGLES, arrays)
	return mesh


func _on_hello(msg: Dictionary) -> void:
	block_table = msg.blocks
	var lib := VoxelBlockyLibrary.new()
	var glass_mat := StandardMaterial3D.new()
	glass_mat.vertex_color_use_as_albedo = true
	glass_mat.vertex_color_is_srgb = true
	glass_mat.transparency = BaseMaterial3D.TRANSPARENCY_ALPHA
	glass_mat.roughness = 0.05
	glass_mat.cull_mode = BaseMaterial3D.CULL_DISABLED
	var water_mat := ShaderMaterial.new()
	water_mat.shader = load("res://shaders/water.gdshader")
	for b in block_table:
		block_by_id[b.id] = int(b.n)
		var model: VoxelBlockyModel
		if int(b.n) == 0:
			model = VoxelBlockyModelEmpty.new()
		else:
			var c := Color.hex((int(b.c) << 8) | 0xFF)
			if _is_cross(b.id):
				var cm := VoxelBlockyModelMesh.new()
				cm.mesh = _cross_mesh(b.id)
				cm.color = c
				cm.transparency_index = 3 + (int(b.n) % 200)
				lib.add_model(cm)
				continue
			var cube := VoxelBlockyModelCube.new()
			cube.height = _block_height(b.id)
			model = cube
			if b.fluid:
				c.a = 0.7
				model.set_material_override(0, water_mat)
				model.transparency_index = 1
			elif b.id == "minecraft:glass":
				c.a = 0.35
				model.set_material_override(0, glass_mat)
				model.transparency_index = 2
			elif b.tr:
				model.transparency_index = 3 + (int(b.n) % 200)
			model.color = c
		lib.add_model(model)
	var mesher := VoxelMesherBlocky.new()
	mesher.library = lib
	mesher.occlusion_enabled = true
	mesher.occlusion_darkness = 0.55
	terrain = VoxelTerrain.new()
	terrain.mesher = mesher
	terrain.automatic_loading_enabled = false
	terrain.generate_collisions = false
	terrain.max_view_distance = VIEW_CHUNKS * CHUNK + 32
	terrain.bounds = AABB(Vector3(-4096, 0, -4096), Vector3(8192, 128, 8192))
	var tmat := ShaderMaterial.new()
	tmat.shader = load("res://shaders/terrain.gdshader")
	terrain.material_override = tmat
	add_child(terrain)
	voxel_tool = terrain.get_voxel_tool()
	voxel_tool.channel = VoxelBuffer.CHANNEL_TYPE
	viewer = VoxelViewer.new()
	viewer.view_distance = VIEW_CHUNKS * CHUNK + 16
	add_child(viewer)
	var sp: Array = msg.spawn
	focus = Vector3(float(sp[0]), float(sp[1]), float(sp[2]))
	_log("mundo: %d tipos de bloco · semente %s" % [block_table.size(), str(int(msg.seed))])
	_request_view()


func _request_view() -> void:
	last_view_focus = focus
	bridge.send({"op": "view", "x": int(focus.x), "z": int(focus.z), "r": VIEW_CHUNKS})


func _on_chunk(msg: Dictionary) -> void:
	if terrain == null:
		return
	chunk_queue.append(msg)


func _feed_chunks(budget_ms: float) -> void:
	var t0 := Time.get_ticks_usec()
	while chunk_queue.size() > 0 and (Time.get_ticks_usec() - t0) < budget_ms * 1000.0:
		var msg: Dictionary = chunk_queue.pop_front()
		var raw: PackedByteArray = Marshalls.base64_to_raw(msg.d).decompress(CHUNK * CHUNK * CHUNK * SECTIONS * 2, FileAccess.COMPRESSION_GZIP)
		var cx := int(msg.cx)
		var cz := int(msg.cz)
		for sec in range(SECTIONS):
			var buf := VoxelBuffer.new()
			buf.create(CHUNK, CHUNK, CHUNK)
			buf.set_channel_depth(VoxelBuffer.CHANNEL_TYPE, VoxelBuffer.DEPTH_16_BIT)
			buf.set_channel_from_byte_array(VoxelBuffer.CHANNEL_TYPE, raw.slice(sec * 8192, (sec + 1) * 8192))
			terrain.try_set_block_data(Vector3i(cx, sec, cz), buf)
		chunks_loaded["%d:%d" % [cx, cz]] = true


func _on_fx(msg: Dictionary) -> void:
	var id = msg.get("npc")
	if id != null and npc_nodes.has(id) and str(msg.anim) == "SWING":
		(npc_nodes[id] as NpcView).swing()


func _on_blocks(msg: Dictionary) -> void:
	if voxel_tool == null:
		return
	for b in msg.b:
		voxel_tool.set_voxel(Vector3i(int(b[0]), int(b[1]), int(b[2])), int(b[3]))


# ---- estado

func _on_state(msg: Dictionary) -> void:
	last_state = msg
	for k in msg.kingdoms:
		kingdom_colors[k.id] = Color.hex((int(k.color) << 8) | 0xFF)
	var seen := {}
	for n in msg.npcs:
		if not n.get("body", false):
			continue
		seen[n.id] = true
		var target := Vector3(float(n.x), float(n.y) + 0.88, float(n.z))
		npc_info[n.id] = n
		if not npc_nodes.has(n.id):
			var v := NpcView.new()
			v.setup(n, kingdom_colors.get(n.k, Color.WHITE))
			v.position = target
			add_child(v)
			npc_nodes[n.id] = v
		var view: NpcView = npc_nodes[n.id]
		view.target = target
		view.set_info(n)
	for id in npc_nodes.keys():
		if not seen.has(id):
			npc_nodes[id].queue_free()
			npc_nodes.erase(id)
			npc_info.erase(id)
			if selected_id == id:
				selected_id = ""
	_update_fires(msg.get("firePos", []))
	game_time = float(msg.time)
	weather = str(msg.get("weather", "CLEAR"))
	_apply_time(game_time)
	rain_fx.emitting = weather != "CLEAR"
	rain_fx.amount_ratio = 1.0 if weather == "STORM" else 0.45
	env.fog_density = 0.0028 if weather == "CLEAR" else 0.0075
	_update_hud(msg)
	if smoke:
		_smoke_step(msg)


func _on_ai_status(msg: Dictionary) -> void:
	if str(msg.get("op", "")) != "ai_status":
		return
	var label := str(msg.get("provider", "mock"))
	for p in msg.get("providers", []):
		if str(p.id) == label:
			label = str(p.label)
	var s := "IA: " + label
	if not msg.get("live", false) and str(msg.get("provider", "mock")) != "mock":
		s += " (fora do ar, usando regras)"
	ai_label.text = s + ("   ⚠ falas pelas regras" if ai_flash > 0.0 else "")


func _update_hud(msg: Dictionary) -> void:
	var hh := int(game_time / 1000.0 + 6.0) % 24
	var mm := int(fmod(game_time, 1000.0) / 1000.0 * 60.0)
	var wname: String = {"CLEAR": "céu limpo", "RAIN": "chuva", "STORM": "tempestade"}.get(weather, weather)
	clock_label.text = "Dia %d · %02d:%02d · %s" % [msg.day, hh, mm, wname]
	var realm: Dictionary = msg.get("realm", {})
	if not realm.is_empty():
		realm_label.text = str(realm.name)
		stock_label.text = "Comida %d   Madeira %d   Pedra %d   Ferro %d   Ouro %d     Povo %d/%d   Estab. %d   Exército %d" % [
			int(realm.food), int(realm.wood), int(realm.stone), int(realm.iron), int(realm.gold),
			int(realm.pop), int(realm.housing), int(realm.stability), int(realm.army)]
	if selected_id != "" and npc_info.has(selected_id):
		var n: Dictionary = npc_info[selected_id]
		var task := str(n.get("task", ""))
		info_label.text = "%s — %s · %s%s" % [n.name, str(n.prof).capitalize(), str(n.act).to_lower().replace("_", " "), (" · " + task) if task != "" else ""]


func _update_fires(list: Array) -> void:
	var want := {}
	for f in list:
		var key := "%d,%d,%d" % [int(f[0]), int(f[1]), int(f[2])]
		want[key] = true
		if fire_nodes.has(key):
			continue
		var n := Node3D.new()
		n.position = Vector3(float(f[0]) + 0.5, float(f[1]) + 0.4, float(f[2]) + 0.5)
		var p := GPUParticles3D.new()
		p.process_material = fire_particles
		p.draw_pass_1 = fire_mesh
		p.amount = 14
		p.lifetime = 0.9
		p.visibility_aabb = AABB(Vector3(-2, -1, -2), Vector3(4, 5, 4))
		n.add_child(p)
		if fire_nodes.size() < 14:
			var l := OmniLight3D.new()
			l.light_color = Color(1.0, 0.55, 0.2)
			l.light_energy = 2.4
			l.omni_range = 9.0
			l.position = Vector3(0, 0.6, 0)
			n.add_child(l)
		add_child(n)
		fire_nodes[key] = n
	for key in fire_nodes.keys():
		if not want.has(key):
			fire_nodes[key].queue_free()
			fire_nodes.erase(key)


func _apply_time(t: float) -> void:
	var a := t / 24000.0 * 360.0
	sun.rotation_degrees = Vector3(-a, 35, 0)
	var h := sin(deg_to_rad(a))
	var day := clampf(h * 2.2 + 0.25, 0.0, 1.0)
	var dusk := clampf(1.0 - absf(h) * 4.0, 0.0, 1.0)
	sun.light_energy = clampf(h * 1.5, 0.0, 1.15)
	sun.light_color = Color(1.0, 0.62, 0.38).lerp(Color(1.0, 0.97, 0.9), clampf(h * 3.0, 0.0, 1.0))
	var top_day := Color(0.22, 0.46, 0.84)
	var hor_day := Color(0.66, 0.8, 0.94)
	var top_night := Color(0.01, 0.015, 0.05)
	var hor_night := Color(0.04, 0.05, 0.1)
	sky_mat.sky_top_color = top_night.lerp(top_day, day)
	var hor := hor_night.lerp(hor_day, day).lerp(Color(1.0, 0.55, 0.3), dusk * 0.7)
	sky_mat.sky_horizon_color = hor
	sky_mat.ground_horizon_color = hor
	sky_mat.ground_bottom_color = Color(0.03, 0.04, 0.05).lerp(Color(0.2, 0.25, 0.2), day)
	env.ambient_light_energy = 1.25 - 0.15 * day
	env.ambient_light_color = Color(0.36, 0.46, 0.72).lerp(Color(0.74, 0.82, 0.96), day)
	moon.light_energy = clampf(-h * 2.0, 0.0, 1.0) * (0.6 if weather != "CLEAR" else 1.0)
	env.fog_light_color = hor
	if weather != "CLEAR":
		sky_mat.sky_top_color = sky_mat.sky_top_color.lerp(Color(0.35, 0.38, 0.42), 0.6)
		sky_mat.sky_horizon_color = sky_mat.sky_horizon_color.lerp(Color(0.45, 0.48, 0.52), 0.6)
		sun.light_energy *= 0.45


# ---- laço

func _process(delta: float) -> void:
	if ai_flash > 0.0:
		ai_flash -= delta
		if ai_flash <= 0.0 and ai_panel.status.size() > 0:
			_on_ai_status(ai_panel.status)
	_feed_chunks(6.0)
	if shot_path != "" and chunks_loaded.size() >= 100 and chunk_queue.size() == 0:
		shot_at += delta
		var sargs := OS.get_cmdline_user_args()
		if "--gather" in sargs and shot_at < delta * 2.0:
			bridge.send({"op": "call_all"})
		var spi := sargs.find("--speed")
		if spi >= 0 and shot_at < delta * 2.0:
			bridge.send({"op": "speed", "mult": int(sargs[spi + 1])})
		var wi := sargs.find("--wait")
		if shot_at > (float(sargs[wi + 1]) if wi >= 0 else 7.0):
			get_viewport().get_texture().get_image().save_png(shot_path)
			get_tree().quit(0)
	for id in npc_nodes:
		var v := npc_nodes[id] as NpcView
		v.tick(delta, NPC_LERP)
		v.show_label(id == selected_id or cam.position.distance_to(v.position) < LABEL_DIST)
	var mv := Vector3.ZERO
	if not input.has_focus():
		if Input.is_key_pressed(KEY_W): mv.z -= 1
		if Input.is_key_pressed(KEY_S): mv.z += 1
		if Input.is_key_pressed(KEY_A): mv.x -= 1
		if Input.is_key_pressed(KEY_D): mv.x += 1
		if Input.is_key_pressed(KEY_Q): yaw += delta
		if Input.is_key_pressed(KEY_E): yaw -= delta
	if mv != Vector3.ZERO:
		var fast := 2.2 if Input.is_key_pressed(KEY_SHIFT) else 1.0
		focus += mv.normalized().rotated(Vector3.UP, yaw) * delta * (14.0 + dist * 0.3) * fast
	if voxel_tool != null:
		focus.y = lerpf(focus.y, _surface_y(int(floor(focus.x)), int(floor(focus.z)), int(focus.y)), minf(1.0, 8.0 * delta))
	marker.position = focus + Vector3(0, 0.9 + sin(Time.get_ticks_msec() / 300.0) * 0.12, 0)
	marker.rotation.y += delta * 1.5
	if viewer != null:
		viewer.position = focus
	rain_fx.position = focus + Vector3(0, 28, 0)
	_update_camera()
	if bridge.is_up() and voxel_tool != null and focus.distance_to(last_view_focus) > 20.0:
		_request_view()
	sent_player_at += delta
	if sent_player_at > 0.25 and bridge.is_up():
		sent_player_at = 0.0
		bridge.send({"op": "player", "x": int(floor(focus.x)), "y": int(focus.y), "z": int(floor(focus.z))})


func _surface_y(x: int, z: int, near: int) -> float:
	var y := mini(126, near + 8)
	while y > 1:
		var id := int(voxel_tool.get_voxel(Vector3i(x, y, z)))
		if id > 0 and id < block_table.size() and bool(block_table[id].solid):
			return float(y + 1)
		y -= 1
	return focus.y


func _update_camera() -> void:
	var off := Vector3(0, 0, dist).rotated(Vector3.RIGHT, pitch).rotated(Vector3.UP, yaw)
	cam.position = focus + off
	cam.look_at(focus + Vector3(0, 1.0, 0), Vector3.UP)


func _unhandled_input(e: InputEvent) -> void:
	if e is InputEventMouseButton and e.pressed:
		if e.button_index == MOUSE_BUTTON_WHEEL_UP:
			dist = maxf(6.0, dist * 0.9)
		elif e.button_index == MOUSE_BUTTON_WHEEL_DOWN:
			dist = minf(200.0, dist * 1.1)
		elif e.button_index == MOUSE_BUTTON_LEFT:
			if mode == "look":
				_pick_npc(e.position)
			else:
				_click(e.position)
	elif e is InputEventMouseMotion and Input.is_mouse_button_pressed(MOUSE_BUTTON_RIGHT):
		yaw -= e.relative.x * 0.005
		pitch = clampf(pitch - e.relative.y * 0.005, -1.5, -0.12)
	elif e is InputEventKey and e.pressed and not e.echo:
		if e.keycode == KEY_ENTER or e.keycode == KEY_KP_ENTER or e.keycode == KEY_T:
			if not input.has_focus():
				input.grab_focus()
				get_viewport().set_input_as_handled()
		elif e.keycode == KEY_1:
			_set_mode("look")
		elif e.keycode == KEY_2:
			_set_mode("break")
		elif e.keycode == KEY_3:
			_set_mode("place")


func _pick_npc(screen: Vector2) -> void:
	var origin := cam.project_ray_origin(screen)
	var dir := cam.project_ray_normal(screen)
	var best := ""
	var best_d := 1.1
	for id in npc_nodes:
		var c: Vector3 = (npc_nodes[id] as NpcView).position + Vector3(0, 0.3, 0)
		var t := maxf(0.0, (c - origin).dot(dir))
		var d := (origin + dir * t).distance_to(c)
		if d < best_d:
			best_d = d
			best = id
	_select(best)


func _select(id: String) -> void:
	if selected_id != "" and npc_nodes.has(selected_id):
		(npc_nodes[selected_id] as NpcView).select(false)
	selected_id = id
	if id == "":
		info_label.text = "Clique num súdito para ver quem é · @frase para falar com ele"
		return
	(npc_nodes[id] as NpcView).select(true)
	bridge.command("npc inspect " + str(npc_info[id].name))


func _click(screen: Vector2) -> void:
	if voxel_tool == null:
		return
	var origin := cam.project_ray_origin(screen)
	var dir := cam.project_ray_normal(screen)
	var hit := voxel_tool.raycast(origin, dir, 120.0)
	if hit == null:
		return
	if mode == "break":
		var p: Vector3i = hit.position
		bridge.send({"op": "break", "x": p.x, "y": p.y, "z": p.z})
	elif mode == "place":
		var q: Vector3i = hit.previous_position
		bridge.send({"op": "place", "x": q.x, "y": q.y, "z": q.z, "block": place_block})


# ---- teste de fumaça: godot --headless -- --smoke --port N

func _smoke_step(msg: Dictionary) -> void:
	if smoke_start == 0.0:
		smoke_start = Time.get_ticks_msec() / 1000.0
	var now := Time.get_ticks_msec() / 1000.0 - smoke_start
	if now > 40.0:
		print("SMOKE FAIL (tempo): estágio %d, chunks %d" % [smoke_stage, chunks_loaded.size()])
		get_tree().quit(1)
		return
	if smoke_stage == 0 and chunks_loaded.size() >= 25 and chunk_queue.size() == 0:
		var bedrock: int = block_by_id["minecraft:bedrock"]
		var ok := true
		for i in range(40):
			var x := (i * 7) % 16
			var z := (i * 5) % 16
			if int(voxel_tool.get_voxel(Vector3i(x, 0, z))) != bedrock:
				ok = false
			if int(voxel_tool.get_voxel(Vector3i(x, 127, z))) != 0:
				ok = false
		print("SMOKE voxels: bedrock em y=0 e ar em y=127 -> %s (chunks %d)" % [ok, chunks_loaded.size()])
		if not ok:
			get_tree().quit(1)
			return
		smoke_stage = 1
		focus = Vector3(40, 72, 40)
		bridge.send({"op": "player", "x": 40, "y": 72, "z": 40})
		bridge.send({"op": "speed", "mult": 10})
		bridge.send({"op": "call_all"})
		return
	if smoke_stage == 1:
		var mine := ""
		for k in msg.kingdoms:
			if k.player:
				mine = k.id
		var sum := 0.0
		var n := 0
		for npc in msg.npcs:
			if npc.k != mine:
				continue
			sum += Vector2(float(npc.x) - 40.0, float(npc.z) - 40.0).length()
			n += 1
		var avg := sum / maxf(1.0, n)
		if avg < 12.0:
			smoke_people = "%d súditos, distância média %.1f, %d corpos em cena, %d chunks" % [n, avg, npc_nodes.size(), chunks_loaded.size()]
			print("SMOKE povo: ", smoke_people)
			smoke_stage = 2
			ai_panel.toggle()          # abre o painel de IA e pede ai_status à ponte
			return
	if smoke_stage == 2 and ai_panel.status_count > 0:
		var st: Dictionary = ai_panel.status
		var led: Dictionary = st.get("ledger", {})
		var ok: bool = ai_panel.visible and st.get("provider", "") == "mock" and st.get("providers", []).size() >= 3 and led.has("calls") 				and ai_panel.provider_id() == "mock" and AiPanel.footer_text(led, "").contains("nenhuma")
		print("SMOKE painel de IA: provider=%s, providers=%d -> %s" % [st.get("provider", "?"), st.get("providers", []).size(), ok])
		if not ok:
			get_tree().quit(1)
			return
		print("SMOKE OK: ", smoke_people, " · IA ", st.get("provider", ""))
		get_tree().quit(0)
