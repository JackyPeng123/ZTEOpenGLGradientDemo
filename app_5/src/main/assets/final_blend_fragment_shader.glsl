precision highp float;

varying vec2 v_texCoord;
uniform sampler2D u_generating_bitmapTexture;
uniform sampler2D u_generating_sweepTexture;
uniform sampler2D u_generating_gradientBgTexture;

// 接收 Java 层传来的 Bitmap 所在的 UV 坐标区域：x=minX, y=minY, z=maxX, w=maxY
uniform vec4 u_generating_bitmapRect;

uniform float u_generating_sweepAlpha;
uniform float u_generating_gradientBgAlpha;
uniform vec2 u_resolution;

// 滤色混合模式（用于扫光）
vec3 blendScreen(vec3 base, vec3 blend) {
    return 1.0 - (1.0 - base) * (1.0 - blend);
}

// 生成中的动效：处理全屏背景、扫光，并将 Bitmap 盖在最上层
vec4 drawGenerating(vec4 bitmapTex, vec4 currentColor) {
    vec4 sweepTex = texture2D(u_generating_sweepTexture, v_texCoord);
    vec4 gradientBgTex = texture2D(u_generating_gradientBgTexture, v_texCoord);

    // 1. 底层：生成背景色
    vec4 bgColor;
    if (u_generating_gradientBgAlpha > 0.0) {
        bgColor = vec4(gradientBgTex.rgb, u_generating_gradientBgAlpha);
    } else {
        bgColor = vec4(gradientBgTex.rgb * u_generating_gradientBgAlpha, u_generating_gradientBgAlpha);
    }

    // 2. 中层：叠加扫光到背景上
    if (u_generating_sweepAlpha > 0.0) {
        vec3 refinedFilter = vec3(0.95, 0.90, 1.10);
        vec3 deepBlueSweep = sweepTex.rgb * refinedFilter;
        deepBlueSweep = clamp(deepBlueSweep, 0.0, 1.0) * 1.05;

        bgColor.rgb = blendScreen(bgColor.rgb, deepBlueSweep);
        bgColor.a = max(bgColor.a, sweepTex.a); // 叠加不透明度
    }

    // （安全保护）没动效时，底层完全透明
    if (u_generating_gradientBgAlpha <= 0.0 && u_generating_sweepAlpha <= 0.0) {
        bgColor = vec4(0.0);
    }

    // 3. 顶层混合：将 Bitmap 正常覆盖在动效背景之上 (源 over 目标)
    vec3 finalRGB = mix(bgColor.rgb, bitmapTex.rgb, bitmapTex.a);
    float finalAlpha = bgColor.a + bitmapTex.a * (1.0 - bgColor.a);

    return vec4(finalRGB, finalAlpha);
}

// 已生成 的动效
vec4 drawGenerated(vec4 currentColor) {
    // 扩展预留：可以在这里对最终合成的画面做额外的特效处理
    return currentColor;
}

void main() {
    // 1. 获取顶层 Bitmap 的采样
    vec4 generatingBitmapTex = vec4(0.0);

    // 只有在设定的边距区域内，才去采样图片
    if (v_texCoord.x >= u_generating_bitmapRect.x && v_texCoord.x <= u_generating_bitmapRect.z &&
        v_texCoord.y >= u_generating_bitmapRect.y && v_texCoord.y <= u_generating_bitmapRect.w) {

        // 将全屏坐标映射到 0~1 的 Bitmap 内部空间
        vec2 bitmapUV;
        bitmapUV.x = (v_texCoord.x - u_generating_bitmapRect.x) / (u_generating_bitmapRect.z - u_generating_bitmapRect.x);
        bitmapUV.y = (v_texCoord.y - u_generating_bitmapRect.y) / (u_generating_bitmapRect.w - u_generating_bitmapRect.y);

        generatingBitmapTex = texture2D(u_generating_bitmapTexture, bitmapUV);
    }

    vec4 finalColor = vec4(0.0);

    // 生成中
    finalColor = drawGenerating(generatingBitmapTex, finalColor);

    // 已生成
    finalColor = drawGenerated(finalColor);

    // 输出最终的颜色和 Alpha
    vec3 premultipliedRGB = finalColor.rgb * finalColor.a;
    gl_FragColor = vec4(clamp(premultipliedRGB, 0.0, 1.0), finalColor.a);
}