package com.cutflow.app

data class TrBuiltin(val id: String, val name: String, val body: String)

/**
 * Переходы — это GLSL-функции в формате gl-transitions (https://gl-transitions.com):
 *   vec4 transition(vec2 uv) { ... getFromColor(uv) / getToColor(uv) / progress / ratio ... }
 * Любой переход из gl-transitions можно положить в zip-пак как есть.
 */
object Transitions {
    const val VERT = """
attribute vec2 aPos;
attribute vec2 aUv;
varying vec2 vUv;
void main() { gl_Position = vec4(aPos, 0.0, 1.0); vUv = aUv; }
"""

    fun fragment(body: String): String {
        val definesRand = Regex("float\\s+rand\\s*\\(").containsMatchIn(body)
        val definesPi = Regex("(const\\s+float|#define)\\s+PI\\b").containsMatchIn(body)
        return buildString {
            append("precision highp float;\n")
            append("varying vec2 vUv;\nuniform sampler2D uFrom;\nuniform sampler2D uTo;\n")
            append("uniform float progress;\nuniform float ratio;\n")
            if (!definesPi) append("const float PI = 3.141592653589793;\n")
            append("vec4 getFromColor(vec2 uv) { return texture2D(uFrom, uv); }\n")
            append("vec4 getToColor(vec2 uv) { return texture2D(uTo, uv); }\n")
            if (!definesRand) append("float rand(vec2 co) { return fract(sin(dot(co.xy, vec2(12.9898, 78.233))) * 43758.5453); }\n")
            append(body).append("\n")
            append("void main() { gl_FragColor = vec4(transition(vUv).rgb, 1.0); }\n")
        }
    }

    private const val CUBE = """
vec4 cubeT(vec2 uv, float fx) {
    vec2 u = uv;
    if (fx > 0.5) u.x = 1.0 - u.x;
    float th = progress * 1.5707963;
    float c = cos(th);
    float s = sin(th);
    float w = 0.5 * ratio;
    float D = 1.2;
    float e = 0.9 * sin(progress * PI);
    vec2 p = (u - 0.5) * vec2(ratio, 1.0);
    vec3 O = vec3(0.0, 0.0, w + D + e);
    vec3 dir = vec3(p / D, -1.0);
    vec3 Ol = vec3(O.x * c + O.z * s, O.y, -O.x * s + O.z * c);
    vec3 dl = vec3(dir.x * c + dir.z * s, dir.y, -dir.x * s + dir.z * c);
    float bestT = 1.0e9;
    vec4 col = vec4(0.015, 0.015, 0.03, 1.0);
    if (abs(dl.z) > 1.0e-5) {
        float t = (w - Ol.z) / dl.z;
        if (t > 0.0) {
            vec3 h = Ol + dl * t;
            if (abs(h.x) <= w && abs(h.y) <= 0.5) {
                bestT = t;
                vec2 q = vec2((h.x + w) / (2.0 * w), h.y + 0.5);
                if (fx > 0.5) q.x = 1.0 - q.x;
                col = getFromColor(q) * mix(1.0, 0.45, progress);
            }
        }
    }
    if (abs(dl.x) > 1.0e-5) {
        float t = (w - Ol.x) / dl.x;
        if (t > 0.0 && t < bestT) {
            vec3 h = Ol + dl * t;
            if (abs(h.z) <= w && abs(h.y) <= 0.5) {
                vec2 q = vec2((w - h.z) / (2.0 * w), h.y + 0.5);
                if (fx > 0.5) q.x = 1.0 - q.x;
                col = getToColor(q) * mix(0.45, 1.0, progress);
            }
        }
    }
    return col;
}
"""

