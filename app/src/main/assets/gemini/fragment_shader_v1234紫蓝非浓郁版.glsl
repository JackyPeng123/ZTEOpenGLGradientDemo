precision highp float;
varying vec2 v_texCoord;
uniform float u_time;
uniform vec2 u_resolution;

// 高级全屏摆动：为了让黄色能在全屏跑，同时保持视频中的流体大轨迹
vec2 fullScreenWiggle(float time, float freq, vec2 amp, vec2 seed) {
    float x = sin(time * freq + seed.x * 2.1) * amp.x + cos(time * freq * 0.72 + seed.y) * amp.x * 0.35;
    float y = cos(time * freq * 1.15 + seed.y * 1.9) * amp.y + sin(time * freq * 0.63 + seed.x) * amp.y * 0.35;
    return vec2(x + 0.5, y + 0.5);
}

// 图层 3：重新校准为“大波浪、粘稠流体”的湍流置换
vec2 turbulentDisplace(vec2 uv, float time, float amount, float size) {
    float angle = time * (100.0 * 3.1415926 / 180.0) * 0.5; // 稍微放缓演化速度，增加粘稠感

    // 大幅降低频率（从4.0降到1.1），让置换变成“大块流体”而不是小水波
    float freq = 1.1 * (300.0 / size);

    vec2 center = vec2(0.5);
    vec2 toCenter = uv - center;

    // 制造大范围的流体拉伸和旋涡
    float waveX = sin(uv.y * freq + angle) * cos(uv.x * freq * 0.4 + angle * 0.5);
    float waveY = cos(uv.x * freq + angle * 0.9) * sin(uv.y * freq * 0.5 + angle * 0.3);

    float twistAngle = waveX * 0.7 + sin(length(toCenter) * freq - angle);
    float s = sin(twistAngle);
    float c = cos(twistAngle);
    vec2 twistOffset = vec2(toCenter.x * c - toCenter.y * s, toCenter.x * s + toCenter.y * c) - toCenter;

    float strength = (amount / 100.0) * 0.15;
    return vec2(waveX, waveY) * strength * 0.3 + twistOffset * strength * 0.7;
}

// 叠加混合模式 (Overlay)
vec3 blendOverlay(vec3 base, vec3 blend) {
    return mix(
        2.0 * base * blend,
        1.0 - 2.0 * (1.0 - base) * (1.0 - blend),
        step(0.5, base)
    );
}

// 核心色彩生成函数：找回视频里的浓郁纯正色彩
vec3 evaluateFourColor(vec2 uv_corr, float aspect, vec2 p1, vec2 p2, vec2 p3, vec2 p4, vec3 c1, vec3 c2, vec3 c3, vec3 c4, float isLayer2) {
    float d1 = length(uv_corr - vec2(p1.x * aspect, p1.y));
    float d2 = length(uv_corr - vec2(p2.x * aspect, p2.y));
    float d3 = length(uv_corr - vec2(p3.x * aspect, p3.y));
    float d4 = length(uv_corr - vec2(p4.x * aspect, p4.y));

    // 暴拉幂次（2.2），极度压缩底数（0.015），确保紫蓝色彩极度浓郁显眼！
    float blendPower = isLayer2 > 0.5 ? 2.3 : 2.0;
    float baseRadius = 0.015;

    float w1 = 1.0 / (pow(d1, blendPower) + baseRadius);
    float w2 = 1.0 / (pow(d2, blendPower) + baseRadius);
    float w3 = 1.0 / (pow(d3, blendPower) + baseRadius);
    float w4 = 1.0 / (pow(d4, blendPower) + baseRadius);

    // 给黄色(w4)稍微补一点大范围权重，保证它全屏跑的时候不会轻易被紫蓝吞掉
    return (c1 * w1 + c2 * w2 + c3 * w3 + c4 * w4 * 1.15) / (w1 + w2 + w3 + w4 * 1.15);
}

