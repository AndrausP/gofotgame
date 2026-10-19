class_name AiPanel
extends PanelContainer
## Painel de IA: escolhe o provider (Regras / Claude / Ollama), os modelos e o teto de gasto, testa, entra na conta
## e mostra "chamadas · gasto estimado · última falha". Só fala com a ponte (ai_status / ai_config / ai_test / ai_login);
## quem decide e valida é o Core.

signal fallback_seen(count: int)

const POLL_SECONDS := 5.0
const EFFORTS := ["", "low", "medium", "high", "xhigh", "max"]

var bridge: BridgeClient
var status: Dictionary = {}
var status_count := 0

var _provider: OptionButton
var _npc_model: OptionButton
var _council_model: OptionButton
var _budget: SpinBox
var _msg: Label
var _footer: Label
var _login_btn: Button
var _models_row: Control
var _loading := false
var _poll := 0.0
var _last_fallbacks := 0


func setup(b: BridgeClient) -> void:
	bridge = b
	bridge.ai.connect(_on_ai)
	_build()
	visible = false


func _build() -> void:
	var sb := StyleBoxFlat.new()
	sb.bg_color = Color(0.06, 0.07, 0.09, 0.92)
	sb.set_corner_radius_all(10)
	sb.border_color = Color(0.85, 0.7, 0.3, 0.5)
	sb.set_border_width_all(1)
	sb.content_margin_left = 14
	sb.content_margin_right = 14
	sb.content_margin_top = 10
	sb.content_margin_bottom = 10
	add_theme_stylebox_override("panel", sb)
	custom_minimum_size = Vector2(360, 0)
	var v := VBoxContainer.new()
	v.add_theme_constant_override("separation", 6)
	add_child(v)
	var title := Label.new()
	title.text = "IA dos súditos"
	title.add_theme_font_size_override("font_size", 17)
	title.add_theme_color_override("font_color", Color(1, 0.86, 0.45))
	v.add_child(title)
	v.add_child(_caption("Quem pensa pelos súditos"))
	_provider = OptionButton.new()
	_provider.item_selected.connect(func(_i): _on_provider_changed())
	v.add_child(_provider)

	_models_row = VBoxContainer.new()
	v.add_child(_models_row)
	_models_row.add_child(_caption("Modelo dos súditos (conversa, agenda)"))
	_npc_model = OptionButton.new()
	_models_row.add_child(_npc_model)
	_models_row.add_child(_caption("Modelo do conselho (ordens, decisões raras)"))
	_council_model = OptionButton.new()
	_models_row.add_child(_council_model)

	v.add_child(_caption("Teto de gasto da sessão (US$, 0 = sem teto)"))
	_budget = SpinBox.new()
	_budget.min_value = 0.0
	_budget.max_value = 100.0
	_budget.step = 0.25
	_budget.custom_arrow_step = 0.25
	v.add_child(_budget)

	var row := HBoxContainer.new()
	row.add_theme_constant_override("separation", 6)
	v.add_child(row)
	row.add_child(_btn("Aplicar", _apply))
	row.add_child(_btn("Testar", _test))
	_login_btn = _btn("Entrar na conta", func(): bridge.send({"op": "ai_login"}))
	row.add_child(_login_btn)

	_msg = Label.new()
	_msg.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	_msg.custom_minimum_size = Vector2(330, 0)
	_msg.add_theme_font_size_override("font_size", 13)
	v.add_child(_msg)
	_footer = Label.new()
	_footer.autowrap_mode = TextServer.AUTOWRAP_WORD_SMART
	_footer.custom_minimum_size = Vector2(330, 0)
	_footer.add_theme_font_size_override("font_size", 12)
	_footer.add_theme_color_override("font_color", Color(0.75, 0.75, 0.65))
	v.add_child(_footer)


func _caption(t: String) -> Label:
	var l := Label.new()
	l.text = t
	l.add_theme_font_size_override("font_size", 12)
	l.add_theme_color_override("font_color", Color(0.75, 0.62, 0.3))
	return l


func _btn(t: String, cb: Callable) -> Button:
	var b := Button.new()
	b.text = t
	b.focus_mode = Control.FOCUS_NONE
	b.pressed.connect(cb)
	return b


