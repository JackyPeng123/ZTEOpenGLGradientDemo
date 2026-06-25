precision highp float;
varying vec2 v_texCoord;
uniform float u_time;
uniform vec2 u_resolution;

// 高级全屏摆动：黄色全屏跑，同时保持大轨迹
vec2 fullScreenWiggle(float time, float freq, vec2 amp, vec2 seed) {
    float x = sin(time * freq + seed.x * 2.1) * amp.x + cos(time * freq * 0.72 + seed.y) * amp.x * 0.35;
    float y = cos(time * freq * 1.15 + seed.y * 1.9) * amp.y + sin(time * freq * 0.63 + seed.x) * amp.y * 0.35;
    return vec2(x + 0.5, y + 0.5);
}

// 图层 3：粘稠大波浪湍流置换
vec2 turbulentDisplace(vec2 uv, float time, float amount, float size) {
    float angle = time * (100.0 * 3.1415926 / 180.0) * 0.5;
    float freq = 1.1 * (300.0 / size);

    vec2 center = vec2(0.5);
    vec2 toCenter = uv - center;

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

// 核心四色渐变函数
vec3 evaluateFourColor(vec2 uv_corr, float aspect, vec2 p1, vec2 p2, vec2 p3, vec2 p4, vec3 c1, vec3 c2, vec3 c3, vec3 c4) {
    float d1 = length(uv_corr - vec2(p1.x * aspect, p1.y));
    float d2 = length(uv_corr - vec2(p2.x * aspect, p2.y));
    float d3 = length(uv_corr - vec2(p3.x * aspect, p3.y));
    float d4 = length(uv_corr - vec2(p4.x * aspect, p4.y));

    float blendPower = 2.0;
    float baseRadius = 0.008;

    float w1 = 1.0 / (pow(d1, blendPower) + baseRadius);
    float w2 = 1.0 / (pow(d2, blendPower) + baseRadius);
    float w3 = 1.0 / (pow(d3, blendPower) + baseRadius);
    float w4 = 1.0 / (pow(d4, blendPower) + baseRadius);

    return (c1 * w1 + c2 * w2 + c3 * w3 + c4 * w4 * 1.15) / (w1 + w2 + w3 + w4 * 1.15);
}

void main() {
    vec2 uv = v_texCoord;

    // ==========================================
    // 【新修改点 1】：视频里的标准流动速率为1.3，速度整体慢一倍 (从 1.3 减慢一倍到 0.65)
    // ==========================================
    float speedMultiplier = 0.65;
    float t = mod(u_time * speedMultiplier, 314.159265);
    float aspect = u_resolution.x / u_resolution.y;

    // 图层 1：原始清淡色彩（对应 AE 饱和度仅为 5）
    vec3 l1_c1 = vec3(0.827, 0.561, 1.000); // #D38FFF
    vec3 l1_c2 = vec3(0.600, 0.647, 1.000); // #99A5FF
    vec3 l1_c3 = vec3(0.549, 0.718, 1.000); // #8CB7FF
    vec3 l1_c4 = vec3(0.996, 0.871, 0.278); // #FEDE47

    // 图层 2：高饱和度色彩（对应 AE 主饱和度+55 后的真实色彩表现）
    vec3 l2_c1 = vec3(0.690, 0.210, 1.000);
    vec3 l2_c2 = vec3(0.180, 0.290, 1.000);
    vec3 l2_c3 = vec3(0.020, 0.410, 1.000);
    vec3 l2_c4 = vec3(1.000, 0.820, 0.000);

    vec2 fullAmplitude = vec2(0.53);

    // 图层 1 轨迹生成
    vec2 l1_p1 = fullScreenWiggle(t, 1.2, fullAmplitude * 0.95, vec2(11.4, 34.5));
    vec2 l1_p2 = fullScreenWiggle(t, 1.6, fullAmplitude * 0.95, vec2(52.1, 87.3));
    vec2 l1_p3 = fullScreenWiggle(t, 1.5, fullAmplitude * 0.95, vec2(93.5, 12.8));
    vec2 l1_p4 = fullScreenWiggle(t, 1.3, fullAmplitude * 0.95, vec2(44.2, 69.9));

    // 图层 2 轨迹生成
    vec2 l2_p1 = fullScreenWiggle(t, 2.2, fullAmplitude * 0.95, vec2(101.3, 134.7));
    vec2 l2_p2 = fullScreenWiggle(t, 1.5, fullAmplitude * 0.95, vec2(188.5, 112.3));
    vec2 l2_p3 = fullScreenWiggle(t, 1.8, fullAmplitude * 0.95, vec2(141.7, 176.2));
    vec2 l2_p4 = fullScreenWiggle(t, 1.3, fullAmplitude * 0.95, vec2(119.3, 154.8));

    // 图层 4 双路采样模糊
    float blurScale = 0.015;

    // 采样点 A
    vec2 uvA = uv + turbulentDisplace(uv, u_time, 108.0, 300.0);
    vec2 uvA_corr = vec2((uvA.x - blurScale) * aspect, uvA.y - blurScale);
    vec3 l1_A = evaluateFourColor(uvA_corr, aspect, l1_p1, l1_p2, l1_p3, l1_p4, l1_c1, l1_c2, l1_c3, l1_c4);
    vec3 l2_A = evaluateFourColor(uvA_corr, aspect, l2_p1, l2_p2, l2_p3, l2_p4, l2_c1, l2_c2, l2_c3, l2_c4);
    vec3 colorSampleA = blendOverlay(l1_A, l2_A);

    // 采样点 B
    vec2 uvB = uv + turbulentDisplace(uv + vec2(0.01), u_time, 108.0, 300.0);
    vec2 uvB_corr = vec2((uvB.x + blurScale) * aspect, uvB.y + blurScale);
    vec3 l1_B = evaluateFourColor(uvB_corr, aspect, l1_p1, l1_p2, l1_p3, l1_p4, l1_c1, l1_c2, l1_c3, l1_c4);
    vec3 l2_B = evaluateFourColor(uvB_corr, aspect, l2_p1, l2_p2, l2_p3, l2_p4, l2_c1, l2_c2, l2_c3, l2_c4);
    vec3 colorSampleB = blendOverlay(l1_B, l2_B);

    // 模糊混合
    vec3 preColor = mix(colorSampleA, colorSampleB, 0.5);

    // 全局饱和度微调
    float luma = dot(preColor, vec3(0.299, 0.587, 0.114));
    vec3 finalColor = mix(vec3(luma), preColor, 1.12);

    // 80% Dither 去噪点
    float noise = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    finalColor += (noise - 0.5) * (0.80 / 255.0);

     // ==========================================
     // 【新修改点 2】：以 50% 透明度覆盖在宿主背景上
     // ==========================================
     // 定义 APP 内部页面底色（这里采用对齐你效果图的纯净浅灰白色 #F8F9FA，可根据实际项目自定义修改）
     vec3 appBackgroundColor = vec3(0.973, 0.976, 0.980);

    // 50% 线性淡入融合，完美达到照片制作中卡片半透明变柔和的高级质感
    finalColor = mix(appBackgroundColor, finalColor, 0.50);
    // ==========================================

    gl_FragColor = vec4(finalColor, 1.0);
}