void main() {
    vec2 uv = v_texCoord;
    float speedMultiplier = 1.3; // 逼近视频里的流动速率
    float t = mod(u_time * speedMultiplier, 314.159265);
    float aspect = u_resolution.x / u_resolution.y;

    // 基础四色定义 (用于图层 1，对应 AE 饱和度仅为 5 的清淡状态)
    vec3 c1 = vec3(0.827, 0.561, 1.000); // #D38FFF (纯正亮紫)
    vec3 c2 = vec3(0.600, 0.647, 1.000); // #99A5FF (紫蓝)
    vec3 c3 = vec3(0.549, 0.718, 1.000); // #8CB7FF (纯正极光蓝)
    vec3 c4 = vec3(0.996, 0.871, 0.278); // #FEDE47 (温暖黄)

    // ==========================================
    // 【核心新增】：定义图层 2 挂载了“主饱和度+55”滤镜后的高纯度深邃色彩基底
    // ==========================================
    vec3 l2_c1 = vec3(0.710, 0.250, 1.000); // 饱和度爆发后的纯正浓郁紫
    vec3 l2_c2 = vec3(0.250, 0.380, 1.000); // 饱和度爆发后的深邃紫蓝
    vec3 l2_c3 = vec3(0.100, 0.480, 1.000); // 饱和度爆发后的深宝石蓝
    vec3 l2_c4 = vec3(1.000, 0.830, 0.050); // 饱和度爆发后的黄金色
    // ==========================================

    // 动态路径生成 (保持全屏幕漫游)
    vec2 fullAmplitude = vec2(0.53);

    // 图层 1 点位路径
    vec2 l1_p1 = fullScreenWiggle(t, 1.2, fullAmplitude * 0.95, vec2(11.4, 34.5));
    vec2 l1_p2 = fullScreenWiggle(t, 1.6, fullAmplitude * 0.95, vec2(52.1, 87.3));
    vec2 l1_p3 = fullScreenWiggle(t, 1.5, fullAmplitude * 0.95, vec2(93.5, 12.8));
    vec2 l1_p4 = fullScreenWiggle(t, 1.3, fullAmplitude * 0.95, vec2(44.2, 69.9));

    // 图层 2 点位路径 (错位)
    vec2 l2_p1 = fullScreenWiggle(t, 2.2, fullAmplitude * 0.95, vec2(101.3, 134.7));
    vec2 l2_p2 = fullScreenWiggle(t, 1.5, fullAmplitude * 0.95, vec2(188.5, 112.3));
    vec2 l2_p3 = fullScreenWiggle(t, 1.8, fullAmplitude * 0.95, vec2(141.7, 176.2));
    vec2 l2_p4 = fullScreenWiggle(t, 1.3, fullAmplitude * 0.95, vec2(119.3, 154.8));

    // =========================================================
    // 【图层 4 物理大模糊模拟】：采用 2 路轻量微移采样
    // =========================================================
    float blurScale = 0.015; // 对应 70px 在 UV 空间的模糊半径

    // 采样点 A（向左下错位置换）
    vec2 uvA = uv + turbulentDisplace(uv, u_time, 108.0, 300.0);
    vec2 uvA_corr = vec2((uvA.x - blurScale) * aspect, uvA.y - blurScale);
    vec3 l1_A = evaluateFourColor(uvA_corr, aspect, l1_p1, l1_p2, l1_p3, l1_p4, c1, c2, c3, c4, 0.0);

    // 【核心修复】：图层 2 传入专属的高饱和色卡 l2_c1 ~ l2_c4
    vec3 l2_A = evaluateFourColor(uvA_corr, aspect, l2_p1, l2_p2, l2_p3, l2_p4, l2_c1, l2_c2, l2_c3, l2_c4, 1.0);
    l2_A = mix(vec3(dot(l2_A, vec3(0.299, 0.587, 0.114))), l2_A, 1.55); // 图层2的高饱和
    vec3 colorSampleA = blendOverlay(l1_A, l2_A);

    // 采样点 B（向右上错位置换）
    vec2 uvB = uv + turbulentDisplace(uv + vec2(0.01), u_time, 108.0, 300.0);
    vec2 uvB_corr = vec2((uvB.x + blurScale) * aspect, uvB.y + blurScale);
    vec3 l1_B = evaluateFourColor(uvB_corr, aspect, l1_p1, l1_p2, l1_p3, l1_p4, c1, c2, c3, c4, 0.0);

    // 【核心修复】：图层 2 传入专属的高饱和色卡 l2_c1 ~ l2_c4
    vec3 l2_B = evaluateFourColor(uvB_corr, aspect, l2_p1, l2_p2, l2_p3, l2_p4, l2_c1, l2_c2, l2_c3, l2_c4, 1.0);
    l2_B = mix(vec3(dot(l2_B, vec3(0.299, 0.587, 0.114))), l2_B, 1.55); // 图层2的高饱和
    vec3 colorSampleB = blendOverlay(l1_B, l2_B);

    // 模糊混合
    vec3 finalColor = mix(colorSampleA, colorSampleB, 0.5);

    // 全局柔和后处理：图层 1 的基础饱和度微调 (+5)
    float luma = dot(finalColor, vec3(0.299, 0.587, 0.114));
    finalColor = mix(vec3(luma), finalColor, 1.08);

    // 80% Dither 去断层噪声
    float noise = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    finalColor += (noise - 0.5) * (0.80 / 255.0);

    gl_FragColor = vec4(finalColor, 1.0);
}