precision highp float;

varying vec2 v_texCoord;
uniform sampler2D u_generating_bitmapTexture;
uniform sampler2D u_generating_sweepTexture;
uniform sampler2D u_generating_gradientBgTexture;
uniform sampler2D u_generating_glowTexture;

uniform float u_generating_sweepAlpha;
uniform float u_generating_gradientBgAlpha;
uniform float u_generating_glowAlpha;
uniform vec2 u_resolution;

// 四次方缓出 (Ease-Out Quart)
float easeOut(float x) {
    if (x <= 0.0) return 0.0;
    if (x >= 1.0) return 1.0;
    float f = 1.0 - x;
    return 1.0 - f * f * f * f;
}

// 强光混合模式
vec3 blendHardLight(vec3 base, vec3 blend) {
    vec3 result;
    result.r = blend.r < 0.5 ? (2.0 * base.r * blend.r) : (1.0 - 2.0 * (1.0 - base.r) * (1.0 - blend.r));
    result.g = blend.g < 0.5 ? (2.0 * base.g * blend.g) : (1.0 - 2.0 * (1.0 - base.g) * (1.0 - blend.g));
    result.b = blend.b < 0.5 ? (2.0 * base.b * blend.b) : (1.0 - 2.0 * (1.0 - base.b) * (1.0 - blend.b));
    return result;
}

vec3 blendScreen(vec3 base, vec3 blend) {
    return 1.0 - (1.0 - base) * (1.0 - blend);
}

// RGB 转 HSV
vec3 rgb2hsv(vec3 c) {
    vec4 K = vec4(0.0, -1.0 / 3.0, 2.0 / 3.0, -1.0);
    vec4 p = mix(vec4(c.bg, K.wz), vec4(c.gb, K.xy), step(c.b, c.g));
    vec4 q = mix(vec4(p.xyw, c.r), vec4(c.r, p.yzx), step(p.x, c.r));
    float d = q.x - min(q.w, q.y);
    float e = 1.0e-10;
    return vec3(abs(q.z + (q.w - q.y) / (6.0 * d + e)), d / (q.x + e), q.x);
}

// HSV 转 RGB
vec3 hsv2rgb(vec3 c) {
    vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
    vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
    return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
}

// 色相偏移函数
vec3 shiftHue(vec3 color, float shiftDegrees) {
    vec3 hsv = rgb2hsv(color);
    hsv.x += shiftDegrees / 360.0;
    hsv.x = fract(hsv.x);
    return hsv2rgb(hsv);
}

// 生成中的动效
vec3 drawGenerating(vec4 bitmapTex, vec3 finalColor) {
    vec4 sweepTex = texture2D(u_generating_sweepTexture, v_texCoord);
    vec4 gradientBgTex = texture2D(u_generating_gradientBgTexture, v_texCoord);
    vec4 glowTex = texture2D(u_generating_glowTexture, v_texCoord);

    //绘制渐变背景
    vec3 blendedRGB = blendHardLight(bitmapTex.rgb, gradientBgTex.rgb);
    vec3 finalColor = mix(bitmapTex.rgb, blendedRGB, u_generating_gradientBgAlpha);

    //绘制边缘发光
    vec3 glowColor = vec3(1.0) * glowTex.r * 1.0 * u_generating_glowAlpha;
    finalColor = finalColor + glowColor;

    //绘制入场扫光
    if (u_generating_sweepAlpha > 0.0) {
        // 微调提蓝滤镜：
        // R: 0.95 (几乎完全放过红色，让品红/粉紫彻底显现)
        // G: 0.90 (放过大部分绿色，保持青色的明亮)
        // B: 1.10 (微微增强蓝色)
        vec3 refinedFilter = vec3(0.95, 0.90, 1.10);
        vec3 deepBlueSweep = sweepTex.rgb * refinedFilter;

        // 将亮度系数提高到 1.05，恢复通透的霓虹发光感，消除底部的脏暗感
        deepBlueSweep = clamp(deepBlueSweep, 0.0, 1.0) * 1.05;

        finalColor = blendScreen(finalColor, deepBlueSweep);
    }
    return finalColor;
}

// 已生成 的动效
vec3 drawGenerated(vec3 finalColor) {
    return finalColor;
}

void main() {
    vec4 generatingBitmapTex = texture2D(u_generating_bitmapTexture, v_texCoord);

    vec3 finalColor;
    //生成中
    finalColor = drawGenerating(generatingBitmapTex, finalColor);
    //已生成
    finalColor = drawGenerated(finalColor);

    gl_FragColor = vec4(clamp(finalColor, 0.0, 1.0), generatingBitmapTex.a);
}