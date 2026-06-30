precision highp float;

varying vec2 v_texCoord;
uniform sampler2D u_generating_bitmapTexture;
uniform sampler2D u_generating_sweepTexture;
uniform sampler2D u_generating_gradientBgTexture;
uniform sampler2D u_generating_glowTexture;

uniform float u_generating_sweepAlpha;
uniform float u_generating_gradientBgAlpha;
uniform float u_generating_glowAlpha;
uniform vec4 u_generated_offset;     // x: left, y: top, z: right, w: bottom
uniform float u_generated_fadeInProgress;  // 0.0 ~ 1.0 动画进度
uniform float u_generated_outsideFadeOutProgress;//控制区域外图片消失的进度
uniform float u_generated_borderFadeOutProgress; // 控制虚线框消失的进度 (0.0~1.0)
uniform float u_generated_gridFadeOutProgress;   // 控制辅助线消失的进度 (0.0~1.0)

uniform vec2 u_resolution;
uniform float u_density;            // 屏幕密度 dp -> px

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
vec4 drawGenerated(vec3 finalColor, float baseAlpha) {
    if (u_generated_fadeInProgress <= 0.0) {
        return vec4(finalColor, baseAlpha);
    }

    vec2 pixelCoord = v_texCoord * u_resolution;

    float minX = u_generated_offset.x;
    float minY = u_generated_offset.y;
    float maxX = 1.0 - u_generated_offset.z;
    float maxY = 1.0 - u_generated_offset.w;

    float minPxX = minX * u_resolution.x;
    float minPxY = minY * u_resolution.y;
    float maxPxX = maxX * u_resolution.x;
    float maxPxY = maxY * u_resolution.y;

    float boxPxWidth = maxPxX - minPxX;
    float boxPxHeight = maxPxY - minPxY;

    bool isOutside = v_texCoord.x < minX || v_texCoord.x > maxX || v_texCoord.y < minY || v_texCoord.y > maxY;

    float finalAlpha = baseAlpha;

    if (isOutside) {
        // 【优化】让原有的 27% 黑色遮罩强度也随着淡出进度同步递减，防止过渡期间出现颜色突兀
        float maskAlpha = 0.27 * u_generated_fadeInProgress * (1.0 - u_generated_outsideFadeOutProgress);
        finalColor = mix(finalColor, vec3(0.0, 0.0, 0.0), maskAlpha);
        // 随着进度将整个区域外像素的透明度匀速降至 0
        finalAlpha = baseAlpha * (1.0 - u_generated_outsideFadeOutProgress);
        // 【安全防线】当进度达到或超过 1.0 时，死死锁定透明度为 0
        if (u_generated_outsideFadeOutProgress >= 1.0) {
            finalAlpha = 0.0;
        }
    } else {
        // 区域内部：逻辑保持不变
        float borderPxWidth = 1.5 * u_density;
        float dashLength = 8.0 * u_density;
        float dashGap = 6.0 * u_density;
        float dashPeriod = dashLength + dashGap;

        bool isBorder = false;
        float edgeDistance = 0.0;

        if (pixelCoord.x <= minPxX + borderPxWidth) {
            isBorder = true;
            edgeDistance = pixelCoord.y - minPxY;
        } else if (pixelCoord.x >= maxPxX - borderPxWidth) {
            isBorder = true;
            edgeDistance = pixelCoord.y - minPxY;
        } else if (pixelCoord.y <= minPxY + borderPxWidth) {
            isBorder = true;
            edgeDistance = pixelCoord.x - minPxX;
        } else if (pixelCoord.y >= maxPxY - borderPxWidth) {
            isBorder = true;
            edgeDistance = pixelCoord.x - minPxX;
        }

        if (isBorder) {
            if (mod(edgeDistance, dashPeriod) < dashLength) {
                // 【修改】原透明度上限为 0.60，乘上 (1.0 - u_generated_borderFadeOutProgress) 让其渐渐归零
                float borderAlpha = 0.60 * u_generated_fadeInProgress * (1.0 - u_generated_borderFadeOutProgress);
                finalColor = mix(finalColor, vec3(1.0, 1.0, 1.0), borderAlpha);
            }
        } else {
            float gridPxWidth = 1.0 * u_density;
            float halfGridWidth = gridPxWidth / 2.0;

            float gx1 = minPxX + boxPxWidth / 3.0;
            float gx2 = minPxX + 2.0 * boxPxWidth / 3.0;
            float gy1 = minPxY + boxPxHeight / 3.0;
            float gy2 = minPxY + 2.0 * boxPxHeight / 3.0;

            bool isGrid = abs(pixelCoord.x - gx1) < halfGridWidth ||
                          abs(pixelCoord.x - gx2) < halfGridWidth ||
                          abs(pixelCoord.y - gy1) < halfGridWidth ||
                          abs(pixelCoord.y - gy2) < halfGridWidth;

            if (isGrid) {
                // 【修改】原透明度上限为 0.27，乘上 (1.0 - u_generated_gridFadeOutProgress) 让其渐渐归零
                float gridAlpha = 0.27 * u_generated_fadeInProgress * (1.0 - u_generated_gridFadeOutProgress);
                finalColor = mix(finalColor, vec3(1.0, 1.0, 1.0), gridAlpha);
            }
        }
    }

    return vec4(finalColor, finalAlpha);
}

void main() {
    vec4 generatingBitmapTex = texture2D(u_generating_bitmapTexture, v_texCoord);

    vec3 finalColor;
    // 生成中
    finalColor = drawGenerating(generatingBitmapTex, finalColor);

    // 【修改】已生成：现在接管 Alpha 控制权
    vec4 finalResult = drawGenerated(finalColor, generatingBitmapTex.a);

    gl_FragColor = vec4(clamp(finalResult.rgb, 0.0, 1.0), finalResult.a);
}