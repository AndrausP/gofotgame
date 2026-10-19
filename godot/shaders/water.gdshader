shader_type spatial;
render_mode blend_mix, depth_draw_always, cull_back;

varying float is_top;

vec3 srgb_to_lin(vec3 c) {
	return mix(pow((c + vec3(0.055)) / 1.055, vec3(2.4)), c / 12.92, lessThan(c, vec3(0.04045)));
}

void vertex() {
	is_top = step(0.5, NORMAL.y);
	vec3 w = (MODEL_MATRIX * vec4(VERTEX, 1.0)).xyz;
	float wave = sin(w.x * 1.3 + TIME * 1.6) + sin(w.z * 1.1 + TIME * 1.3) + sin((w.x + w.z) * 0.7 + TIME * 0.9);
	VERTEX.y += is_top * (wave * 0.025 - 0.09);
}

void fragment() {
	vec3 base = srgb_to_lin(COLOR.rgb);
	ALBEDO = mix(base, vec3(0.05, 0.25, 0.45), 0.35);
	ALPHA = mix(0.55, 0.78, 1.0 - is_top);
	ROUGHNESS = 0.04;
	METALLIC = 0.0;
	SPECULAR = 0.8;
	EMISSION = ALBEDO * 0.05;
}
