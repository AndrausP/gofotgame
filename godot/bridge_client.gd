class_name BridgeClient
extends Node
## Cliente da ponte Godot ↔ Core (NDJSON em TCP local). Só traduz: o Core decide tudo.

signal hello(msg: Dictionary)
signal state(msg: Dictionary)
signal lines(msg: Dictionary)
signal chunk(msg: Dictionary)
signal blocks(msg: Dictionary)
signal fx(msg: Dictionary)
signal connection_changed(connected: bool)

var host := "127.0.0.1"
var port := 7777

var _peer := StreamPeerTCP.new()
var _buf := PackedByteArray()
var _was_connected := false
var _retry := 0.0


func _ready() -> void:
	_connect()


func _connect() -> void:
	_peer = StreamPeerTCP.new()
	_buf = PackedByteArray()
	_peer.connect_to_host(host, port)


func is_up() -> bool:
	return _peer.get_status() == StreamPeerTCP.STATUS_CONNECTED


func send(msg: Dictionary) -> void:
	if not is_up():
		return
	_peer.put_data((JSON.stringify(msg) + "\n").to_utf8_buffer())


func command(line: String) -> void:
	send({"op": "cmd", "line": line})


func _process(delta: float) -> void:
	_peer.poll()
	var up := is_up()
	if up != _was_connected:
		_was_connected = up
		connection_changed.emit(up)
	var status := _peer.get_status()
	if status == StreamPeerTCP.STATUS_NONE or status == StreamPeerTCP.STATUS_ERROR:
		_retry -= delta
		if _retry <= 0.0:
			_retry = 2.0
			_connect()
		return
	if not up:
		return
	var n := _peer.get_available_bytes()
	if n <= 0:
		return
	var got := _peer.get_data(n)
	if got[0] != OK:
		return
	_buf.append_array(got[1])
	while true:
		var i := _buf.find(10)
		if i < 0:
			break
		var text := _buf.slice(0, i).get_string_from_utf8()
		_buf = _buf.slice(i + 1)
		var parsed: Variant = JSON.parse_string(text)
		if typeof(parsed) == TYPE_DICTIONARY:
			_dispatch(parsed)


func _dispatch(msg: Dictionary) -> void:
	match msg.get("op", ""):
		"hello": hello.emit(msg)
		"state": state.emit(msg)
		"chunk": chunk.emit(msg)
		"blocks": blocks.emit(msg)
		"fx": fx.emit(msg)
		"reply", "async", "error": lines.emit(msg)