    val all: List<TrBuiltin> = listOf(
        TrBuiltin("fade", "Затухание", "vec4 transition(vec2 uv) { return mix(getFromColor(uv), getToColor(uv), progress); }"),
        TrBuiltin("fadeblack", "Через чёрный",
            "vec4 transition(vec2 uv) { vec4 k = vec4(0.0, 0.0, 0.0, 1.0); return mix(mix(getFromColor(uv), k, smoothstep(0.0, 0.5, progress)), getToColor(uv), smoothstep(0.5, 1.0, progress)); }"),
        TrBuiltin("fadewhite", "Вспышка",
            "vec4 transition(vec2 uv) { vec4 k = vec4(1.0); return mix(mix(getFromColor(uv), k, smoothstep(0.0, 0.5, progress)), getToColor(uv), smoothstep(0.5, 1.0, progress)); }"),
        TrBuiltin("cube_left", "3D куб ←", CUBE + "vec4 transition(vec2 uv) { return cubeT(uv, 0.0); }"),
        TrBuiltin("cube_right", "3D куб →", CUBE + "vec4 transition(vec2 uv) { return cubeT(uv, 1.0); }"),
        TrBuiltin("flip", "3D переворот карты", """
vec4 transition(vec2 uv) {
    float a = progress * PI;
    float c = cos(a);
    float w = abs(c);
    vec4 bg = vec4(0.02, 0.02, 0.04, 1.0);
    if (w < 0.002) return bg;
    float k = 1.0 + 0.35 * (1.0 - w) * (uv.x - 0.5) * 2.0 * (c > 0.0 ? 1.0 : -1.0);
    vec2 p = vec2((uv.x - 0.5) / w + 0.5, (uv.y - 0.5) / k + 0.5);
    if (p.x < 0.0 || p.x > 1.0 || p.y < 0.0 || p.y > 1.0) return bg;
    if (progress < 0.5) return getFromColor(p);
    return getToColor(vec2(1.0 - p.x, p.y));
}"""),
        TrBuiltin("wipeleft", "Шторка ←", "vec4 transition(vec2 uv) { return uv.x < 1.0 - progress ? getFromColor(uv) : getToColor(uv); }"),
        TrBuiltin("wiperight", "Шторка →", "vec4 transition(vec2 uv) { return uv.x > progress ? getFromColor(uv) : getToColor(uv); }"),
        TrBuiltin("wipeup", "Шторка ↑", "vec4 transition(vec2 uv) { return uv.y < 1.0 - progress ? getFromColor(uv) : getToColor(uv); }"),
        TrBuiltin("wipedown", "Шторка ↓", "vec4 transition(vec2 uv) { return uv.y > progress ? getFromColor(uv) : getToColor(uv); }"),
        TrBuiltin("slideleft", "Сдвиг ←", """
vec4 transition(vec2 uv) {
    float x = uv.x + progress;
    return x < 1.0 ? getFromColor(vec2(x, uv.y)) : getToColor(vec2(x - 1.0, uv.y));
}"""),
        TrBuiltin("slideright", "Сдвиг →", """
vec4 transition(vec2 uv) {
    float x = uv.x - progress;
    return x >= 0.0 ? getFromColor(vec2(x, uv.y)) : getToColor(vec2(x + 1.0, uv.y));
}"""),
        TrBuiltin("slideup", "Сдвиг ↑", """
vec4 transition(vec2 uv) {
    float y = uv.y + progress;
    return y < 1.0 ? getFromColor(vec2(uv.x, y)) : getToColor(vec2(uv.x, y - 1.0));
}"""),
        TrBuiltin("slidedown", "Сдвиг ↓", """
vec4 transition(vec2 uv) {
    float y = uv.y - progress;
    return y >= 0.0 ? getFromColor(vec2(uv.x, y)) : getToColor(vec2(uv.x, y + 1.0));
}"""),
        TrBuiltin("coverleft", "Накрытие ←", """
vec4 transition(vec2 uv) {
    float edge = 1.0 - progress;
    return uv.x > edge ? getToColor(vec2(uv.x - edge, uv.y)) : getFromColor(uv);
}"""),
        TrBuiltin("revealleft", "Раскрытие ←", """
vec4 transition(vec2 uv) {
    float x = uv.x + progress;
    return x < 1.0 ? getFromColor(vec2(x, uv.y)) : getToColor(uv);
}"""),
        TrBuiltin("zoomin", "Наезд", """
vec4 transition(vec2 uv) {
    float z = 1.0 + progress * 1.2;
    vec4 f = getFromColor((uv - 0.5) / z + 0.5);
    return mix(f, getToColor(uv), smoothstep(0.35, 1.0, progress));
}"""),
        TrBuiltin("zoomout", "Отъезд", """
vec4 transition(vec2 uv) {
    float z = 1.0 + (1.0 - progress) * 0.8;
    vec4 t = getToColor((uv - 0.5) / z + 0.5);
    return mix(getFromColor(uv), t, smoothstep(0.0, 0.65, progress));
}"""),
        TrBuiltin("crosszoom", "Зум-размытие", """
vec4 transition(vec2 uv) {
    vec2 d = uv - 0.5;
    float strength = sin(progress * PI) * 0.6;
    vec4 c = vec4(0.0);
    float tot = 0.0;
    for (int i = 0; i < 12; i++) {
        float t = float(i) / 11.0;
        float w = 1.0 - abs(t - 0.5);
        vec2 q = uv - d * strength * t;
        c += mix(getFromColor(q), getToColor(q), progress) * w;
        tot += w;
    }
    return c / tot;
}"""),
        TrBuiltin("circleopen", "Круг открывается", """
vec4 transition(vec2 uv) {
    float d = length((uv - 0.5) * vec2(ratio, 1.0));
    float r = progress * (0.5 * sqrt(ratio * ratio + 1.0) + 0.05);
    return mix(getFromColor(uv), getToColor(uv), 1.0 - smoothstep(r - 0.02, r + 0.02, d));
}"""),
        TrBuiltin("circleclose", "Круг закрывается", """
vec4 transition(vec2 uv) {
    float d = length((uv - 0.5) * vec2(ratio, 1.0));
    float r = (1.0 - progress) * (0.5 * sqrt(ratio * ratio + 1.0) + 0.05);
    return mix(getFromColor(uv), getToColor(uv), smoothstep(r - 0.02, r + 0.02, d));
}"""),
        TrBuiltin("radial", "Часовая стрелка", """
vec4 transition(vec2 uv) {
    float ang = atan(uv.x - 0.5, uv.y - 0.5) + PI;
    return ang < progress * 2.0 * PI ? getToColor(uv) : getFromColor(uv);
}"""),
        TrBuiltin("dreamy", "Волна", """
vec4 transition(vec2 uv) {
    float s = sin(progress * PI);
    vec2 o = vec2(sin(uv.y * 10.0 + progress * 6.0), cos(uv.x * 10.0 + progress * 6.0)) * 0.03 * s;
    return mix(getFromColor(uv + o), getToColor(uv - o), progress);
}"""),
        TrBuiltin("pixelize", "Пикселизация", """
vec4 transition(vec2 uv) {
    float s = sin(progress * PI);
    float n = mix(400.0, 14.0, s);
    vec2 g = vec2(n, n / ratio);
    vec2 p = (floor(uv * g) + 0.5) / g;
    return mix(getFromColor(p), getToColor(p), progress);
}"""),
        TrBuiltin("swirl", "Водоворот", """
vec4 transition(vec2 uv) {
    vec2 c = uv - 0.5;
    float r = length(c * vec2(ratio, 1.0));
    float a = (1.0 - smoothstep(0.0, 0.7, r)) * sin(progress * PI) * 6.0;
    vec2 q = vec2(c.x * cos(a) - c.y * sin(a), c.x * sin(a) + c.y * cos(a)) + 0.5;
    return mix(getFromColor(q), getToColor(q), progress);
}"""),
        TrBuiltin("ripple", "Рябь", """
vec4 transition(vec2 uv) {
    vec2 d = uv - 0.5;
    float r = length(d);
    vec2 o = normalize(d + vec2(1.0e-5)) * sin(r * 40.0 - progress * 20.0) * 0.03 * sin(progress * PI);
    return mix(getFromColor(uv + o), getToColor(uv + o), progress);
}"""),
        TrBuiltin("glitch", "Глитч", """
vec4 transition(vec2 uv) {
    float s = sin(progress * PI);
    float band = floor(uv.y * 20.0);
    float rnd = rand(vec2(band, floor(progress * 12.0)));
    vec2 o = vec2((rnd - 0.5) * 0.15 * s * step(0.6, rnd), 0.0);
    vec2 sp = vec2(0.015 * s, 0.0);
    float r = mix(getFromColor(uv + o + sp).r, getToColor(uv + o + sp).r, progress);
    float g = mix(getFromColor(uv + o).g, getToColor(uv + o).g, progress);
    float b = mix(getFromColor(uv + o - sp).b, getToColor(uv + o - sp).b, progress);
    return vec4(r, g, b, 1.0);
}"""),
        TrBuiltin("blinds", "Жалюзи", "vec4 transition(vec2 uv) { return fract(uv.x * 10.0) < progress ? getToColor(uv) : getFromColor(uv); }"),
        TrBuiltin("mosaic", "Мозаика", """
vec4 transition(vec2 uv) {
    vec2 g = vec2(16.0, 16.0 / ratio);
    float r = rand(floor(uv * g));
    return step(r, progress * 1.25 - 0.1) > 0.5 ? getToColor(uv) : getFromColor(uv);
}"""),
        TrBuiltin("spin", "Вращение", """
vec4 transition(vec2 uv) {
    float a = progress * 1.5;
    float z = 1.0 + progress * 1.5;
    vec2 c = (uv - 0.5) * vec2(ratio, 1.0);
    vec2 q = vec2(cos(a) * c.x - sin(a) * c.y, sin(a) * c.x + cos(a) * c.y) / z;
    q.x /= ratio;
    return mix(getFromColor(q + 0.5), getToColor(uv), smoothstep(0.2, 0.9, progress));
}"""),
        TrBuiltin("diagonal", "Диагональ", "vec4 transition(vec2 uv) { return (uv.x + uv.y) * 0.5 < progress * 1.2 - 0.1 ? getToColor(uv) : getFromColor(uv); }"),
        TrBuiltin("split", "Двери", "vec4 transition(vec2 uv) { return abs(uv.x - 0.5) * 2.0 < progress ? getToColor(uv) : getFromColor(uv); }"),
        TrBuiltin("stripes", "Полосы", """
vec4 transition(vec2 uv) {
    float row = floor(uv.y * 8.0);
    float dir = mod(row, 2.0) < 1.0 ? 1.0 : -1.0;
    float x = uv.x + dir * progress;
    return (x >= 0.0 && x <= 1.0) ? getFromColor(vec2(x, uv.y)) : getToColor(uv);
}"""),
        TrBuiltin("dissolve", "Растворение", """
vec4 transition(vec2 uv) {
    float n = rand(floor(uv * vec2(240.0, 240.0 / ratio)));
    return n < progress ? getToColor(uv) : getFromColor(uv);
}"""),
    )
}
