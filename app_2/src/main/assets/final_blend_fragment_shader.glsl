precision highp float;

varying vec2 v_texCoord;
uniform sampler2D u_generating_bitmapTexture;
uniform sampler2D u_generating_sweepTexture;
uniform sampler2D u_generating_gradientBgTexture;
uniform sampler2D u_generating_glowTexture;
uniform sampler2D u_generated_bitmapTexture;
uniform sampler2D u_generated_maskTexture;

uniform float u_generating_sweepAlpha;
uniform float u_generating_gradientBgAlpha;
uniform float u_generating_glowAlpha;
uniform float u_generated_bitmapTextureAlpha;
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
vec3 drawGenerated(vec4 bitmapTex, vec3 finalColor) {
    vec3 maskGrad = texture2D(u_generated_maskTexture, v_texCoord).rgb;

    float solidAlpha = 0.0;
    float strokeAlphaLarge = 0.0;
    float strokeAlphaSmall = 0.0;
    float scale = 0.0;
    float scaleSmall = 0.0;

    if (u_generated_bitmapTextureAlpha > 0.0) {
        scale = easeOut(u_generated_bitmapTextureAlpha);
        vec2 px = v_texCoord * u_resolution;
        vec2 center = u_resolution * 0.5;
        float dist = length(px - center);

        float maxRadius = length(u_resolution) * 0.6;

        // 【图像显现边缘也变得极度柔和】
        float currentRadiusL = maxRadius * scale;
        float blur = 250.0; // 从 100 增加到 250，让图像本身也是雾化浮现
        solidAlpha = 1.0 - smoothstep(currentRadiusL - blur, currentRadiusL, dist);

        // 【大号描边：彻底变成全屏氛围光】
        float strokeWidthL = 130.0;  // 宽度极大化
        float strokeBlurL = 200.0;   // 羽化极大化（跨越半个屏幕的柔和度）
        float outerEdgeL = currentRadiusL + strokeWidthL * 0.5;
        float innerEdgeL = currentRadiusL - strokeWidthL * 0.5;
        strokeAlphaLarge = smoothstep(innerEdgeL - strokeBlurL, innerEdgeL, dist)
                         - smoothstep(outerEdgeL, outerEdgeL + strokeBlurL, dist);
        strokeAlphaLarge = clamp(strokeAlphaLarge, 0.0, 1.0);

        // 【小号描边：内层粉紫光也大幅扩散】
        scaleSmall = scale * 0.98 - 0.05;
        float currentRadiusS = maxRadius * scaleSmall;

        if (scaleSmall > 0.0) {
            float strokeWidthS = 100.0;  // 宽度极大化
            float strokeBlurS = 160.0;   // 羽化极大化
            float outerEdgeS = currentRadiusS + strokeWidthS * 0.5;
            float innerEdgeS = currentRadiusS - strokeWidthS * 0.5;
            strokeAlphaSmall = smoothstep(innerEdgeS - strokeBlurS, innerEdgeS, dist)
                             - smoothstep(outerEdgeS, outerEdgeS + strokeBlurS, dist);
            strokeAlphaSmall = clamp(strokeAlphaSmall, 0.0, 1.0);
        }
    }

    finalColor = mix(finalColor, bitmapTex.rgb, solidAlpha);

    float globalGlowAlpha = sin(u_generated_bitmapTextureAlpha * 3.14159);

    // ==========================================
    // 叠加图层 1：大号描边 (偏蓝底色)
    // ==========================================
    if (strokeAlphaLarge > 0.0) {
        float dynamicWhiteL = mix(0.60, 0.05, scale);
        vec3 gradLarge = mix(maskGrad, vec3(1.0), dynamicWhiteL);
        vec3 hardLight1 = blendHardLight(finalColor, gradLarge);
        // 因为面积变大很多，适当提一点浓度
        finalColor = mix(finalColor, hardLight1, strokeAlphaLarge * globalGlowAlpha * 0.65);
    }

    // ==========================================
    // 叠加图层 2：小号描边 (粉紫偏蓝)
    // ==========================================
    if (strokeAlphaSmall > 0.0) {
        vec3 gradSmall = shiftHue(maskGrad, 58.0);

        float dynamicWhiteS = mix(0.65, 0.05, clamp(scaleSmall, 0.0, 1.0));
        gradSmall = mix(gradSmall, vec3(1.0), dynamicWhiteS);

        vec3 hardLight2 = blendHardLight(finalColor, gradSmall);
        finalColor = mix(finalColor, hardLight2, strokeAlphaSmall * globalGlowAlpha * 0.75);
    }

    return finalColor;
}

void main() {
    vec4 generatingBitmapTex = texture2D(u_generating_bitmapTexture, v_texCoord);
    vec4 generatedBitmapTex = texture2D(u_generated_bitmapTexture, v_texCoord);

    vec3 finalColor;
    //生成中
    finalColor = drawGenerating(generatingBitmapTex, finalColor);
    //已生成
    finalColor = drawGenerated(generatedBitmapTex, finalColor);

    if (u_generated_bitmapTextureAlpha > 0.0) {
        gl_FragColor = vec4(clamp(finalColor, 0.0, 1.0), generatedBitmapTex.a);
    } else {
        gl_FragColor = vec4(clamp(finalColor, 0.0, 1.0), generatingBitmapTex.a);
    }
}