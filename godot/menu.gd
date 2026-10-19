class_name GameMenu
extends Control
## Menu inicial/pausa: Continuar, Mundo novo (nome + semente), Salvar, Sair. Botões grossos estilo desenho animado.

signal closed

var bridge: BridgeClient
var info: Dictionary = {}
var _name: LineEdit
var _seed: LineEdit
var _msg: Label
var _continue: Button
var _save: Button


static func cartoon(text: String, color: Color, cb: Callable) -> Button:
	var b := Button.new()
	b.text = text
	b.focus_mode = Control.FOCUS_NONE
	b.custom_minimum_size = Vector2(240, 54)
	b.add_theme_font_size_override("font_size", 20)
	b.add_theme_color_override("font_color", Color.WHITE)
	b.add_theme_color_override("font_outline_color", Color(0.1, 0.07, 0.05))
	b.add_theme_constant_override("outline_size", 6)
	b.add_theme_color_override("font_disabled_color", Color(0.8, 0.8, 0.8))
	for st in ["normal", "hover", "pressed", "disabled"]:
		var s := StyleBoxFlat.new()
		var c := color
		if st == "hover":
			c = color.lightened(0.15)
		elif st == "pressed":
			c = color.darkened(0.15)
		elif st == "disabled":
			c = Color(0.5, 0.5, 0.5)
		s.bg_color = c
		s.set_corner_radius_all(18)
		s.set_border_width_all(4)
		s.border_color = Color(0.1, 0.07, 0.05)
		s.border_width_bottom = 9 if st != "pressed" else 4
		s.content_margin_top = 8
		s.content_margin_bottom = 8 if st != "pressed" else 13
		b.add_theme_stylebox_override(st, s)
	b.add_theme_stylebox_override("focus", b.get_theme_stylebox("normal"))
	b.pressed.connect(cb)
	return b


func setup(b: BridgeClient) -> void:
	bridge = b
	bridge.ai.connect(_on_msg)
	set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	var dim := ColorRect.new()
	dim.color = Color(0.05, 0.1, 0.2, 0.72)
	dim.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(dim)
	var center := CenterContainer.new()
	center.set_anchors_and_offsets_preset(Control.PRESET_FULL_RECT)
	add_child(center)
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 12)
	center.add_child(v)
	var title := Label.new()
	title.text = "KINGDOMS AI"
	title.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	title.add_theme_font_size_override("font_size", 56)
	title.add_theme_color_override("font_color", Color(1, 0.85, 0.3))
	title.add_theme_color_override("font_outline_color", Color(0.25, 0.1, 0.05))
	title.add_theme_constant_override("outline_size", 14)
	v.add_child(title)
	_continue = cartoon("Continuar", Color(0.3, 0.75, 0.35), func(): close())
	v.add_child(_continue)
	v.add_child(cartoon("Mundo novo", Color(0.95, 0.6, 0.15), _new_world))
	_name = LineEdit.new()
	_name.placeholder_text = "Nome do reino"
	_name.text = "Reino de Andraus"
	_seed = LineEdit.new()
	_seed.placeholder_text = "Semente (vazio = aleatória)"
	v.add_child(_name)
	v.add_child(_seed)
	_save = cartoon("Salvar jogo", Color(0.25, 0.6, 0.95), func(): bridge.send({"op": "save_now"}))
	v.add_child(_save)
	v.add_child(cartoon("Sair", Color(0.85, 0.3, 0.3), func(): get_tree().quit()))
	_msg = Label.new()
	_msg.horizontal_alignment = HORIZONTAL_ALIGNMENT_CENTER
	v.add_child(_msg)
	visible = true


func open() -> void:
	visible = true
	bridge.send({"op": "menu_info"})


func close() -> void:
	visible = false
	closed.emit()


func _new_world() -> void:
	_msg.text = "Criando mundo novo…"
	bridge.send({"op": "new_world", "name": _name.text, "seed": _seed.text})


func _on_msg(msg: Dictionary) -> void:
	if str(msg.get("op", "")) != "menu_info":
		return
	info = msg
	_continue.disabled = false
	_continue.text = "Continuar" if msg.get("saved", false) else "Jogar"
	_save.disabled = not msg.get("can_save", false)
	if msg.get("name", "") != "":
		_msg.text = "%s · dia %d" % [msg.name, int(msg.get("day", 0))]
