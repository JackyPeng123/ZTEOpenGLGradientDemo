precision highp float;
varying vec2 v_texCoord;
uniform vec2 u_resolution;

void main() {
    // 【调整颜色】：更接近目标图的“粉彩/奶油”质感，高明度、低饱和
    vec3 c1 = vec3(0.70, 0.85, 0.95); // 顶部：极淡的冰蓝色，几乎泛白
    vec3 c2 = vec3(0.60, 0.75, 0.95); // 中上：柔和的浅蓝
    vec3 c3 = vec3(0.45, 0.60, 0.90); // 中下：带一点灰度的蓝
    vec3 c4 = vec3(0.35, 0.45, 0.85); // 底部：沉稳的灰紫蓝

    vec2 px = v_texCoord * u_resolution;
    vec2 uv = px / u_resolution.x;
    float aspect = u_resolution.y / u_resolution.x;

    // 根据“均等分布”原则，将中心点设在高度的 1/8, 3/8, 5/8, 7/8 处
    vec2 p1 = vec2(0.5, (1.0 - 0.125) * aspect);
    vec2 p2 = vec2(0.5, (1.0 - 0.375) * aspect);
    vec2 p3 = vec2(0.5, (1.0 - 0.625) * aspect);
    vec2 p4 = vec2(0.5, (1.0 - 0.875) * aspect);

    float d1 = length(uv - p1);
    float d2 = length(uv - p2);
    float d3 = length(uv - p3);
    float d4 = length(uv - p4);

    float power = 4.0;
    float w1 = 1.0 / (pow(d1, power) + 0.0001);
    float w2 = 1.0 / (pow(d2, power) + 0.0001);
    float w3 = 1.0 / (pow(d3, power) + 0.0001);
    float w4 = 1.0 / (pow(d4, power) + 0.0001);

    vec3 gradColor = (c1 * w1 + c2 * w2 + c3 * w3 + c4 * w4) / (w1 + w2 + w3 + w4);

    // 稍微提亮中间调，保持通透感
    gradColor = gradColor * (1.3 - gradColor * 0.5);

    gl_FragColor = vec4(gradColor, 1.0);
}