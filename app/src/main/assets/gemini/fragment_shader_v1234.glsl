precision mediump float;
varying vec2 v_texCoord;
uniform float u_time;  // 外部传入的时间驱动变量（单位：秒）
uniform vec2 u_resolution; // 外部传入的手机屏幕实际分辨率 (width, height)

// 高级全屏无界摆动函数
vec2 fullScreenWiggle(float time, float freq, vec2 amp, vec2 seed) {
    float x = sin(time * freq + seed.x * 2.0) * amp.x + cos(time * freq * 0.8 + seed.y) * amp.x * 0.3;
    float y = cos(time * freq * 1.1 + seed.y * 2.0) * amp.y + sin(time * freq * 0.7 + seed.x) * amp.y * 0.3;
    return vec2(x + 0.5, y + 0.5);
}

// 图层 3：湍流置换（扭转）空间畸变
vec2 turbulentDisplace(vec2 uv, float time, float amount, float size) {
    float angle = time * (100.0 * 3.1415926 / 180.0);
    float freq = 4.0 * (300.0 / size);

    vec2 center = vec2(0.5);
    vec2 toCenter = uv - center;
    float distToCenter = length(toCenter);

    float waveX = sin(uv.y * freq + angle) * cos(uv.x * freq * 0.5 + angle * 0.6);
    float waveY = cos(uv.x * freq + angle * 1.1) * sin(uv.y * freq * 0.7 + angle * 0.4);

    float twistAngle = waveX * 0.5 + sin(distToCenter * freq - angle * 0.5);
    float s = sin(twistAngle);
    float c = cos(twistAngle);
    vec2 twistOffset = vec2(toCenter.x * c - toCenter.y * s, toCenter.x * s + toCenter.y * c) - toCenter;

    float strength = (amount / 100.0) * 0.12;
    return vec2(waveX, waveY) * strength * 0.4 + twistOffset * strength * 0.6;
}

// 叠加混合模式 (Overlay)
vec3 blendOverlay(vec3 base, vec3 blend) {
    return mix(
        2.0 * base * blend,
        1.0 - 2.0 * (1.0 - base) * (1.0 - blend),
        step(0.5, base)
    );
}

