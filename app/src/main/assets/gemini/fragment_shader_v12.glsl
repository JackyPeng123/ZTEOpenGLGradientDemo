precision mediump float;
varying vec2 v_texCoord;
uniform float u_time;  // 外部传入的时间驱动变量（单位：秒）
uniform vec2 u_resolution; // 外部传入的手机屏幕实际分辨率 (width, height)

// Specialized wiggle function designed for global, large-scale, intricate motion
// Ensures points travel freely from edge to edge without static center constraints.
vec2 fullScreenWiggle(float time, float freq, vec2 amp, vec2 seed) {
    // Generate complex paths using intricate offset sine waves to ensure global coverage.
    float x = sin(time * freq + seed.x * 2.0) * amp.x + cos(time * freq * 0.8 + seed.y) * amp.x * 0.3;
    float y = cos(time * freq * 1.1 + seed.y * 2.0) * amp.y + sin(time * freq * 0.7 + seed.x) * amp.y * 0.3;

    // Direct clamping to UV range is too restrictive. Normalized from [-1, 1] base.
    // Instead, we use a wide base oscillation and only center it, letting large 'amp' drive full-screen travel.
    return vec2(x + 0.5, y + 0.5);
}

// 叠加混合模式算法 (Overlay) - No change
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
    vec2 uv_corr = vec2(uv.x * aspect, uv.y);

    // Color definitions (as before)
    vec3 c1 = vec3(0.827, 0.561, 1.000); // #D38FFF (Purple)
    vec3 c2 = vec3(0.600, 0.647, 1.000); // #99A5FF (Light Purple)
    vec3 c3 = vec3(0.549, 0.718, 1.000); // #8CB7FF (Blue)
    vec3 c4 = vec3(0.996, 0.871, 0.278); // #FEDE47 (Yellow)

    // ==========================================
    // 【图层 1：全屏交织路径漫游】
    // ==========================================
    // Core Change: We no longer add wiggle to a static base point.
    // The `wiggle` function now directly generates UV coordinates (normalized to full screen amplitude).
    // The seeds and amplitudes are manually tuned to ensure a complex, globally interwoven dance.

    // A full full-screen amplitude (0.5 means a total width of 1.0 travel).
    vec2 fullAmplitude = vec2(0.5);

    // Layer 1 - Generate 4 unique, full-screen interwoven paths
    vec2 l1_p1 = fullScreenWiggle(t, 1.5, fullAmplitude * 0.9, vec2(11.4, 34.5)); // Path 1
    vec2 l1_p2 = fullScreenWiggle(t, 2.0, fullAmplitude * 0.9, vec2(52.1, 87.3)); // Path 2
    vec2 l1_p3 = fullScreenWiggle(t, 2.0, fullAmplitude * 0.9, vec2(93.5, 12.8)); // Path 3
    vec2 l1_p4 = fullScreenWiggle(t, 1.7, fullAmplitude * 0.9, vec2(44.2, 69.9)); // Path 4 (YELLOW)

    // Verify coordinates are centered in screen UV [0,1] range by default
    // We add +0.5 inside the wiggle function to make the default oscillation center at screen center.
    // However, if amplitudes were too small, they would collapse. They are large (0.9).

    float l1_d1 = length(uv_corr - vec2(l1_p1.x * aspect, l1_p1.y));
    float l1_d2 = length(uv_corr - vec2(l1_p2.x * aspect, l1_p2.y));
    float l1_d3 = length(uv_corr - vec2(l1_p3.x * aspect, l1_p3.y));
    float l1_d4 = length(uv_corr - vec2(l1_p4.x * aspect, l1_p4.y));

    // Blending for Layer 1: Reduce blendPower (e.g., from 1.25 to 1.1) to allow colors
    // to spread softly over large distances, preventing edge collapse.
    float l1_blendPower = 1.1;
    float l1_baseRadius = 0.08;
    float l1_w1 = 1.0 / (pow(l1_d1, l1_blendPower) + l1_baseRadius);
    float l1_w2 = 1.0 / (pow(l1_d2, l1_blendPower) + l1_baseRadius);
    float l1_w3 = 1.0 / (pow(l1_d3, l1_blendPower) + l1_baseRadius);
    float l1_w4 = 1.0 / (pow(l1_d4, l1_blendPower) + l1_baseRadius);

    vec3 colorLayer1 = (c1 * l1_w1 + c2 * l1_w2 + c3 * l1_w3 + c4 * l1_w4) / (l1_w1 + l1_w2 + l1_w3 + l1_w4);

    // Saturation boost (+5)
    float luma1 = dot(colorLayer1, vec3(0.299, 0.587, 0.114));
    colorLayer1 = mix(vec3(luma1), colorLayer1, 1.05);


    // ==========================================
    // 【图层 2：全屏交织路径漫游 (错位)】
    // ==========================================
    // Core Change: Again, purely generative, no static points.
    // The Layer 2 paths are "slightly offset" by using different base seeds.

    // Layer 2 - Generate 4 new, interwoven paths, globally offset from Layer 1.
    vec2 l2_p1 = fullScreenWiggle(t, 3.0, fullAmplitude * 0.95, vec2(101.3, 134.7)); // New seed
    vec2 l2_p2 = fullScreenWiggle(t, 2.0, fullAmplitude * 0.95, vec2(188.5, 112.3)); // New seed
    vec2 l2_p3 = fullScreenWiggle(t, 2.3, fullAmplitude * 0.95, vec2(141.7, 176.2)); // New seed
    vec2 l2_p4 = fullScreenWiggle(t, 1.7, fullAmplitude * 0.95, vec2(119.3, 154.8)); // New seed

    float l2_d1 = length(uv_corr - vec2(l2_p1.x * aspect, l2_p1.y));
    float l2_d2 = length(uv_corr - vec2(l2_p2.x * aspect, l2_p2.y));
    float l2_d3 = length(uv_corr - vec2(l2_p3.x * aspect, l2_p3.y));
    float l2_d4 = length(uv_corr - vec2(l2_p4.x * aspect, l2_p4.y));

    // Blending for Layer 2: Also reduce blendPower to prevent collapse.
    float l2_blendPower = 1.15; // Slightly more cohesive than L1 but still soft.
    float l2_baseRadius = 0.04;
    float l2_w1 = 1.0 / (pow(l2_d1, l2_blendPower) + l2_baseRadius);
    float l2_w2 = 1.0 / (pow(l2_d2, l2_blendPower) + l2_baseRadius);
    float l2_w3 = 1.0 / (pow(l2_d3, l2_blendPower) + l2_baseRadius);
    float l2_w4 = 1.0 / (pow(l2_d4, l2_blendPower) + l2_baseRadius);
    vec3 colorLayer2 = (c1 * l2_w1 + c2 * l2_w2 + c3 * l2_w3 + c4 * l2_w4) / (l2_w1 + l2_w2 + l2_w3 + l2_w4);

    // Saturation strong boost (+55) -> 系数为 1.55
    float luma2 = dot(colorLayer2, vec3(0.299, 0.587, 0.114));
    colorLayer2 = mix(vec3(luma2), colorLayer2, 1.55);


    // ==========================================
    // 【图层融合与后处理】 - No change
    // ==========================================
    // Overlay l2 on top of l1
    vec3 finalColor = blendOverlay(colorLayer1, colorLayer2);

    // 80% dither noise
    float noise = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    finalColor += (noise - 0.5) * (0.80 / 255.0);

    gl_FragColor = vec4(finalColor, 1.0);
}