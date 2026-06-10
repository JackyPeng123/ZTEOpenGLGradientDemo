precision highp float;
varying vec2 v_texCoord;
uniform float u_time;
uniform vec2 u_resolution;

// 全屏摆动保持动态平衡
vec2 fullScreenWiggle(float time, float freq, vec2 amp, vec2 seed) {
    float x = sin(time * freq + seed.x * 2.1) * amp.x + cos(time * freq * 0.72 + seed.y) * amp.x * 0.35;
    float y = cos(time * freq * 1.15 + seed.y * 1.9) * amp.y + sin(time * freq * 0.63 + seed.x) * amp.y * 0.35;
    return vec2(x + 0.5, y + 0.5);
}

// 置换湍流
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

vec3 blendOverlay(vec3 base, vec3 blend) {
    return mix(
        2.0 * base * blend,
        1.0 - 2.0 * (1.0 - base) * (1.0 - blend),
        step(0.5, base)
    );
}

// 【修复点 1】：重新设计的抗拉伸四色四角评估函数
vec3 evaluateFourColor(vec2 uv, vec2 aspectScale, vec2 p1, vec2 p2, vec2 p3, vec2 p4, vec3 c1, vec3 c2, vec3 c3, vec3 c4) {
    // 将 UV 坐标和控制点轨迹都带入相同的缩放空间，消除横屏时的跨度溢出
    vec2 uv_c = (uv - 0.5) * aspectScale + 0.5;
    vec2 cl_p1 = (p1 - 0.5) * aspectScale + 0.5;
    vec2 cl_p2 = (p2 - 0.5) * aspectScale + 0.5;
    vec2 cl_p3 = (p3 - 0.5) * aspectScale + 0.5;
    vec2 cl_p4 = (p4 - 0.5) * aspectScale + 0.5;

    float d1 = length(uv_c - cl_p1);
    float d2 = length(uv_c - cl_p2);
    float d3 = length(uv_c - cl_p3);
    float d4 = length(uv_c - cl_p4);

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

    float speedMultiplier = 0.65;
    float t = mod(u_time * speedMultiplier, 314.159265);

    // 【修复点 2】：动态横竖屏自适应屏比因子
    // 无论横屏还是竖屏，画面都以中心向外辐射，确保控制点不会丢失在右侧黑影中
    vec2 aspectScale = vec2(1.0);
    if (u_resolution.x > u_resolution.y) {
        aspectScale.x = u_resolution.x / u_resolution.y; // 横屏模式
    } else {
        aspectScale.y = u_resolution.y / u_resolution.x; // 竖屏模式
    }

    // 图层 1 色彩
    vec3 l1_c1 = vec3(0.827, 0.561, 1.000);
    vec3 l1_c2 = vec3(0.600, 0.647, 1.000);
    vec3 l1_c3 = vec3(0.549, 0.718, 1.000);
    vec3 l1_c4 = vec3(0.996, 0.871, 0.278);

    // 图层 2 色彩
    vec3 l2_c1 = vec3(0.690, 0.210, 1.000);
    vec3 l2_c2 = vec3(0.180, 0.290, 1.000);
    vec3 l2_c3 = vec3(0.020, 0.410, 1.000);
    vec3 l2_c4 = vec3(1.000, 0.820, 0.000);

    vec2 fullAmplitude = vec2(0.53);

    // 轨迹生成
    vec2 l1_p1 = fullScreenWiggle(t, 1.2, fullAmplitude * 0.95, vec2(11.4, 34.5));
    vec2 l1_p2 = fullScreenWiggle(t, 1.6, fullAmplitude * 0.95, vec2(52.1, 87.3));
    vec2 l1_p3 = fullScreenWiggle(t, 1.5, fullAmplitude * 0.95, vec2(93.5, 12.8));
    vec2 l1_p4 = fullScreenWiggle(t, 1.3, fullAmplitude * 0.95, vec2(44.2, 69.9));

    vec2 l2_p1 = fullScreenWiggle(t, 2.2, fullAmplitude * 0.95, vec2(101.3, 134.7));
    vec2 l2_p2 = fullScreenWiggle(t, 1.5, fullAmplitude * 0.95, vec2(188.5, 112.3));
    vec2 l2_p3 = fullScreenWiggle(t, 1.8, fullAmplitude * 0.95, vec2(141.7, 176.2));
    vec2 l2_p4 = fullScreenWiggle(t, 1.3, fullAmplitude * 0.95, vec2(119.3, 154.8));

    float blurScale = 0.015;

    // 采样点 A（引入 t 修复置换算法的突变稳定性）
    vec2 uvA = uv + turbulentDisplace(uv, t, 108.0, 300.0);
    vec2 uvA_offset = uvA - vec2(blurScale);
    vec3 l1_A = evaluateFourColor(uvA_offset, aspectScale, l1_p1, l1_p2, l1_p3, l1_p4, l1_c1, l1_c2, l1_c3, l1_c4);
    vec3 l2_A = evaluateFourColor(uvA_offset, aspectScale, l2_p1, l2_p2, l2_p3, l2_p4, l2_c1, l2_c2, l2_c3, l2_c4);
    vec3 colorSampleA = blendOverlay(l1_A, l2_A);

    // 采样点 B
    vec2 uvB = uv + turbulentDisplace(uv + vec2(0.01), t, 108.0, 300.0);
    vec2 uvB_offset = uvB + vec2(blurScale);
    vec3 l1_B = evaluateFourColor(uvB_offset, aspectScale, l1_p1, l1_p2, l1_p3, l1_p4, l1_c1, l1_c2, l1_c3, l1_c4);
    vec3 l2_B = evaluateFourColor(uvB_offset, aspectScale, l2_p1, l2_p2, l2_p3, l2_p4, l2_c1, l2_c2, l2_c3, l2_c4);
    vec3 colorSampleB = blendOverlay(l1_B, l2_B);

    // 混合与后期处理
    vec3 preColor = mix(colorSampleA, colorSampleB, 0.5);

    float luma = dot(preColor, vec3(0.299, 0.587, 0.114));
    vec3 finalColor = mix(vec3(luma), preColor, 1.12);

    float noise = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    finalColor += (noise - 0.5) * (0.80 / 255.0);

    // 50% 宿主底色融合
    vec3 appBackgroundColor = vec3(0.973, 0.976, 0.980);
    finalColor = mix(appBackgroundColor, finalColor, 0.50);

    gl_FragColor = vec4(finalColor, 1.0);
}