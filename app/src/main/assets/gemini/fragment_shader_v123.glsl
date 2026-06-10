precision highp float;
varying vec2 v_texCoord;
uniform float u_time;  // 外部传入的时间驱动变量（单位：秒）
uniform vec2 u_resolution; // 外部传入的手机屏幕实际分辨率 (width, height)

// 基础摆动函数：生成全屏漫游路径
vec2 fullScreenWiggle(float time, float freq, vec2 amp, vec2 seed) {
    float x = sin(time * freq + seed.x * 2.0) * amp.x + cos(time * freq * 0.8 + seed.y) * amp.x * 0.3;
    float y = cos(time * freq * 1.1 + seed.y * 2.0) * amp.y + sin(time * freq * 0.7 + seed.x) * amp.y * 0.3;
    return vec2(x + 0.5, y + 0.5);
}

// 空间扰动噪声函数：用于模拟 AE 的湍流置换演化
// 使用轻量级的正余弦叠加模拟流体噪声
vec2 turbulentDisplace(vec2 uv, float time, float amount, float size) {
    // 将 AE 的“演化(度/秒)”转换为弧度，并配合大小比例
    float angle = time * (100.0 * 3.1415926 / 180.0);

    // 空间频率：将 AE 的 size 转换为基于 UV 空间的频率
    float freq = 4.0 * (300.0 / size);

    // 以屏幕中心 (0.5, 0.5) 为基准进行空间扭转计算
    vec2 center = vec2(0.5);
    vec2 toCenter = uv - center;
    float distToCenter = length(toCenter);

    // 湍流波形计算 (引入 Twist 扭转特性：距离越近扭角越深，配合时间上演化)
    float waveX = sin(uv.y * freq + angle) * cos(uv.x * freq * 0.5 + angle * 0.6);
    float waveY = cos(uv.x * freq + angle * 1.1) * sin(uv.y * freq * 0.7 + angle * 0.4);

    // 混合漩涡(Twist)效果
    float twistAngle = waveX * 0.5 + sin(distToCenter * freq - angle * 0.5);
    float s = sin(twistAngle);
    float c = cos(twistAngle);
    vec2 twistOffset = vec2(toCenter.x * c - toCenter.y * s, toCenter.x * s + toCenter.y * c) - toCenter;

    // 将 AE 的数量(108.0) 换算为适合 UV 的微调系数
    float strength = (amount / 100.0) * 0.12;

    // 最终返回形变后的 UV 偏移量
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
    float t = mod(u_time * speedMultiplier, 314.159265);
    float aspect = u_resolution.x / u_resolution.y;

    // ==========================================
    // 【核心新增：图层3 湍流置换物理空间扭曲】
    // ==========================================
    // 参数直接对应设计图：数量=108.0, 大小=300.0
    vec2 uvOffset = turbulentDisplace(uv, u_time, 108.0, 300.0);

    // 应用空间扭曲，后续所有的色彩和距离计算都基于这个被揉捏过的畸变空间
    vec2 distortedUV = uv + uvOffset;
    vec2 uv_corr = vec2(distortedUV.x * aspect, distortedUV.y);

    // 四色定义保持不变
    vec3 c1 = vec3(0.827, 0.561, 1.000); // #D38FFF (紫)
    vec3 c2 = vec3(0.600, 0.647, 1.000); // #99A5FF (浅蓝紫)
    vec3 c3 = vec3(0.549, 0.718, 1.000); // #8CB7FF (蓝)
    vec3 c4 = vec3(0.996, 0.871, 0.278); // #FEDE47 (黄)

    // 全屏自由漫游范围幅值 (0.5 代表全屏覆盖)
    vec2 fullAmplitude = vec2(0.5);

    // ==========================================
    // 【图层 1 计算 (全屏自由漫游版)】
    // ==========================================
    vec2 l1_p1 = fullScreenWiggle(t, 1.5, fullAmplitude * 0.95, vec2(11.4, 34.5));
    vec2 l1_p2 = fullScreenWiggle(t, 2.0, fullAmplitude * 0.95, vec2(52.1, 87.3));
    vec2 l1_p3 = fullScreenWiggle(t, 2.0, fullAmplitude * 0.95, vec2(93.5, 12.8));
    vec2 l1_p4 = fullScreenWiggle(t, 1.7, fullAmplitude * 0.95, vec2(44.2, 69.9)); // 黄色全屏自由漫游

    // 基于扭曲后的 uv_corr 计算相对色点距离
    float l1_d1 = length(uv_corr - vec2(l1_p1.x * aspect, l1_p1.y));
    float l1_d2 = length(uv_corr - vec2(l1_p2.x * aspect, l1_p2.y));
    float l1_d3 = length(uv_corr - vec2(l1_p3.x * aspect, l1_p3.y));
    float l1_d4 = length(uv_corr - vec2(l1_p4.x * aspect, l1_p4.y));

    // 大漫游大平滑底色混合（混合度138.0 对应较低幂次 1.1）
    float l1_blendPower = 1.1;
    float l1_baseRadius = 0.08;
    float l1_w1 = 1.0 / (pow(l1_d1, l1_blendPower) + l1_baseRadius);
    float l1_w2 = 1.0 / (pow(l1_d2, l1_blendPower) + l1_baseRadius);
    float l1_w3 = 1.0 / (pow(l1_d3, l1_blendPower) + l1_baseRadius);
    float l1_w4 = 1.0 / (pow(l1_d4, l1_blendPower) + l1_baseRadius);
    vec3 colorLayer1 = (c1 * l1_w1 + c2 * l1_w2 + c3 * l1_w3 + c4 * l1_w4) / (l1_w1 + l1_w2 + l1_w3 + l1_w4);

    // 图层1饱和度 (+5)
    float luma1 = dot(colorLayer1, vec3(0.299, 0.587, 0.114));
    colorLayer1 = mix(vec3(luma1), colorLayer1, 1.05);


    // ==========================================
    // 【图层 2 计算 (全屏自由漫游 + 错位版)】
    // ==========================================
    vec2 l2_p1 = fullScreenWiggle(t, 3.0, fullAmplitude * 0.95, vec2(101.3, 134.7));
    vec2 l2_p2 = fullScreenWiggle(t, 2.0, fullAmplitude * 0.95, vec2(188.5, 112.3));
    vec2 l2_p3 = fullScreenWiggle(t, 2.3, fullAmplitude * 0.95, vec2(141.7, 176.2));
    vec2 l2_p4 = fullScreenWiggle(t, 1.7, fullAmplitude * 0.95, vec2(119.3, 154.8)); // 黄色全屏自由漫游

    // 同样基于扭曲后的空间计算距离
    float l2_d1 = length(uv_corr - vec2(l2_p1.x * aspect, l2_p1.y));
    float l2_d2 = length(uv_corr - vec2(l2_p2.x * aspect, l2_p2.y));
    float l2_d3 = length(uv_corr - vec2(l2_p3.x * aspect, l2_p3.y));
    float l2_d4 = length(uv_corr - vec2(l2_p4.x * aspect, l2_p4.y));

    // 图层2混合度100.0（凝聚感稍强，降低幂次至1.15以匹配全屏漫游范围）
    float l2_blendPower = 1.15;
    float l2_baseRadius = 0.04;
    float l2_w1 = 1.0 / (pow(l2_d1, l2_blendPower) + l2_baseRadius);
    float l2_w2 = 1.0 / (pow(l2_d2, l2_blendPower) + l2_baseRadius);
    float l2_w3 = 1.0 / (pow(l2_d3, l2_blendPower) + l2_baseRadius);
    float l2_w4 = 1.0 / (pow(l2_d4, l2_blendPower) + l2_baseRadius);
    vec3 colorLayer2 = (c1 * l2_w1 + c2 * l2_w2 + c3 * l2_w3 + c4 * l2_w4) / (l2_w1 + l2_w2 + l2_w3 + l2_w4);

    // 图层2高饱和度强开 (+55)
    float luma2 = dot(colorLayer2, vec3(0.299, 0.587, 0.114));
    colorLayer2 = mix(vec3(luma2), colorLayer2, 1.55);


    // ==========================================
    // 【双层融合与抖动去断层】
    // ==========================================
    // 使用 Overlay 模式将图层2叠加在图层1之上
    vec3 finalColor = blendOverlay(colorLayer1, colorLayer2);

    // 80% Dither 去除渐变断层
    float noise = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    finalColor += (noise - 0.5) * (0.80 / 255.0);

    gl_FragColor = vec4(finalColor, 1.0);
}