void main() {
    vec2 uv = v_texCoord;
    float speedMultiplier = 1.5;
    float t = u_time * speedMultiplier;
    float aspect = u_resolution.x / u_resolution.y;

    // 1. 应用图层 3 的湍流空间置换
    vec2 uvOffset = turbulentDisplace(uv, u_time, 108.0, 300.0);
    vec2 distortedUV = uv + uvOffset;
    vec2 uv_corr = vec2(distortedUV.x * aspect, distortedUV.y);

    // ==========================================
    // 【核心修改点】：拆分并重新映射图层 1 与图层 2 的色彩基底
    // ==========================================
    // 图层 1 基础四色 (对应主饱和度 +5 的清淡柔和状态)
    vec3 l1_c1 = vec3(0.827, 0.561, 1.000); // #D38FFF
    vec3 l1_c2 = vec3(0.600, 0.647, 1.000); // #99A5FF
    vec3 l1_c3 = vec3(0.549, 0.718, 1.000); // #8CB7FF
    vec3 l1_c4 = vec3(0.996, 0.871, 0.278); // #FEDE47

    // 图层 2 基础四色 (对齐 AE 右侧面板主饱和度强开 +55 后的真实色彩能量)
    // 调高色彩纯度，防止在四色权重分母中由于羽化扩散而被稀释或调和
    vec3 l2_c1 = vec3(0.710, 0.250, 1.000); // 饱和度爆发后的高纯度亮紫
    vec3 l2_c2 = vec3(0.250, 0.380, 1.000); // 饱和度爆发后的深邃紫蓝
    vec3 l2_c3 = vec3(0.100, 0.480, 1.000); // 饱和度爆发后的亮丽荧光极光蓝
    vec3 l2_c4 = vec3(1.000, 0.830, 0.050); // 饱和度爆发后的高饱和金黄
    // ==========================================

    // 设定能覆盖全屏的漫游范围幅值
    vec2 fullAmplitude = vec2(0.5);

    // ==========================================
    // 2. 【图层 1 计算 + 图层 4 全局柔化模糊集成】
    // ==========================================
    vec2 l1_p1 = fullScreenWiggle(t, 1.5, fullAmplitude * 0.95, vec2(11.4, 34.5));
    vec2 l1_p2 = fullScreenWiggle(t, 2.0, fullAmplitude * 0.95, vec2(52.1, 87.3));
    vec2 l1_p3 = fullScreenWiggle(t, 2.0, fullAmplitude * 0.95, vec2(93.5, 12.8));
    vec2 l1_p4 = fullScreenWiggle(t, 1.7, fullAmplitude * 0.95, vec2(44.2, 69.9));

    float l1_d1 = length(uv_corr - vec2(l1_p1.x * aspect, l1_p1.y));
    float l1_d2 = length(uv_corr - vec2(l1_p2.x * aspect, l1_p2.y));
    float l1_d3 = length(uv_corr - vec2(l1_p3.x * aspect, l1_p3.y));
    float l1_d4 = length(uv_corr - vec2(l1_p4.x * aspect, l1_p4.y));

    float l1_blendPower = 0.95;
    float l1_baseRadius = 0.25;

    float l1_w1 = 1.0 / (pow(l1_d1, l1_blendPower) + l1_baseRadius);
    float l1_w2 = 1.0 / (pow(l1_d2, l1_blendPower) + l1_baseRadius);
    float l1_w3 = 1.0 / (pow(l1_d3, l1_blendPower) + l1_baseRadius);
    float l1_w4 = 1.0 / (pow(l1_d4, l1_blendPower) + l1_baseRadius);

    // 【修改】：使用图层 1 专属的颜色变量
    vec3 colorLayer1 = (l1_c1 * l1_w1 + l1_c2 * l1_w2 + l1_c3 * l1_w3 + l1_c4 * l1_w4) / (l1_w1 + l1_w2 + l1_w3 + l1_w4);

    // 图层 1 主饱和度 (+5)
    float luma1 = dot(colorLayer1, vec3(0.299, 0.587, 0.114));
    colorLayer1 = mix(vec3(luma1), colorLayer1, 1.05);


    // ==========================================
    // 3. 【图层 2 计算 + 图层 4 全局柔化模糊集成】
    // ==========================================
    vec2 l2_p1 = fullScreenWiggle(t, 3.0, fullAmplitude * 0.95, vec2(101.3, 134.7));
    vec2 l2_p2 = fullScreenWiggle(t, 2.0, fullAmplitude * 0.95, vec2(188.5, 112.3));
    vec2 l2_p3 = fullScreenWiggle(t, 2.3, fullAmplitude * 0.95, vec2(141.7, 176.2));
    vec2 l2_p4 = fullScreenWiggle(t, 1.7, fullAmplitude * 0.95, vec2(119.3, 154.8));

    float l2_d1 = length(uv_corr - vec2(l2_p1.x * aspect, l2_p1.y));
    float l2_d2 = length(uv_corr - vec2(l2_p2.x * aspect, l2_p2.y));
    float l2_d3 = length(uv_corr - vec2(l2_p3.x * aspect, l2_p3.y));
    float l2_d4 = length(uv_corr - vec2(l2_p4.x * aspect, l2_p4.y));

    float l2_blendPower = 1.0;
    float l2_baseRadius = 0.18;

    float l2_w1 = 1.0 / (pow(l2_d1, l2_blendPower) + l2_baseRadius);
    float l2_w2 = 1.0 / (pow(l2_d2, l2_blendPower) + l2_baseRadius);
    float l2_w3 = 1.0 / (pow(l2_d3, l2_blendPower) + l2_baseRadius);
    float l2_w4 = 1.0 / (pow(l2_d4, l2_blendPower) + l2_baseRadius);

    // 【修改】：使用图层 2 专属的高饱和颜色变量，彻底解决“图层2用错底色”的硬伤
    vec3 colorLayer2 = (l2_c1 * l2_w1 + l2_c2 * l2_w2 + l2_c3 * l2_w3 + l2_c4 * l2_w4) / (l2_w1 + l2_w2 + l2_w3 + l2_w4);

    // 图层 2 高饱和度强开 (+55)
    float luma2 = dot(colorLayer2, vec3(0.299, 0.587, 0.114));
    colorLayer2 = mix(vec3(luma2), colorLayer2, 1.55);


    // ==========================================
    // 4. 【多图层终极融合与去断层】
    // ==========================================
    vec3 finalColor = blendOverlay(colorLayer1, colorLayer2);

    // 80% Dither 去除大模糊后极易出现的色彩带（Color Banding）
    float noise = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    finalColor += (noise - 0.5) * (0.80 / 255.0);

    gl_FragColor = vec4(finalColor, 1.0);
}