func toggle() -> void:
	visible = not visible
	if visible:
		refresh()


func refresh() -> void:
	if bridge:
		bridge.send({"op": "ai_status"})


func _process(delta: float) -> void:
	_poll -= delta
	if _poll <= 0.0:
		_poll = POLL_SECONDS
		refresh()


func provider_id() -> String:
	if _provider.selected < 0:
		return ""
	return str(_provider.get_item_metadata(_provider.selected))


func _apply() -> void:
	var req := {"op": "ai_config", "provider": provider_id(), "budget_usd": _budget.value}
	if provider_id() == "claude_code":
		req["npc_model"] = _npc_model.get_item_text(_npc_model.selected)
		req["council_model"] = _council_model.get_item_text(_council_model.selected)
	_msg.text = "Aplicando…"
	bridge.send(req)


func _test() -> void:
	_msg.text = "Testando…"
	bridge.send({"op": "ai_test"})


func _on_provider_changed() -> void:
	if _loading:
		return
	_models_row.visible = provider_id() == "claude_code"
	_login_btn.visible = provider_id() == "claude_code"


func _fill_models(opt: OptionButton, models: Array, current: String) -> void:
	opt.clear()
	var sel := 0
	for i in models.size():
		opt.add_item(str(models[i]))
		if str(models[i]) == current:
			sel = i
	if models.size() > 0:
		opt.select(sel)


func _on_ai(msg: Dictionary) -> void:
	match str(msg.get("op", "")):
		"ai_status":
			_show_status(msg)
		"ai_test":
			_msg.text = str(msg.get("text", ""))
			_msg.add_theme_color_override("font_color", Color(0.56, 0.83, 0.56) if msg.get("ok", false) else Color(0.91, 0.56, 0.56))
			refresh()
		"ai_login":
			_msg.text = str(msg.get("text", ""))


func _show_status(msg: Dictionary) -> void:
	status = msg
	status_count += 1
	_loading = true
	var provs: Array = msg.get("providers", [])
	_provider.clear()
	var sel := 0
	for i in provs.size():
		_provider.add_item(str(provs[i].label))
		_provider.set_item_metadata(i, str(provs[i].id))
		if str(provs[i].id) == str(msg.get("provider", "")):
			sel = i
	if provs.size() > 0:
		_provider.select(sel)
	var claude := str(msg.get("provider", "")) == "claude_code"
	var models: Array = msg.get("models", [])
	if claude:
		_fill_models(_npc_model, models, str(msg.get("npc_model", "")))
		_fill_models(_council_model, models, str(msg.get("council_model", "")))
	_models_row.visible = claude
	_login_btn.visible = claude
	_budget.value = float(msg.get("budget_usd", 0.0))
	_loading = false
	if msg.has("ok"):
		if msg.get("ok", false):
			_msg.text = "Configuração aplicada."
			_msg.add_theme_color_override("font_color", Color(0.56, 0.83, 0.56))
		else:
			_msg.text = "✗ " + str(msg.get("error", "recusado"))
			_msg.add_theme_color_override("font_color", Color(0.91, 0.56, 0.56))
	elif _msg.text == "":
		_msg.text = str(msg.get("text", ""))
	var led: Dictionary = msg.get("ledger", {})
	var err := str(msg.get("last_error", ""))
	_footer.text = footer_text(led, err)
	var fb := int(led.get("fallbacks", 0))
	if fb > _last_fallbacks:
		fallback_seen.emit(fb)
	_last_fallbacks = fb


## "12 chamadas · US$ 0.0420 · última falha: …"
static func footer_text(led: Dictionary, last_error: String) -> String:
	var calls := int(led.get("calls", 0))
	var cost := float(led.get("cost_usd", 0.0))
	var s := "%d chamada(s) · gasto estimado US$ %.4f" % [calls, cost]
	var fb := int(led.get("fallbacks", 0))
	if fb > 0:
		s += " · %d pelas regras" % fb
	s += " · última falha: " + (last_error if last_error != "" else "nenhuma")
	return s
