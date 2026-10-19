class_name NpcView
extends Node3D
## Corpo de um súdito: cabeça, tronco, braços e pernas de cubos, com passada, golpe de ferramenta e ferramenta na mão.

const SKIN := [Color(0.93, 0.75, 0.62), Color(0.80, 0.60, 0.45), Color(0.62, 0.43, 0.30), Color(0.45, 0.30, 0.20)]

var npc_id := ""
var npc_name := ""
var target := Vector3.ZERO
var tunic := Color.WHITE
var held_id := ""
var moving := false

var _arm_l: Node3D
var _arm_r: Node3D
var _leg_l: Node3D
var _leg_r: Node3D
var _tool: MeshInstance3D
var _label: Label3D
var _ring: MeshInstance3D
var _phase := 0.0
var _swing := 0.0


static func _box(size: Vector3, color: Color, offset := Vector3.ZERO) -> MeshInstance3D:
	var mi := MeshInstance3D.new()
	var bm := BoxMesh.new()
	bm.size = size
	mi.mesh = bm
	var m := StandardMaterial3D.new()
	m.albedo_color = color
	mi.material_override = m
	mi.position = offset
	return mi


func setup(info: Dictionary, color: Color) -> void:
	npc_id = str(info.id)
	npc_name = str(info.name)
	tunic = color
	var skin: Color = SKIN[abs(hash(npc_id)) % SKIN.size()]
	var pants := color.darkened(0.55)
	var body := _box(Vector3(0.5, 0.7, 0.26), color, Vector3(0, 0.0, 0))
	body.position = Vector3(0, 0.15, 0)
	add_child(body)
	var head := _box(Vector3(0.38, 0.38, 0.38), skin)
	head.position = Vector3(0, 0.73, 0)
	add_child(head)
	var hair := _box(Vector3(0.4, 0.12, 0.4), Color(0.2, 0.14, 0.1).lerp(skin, 0.2))
	hair.position = Vector3(0, 0.95, 0)
	add_child(hair)
	_arm_l = _limb(Vector3(-0.34, 0.45, 0), Vector3(0.14, 0.62, 0.14), skin.lerp(color, 0.5))
	_arm_r = _limb(Vector3(0.34, 0.45, 0), Vector3(0.14, 0.62, 0.14), skin.lerp(color, 0.5))
	_leg_l = _limb(Vector3(-0.13, -0.2, 0), Vector3(0.2, 0.7, 0.2), pants)
	_leg_r = _limb(Vector3(0.13, -0.2, 0), Vector3(0.2, 0.7, 0.2), pants)
	_tool = _box(Vector3(0.1, 0.1, 0.5), Color.WHITE, Vector3(0, -0.3, 0.28))
	_tool.visible = false
	_arm_r.add_child(_tool)
	_label = Label3D.new()
	_label.billboard = BaseMaterial3D.BILLBOARD_ENABLED
	_label.pixel_size = 0.0075
	_label.font_size = 30
	_label.outline_size = 8
	_label.position = Vector3(0, 1.45, 0)
	_label.no_depth_test = true
	_label.text = "%s\n%s" % [npc_name, str(info.prof).capitalize()]
	add_child(_label)
	_ring = MeshInstance3D.new()
	var tm := TorusMesh.new()
	tm.inner_radius = 0.5
	tm.outer_radius = 0.62
	_ring.mesh = tm
	var rm := StandardMaterial3D.new()
	rm.albedo_color = Color(1, 0.9, 0.2)
	rm.shading_mode = BaseMaterial3D.SHADING_MODE_UNSHADED
	_ring.material_override = rm
	_ring.position = Vector3(0, -0.88, 0)
	_ring.visible = false
	add_child(_ring)


func _limb(pivot: Vector3, size: Vector3, color: Color) -> Node3D:
	var p := Node3D.new()
	p.position = pivot
	var m := _box(size, color, Vector3(0, -size.y / 2.0 + 0.08, 0))
	p.add_child(m)
	add_child(p)
	return p


func show_label(on: bool) -> void:
	_label.visible = on


func select(on: bool) -> void:
	_ring.visible = on


func set_info(info: Dictionary) -> void:
	_label.text = "%s\n%s" % [npc_name, str(info.prof).capitalize()]
	var h := str(info.get("held", ""))
	if h != held_id:
		held_id = h
		_apply_tool()


func _apply_tool() -> void:
	var c := Color.WHITE
	if held_id == "":
		_tool.visible = false
		return
	if held_id.contains("pickaxe"):
		c = Color(0.65, 0.68, 0.72)
	elif held_id.contains("axe"):
		c = Color(0.55, 0.35, 0.2)
	elif held_id.contains("hoe") or held_id.contains("shovel"):
		c = Color(0.6, 0.5, 0.3)
	elif held_id.contains("sword"):
		c = Color(0.85, 0.88, 0.95)
	elif held_id.contains("bread") or held_id.contains("wheat"):
		c = Color(0.85, 0.7, 0.3)
	else:
		c = Color(0.5, 0.7, 0.9)
	(_tool.material_override as StandardMaterial3D).albedo_color = c
	_tool.visible = true


func swing() -> void:
	_swing = 0.45


func tick(delta: float, lerp_speed: float) -> void:
	var d := target - position
	moving = d.length() > 0.03
	if moving:
		position += d * minf(1.0, lerp_speed * delta)
		var flat := Vector3(d.x, 0, d.z)
		if flat.length() > 0.04:
			rotation.y = lerp_angle(rotation.y, atan2(flat.x, flat.z), 9.0 * delta)
		_phase += delta * 9.0
		var s := sin(_phase) * 0.7
		_leg_l.rotation.x = s
		_leg_r.rotation.x = -s
		_arm_l.rotation.x = -s * 0.8
		_arm_r.rotation.x = s * 0.8 if _swing <= 0.0 else _arm_r.rotation.x
	else:
		_leg_l.rotation.x = lerpf(_leg_l.rotation.x, 0.0, 8.0 * delta)
		_leg_r.rotation.x = lerpf(_leg_r.rotation.x, 0.0, 8.0 * delta)
		_arm_l.rotation.x = lerpf(_arm_l.rotation.x, 0.0, 8.0 * delta)
		if _swing <= 0.0:
			_arm_r.rotation.x = lerpf(_arm_r.rotation.x, 0.0, 8.0 * delta)
	if _swing > 0.0:
		_swing -= delta
		_arm_r.rotation.x = -1.6 + sin((0.45 - _swing) / 0.45 * PI * 2.0) * 0.9
