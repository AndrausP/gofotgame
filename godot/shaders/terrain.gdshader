shader_type spatial;
render_mode cull_back;

// Cor do bloco (com a oclusao ambiente ja assada pelo mesher) + variacao por bloco e por "texel", para o chao nao parecer plastico.
varying vec3 wpos;
varying vec3 wnrm;

vec3 srgb_to_lin(vec3 c) {
	return mix(pow((c + vec3(0.055)) / 1.055, vec3(2.4)), c / 12.92, lessThan(c, vec3(0.04045)));
}

float hash3(vec3 p) {
	p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3));
	p *= 17.0;
	return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

void vertex() {
	wpos = (MODEL_MATRIX * vec4(VERTEX, 1.0)).xyz;
	wnrm = normalize((MODEL_MATRIX * vec4(NORMAL, 0.0)).xyz);
}

void fragment() {
	vec3 c = srgb_to_lin(COLOR.rgb);
	vec3 p = wpos - wnrm * 0.5;
	float block_n = hash3(floor(p));
	float texel_n = hash3(floor(p * 4.0));
	float top = clamp(wnrm.y, 0.0, 1.0);
	c *= 0.86 + 0.16 * block_n + 0.07 * (texel_n - 0.5);
	c *= 1.0 + top * 0.06;
	ALBEDO = c;
	ROUGHNESS = 0.93;
	SPECULAR = 0.12;
}
