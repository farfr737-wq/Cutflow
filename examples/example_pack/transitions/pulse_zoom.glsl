// Пульсирующий зум: простой пример своего перехода
vec4 transition(vec2 uv) {
    float s = sin(progress * PI);
    vec2 c = (uv - 0.5) / (1.0 + 0.5 * s) + 0.5;
    return mix(getFromColor(c), getToColor(c), smoothstep(0.35, 0.65, progress));
}
