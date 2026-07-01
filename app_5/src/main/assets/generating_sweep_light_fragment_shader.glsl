precision highp float;
varying vec2 v_texCoord;
uniform float u_time;          // 注入时间（单位：秒）
uniform float u_duration;      // 动画总时长
uniform vec2 u_resolution;     // 画布分辨率 (如 1080.0, 1920.0)

// ==========================================
// 1. 三次贝塞尔曲线求解器 (0.10, 0.0, 0.0, 1.0)
// ==========================================
float easeOutQuart(float x) {
    float f = 1.0 - x;
    return 1.0 - f * f * f * f;
}

void main() {
    float totalDurationMs = u_duration;
    float timeMs = u_time;

    if (timeMs > totalDurationMs) {
        gl_FragColor = vec4(0.0);
        return;
    }

    // ==========================================
    // 2. 动效时间线与速率控制
    // ==========================================
    // 恢复了你原本注释掉的动画进度控制
    float globalAlpha = 1.0 - clamp(timeMs / totalDurationMs, 0.0, 1.0);
    // 归一化时间进度 (0.0 到 1.0)
    float moveRaw = clamp(timeMs / totalDurationMs, 0.0, 1.0);
    // 使用新的缓出曲线计算运动距离进度
    float progress = easeOutQuart(moveRaw);

    // ==========================================
    // 3. 四色光晕混合 (径向均匀扩散)
    // ==========================================
    vec3 c1 = vec3(0.20, 0.65, 0.95); // 顶部浅蓝 (保持明亮)
    vec3 c2 = vec3(0.65, 0.50, 0.90); // 中上浅紫 (作为过渡)
    vec3 c3 = vec3(0.08, 0.35, 0.85); // 中下：原本是偏紫的蓝，现在改为【更纯粹的深蓝】
    vec3 c4 = vec3(0.05, 0.15, 0.60); // 底部：原本是深紫，现在改为【极深的藏青/海水蓝】

    // 将坐标归一化到像素比例，防止光晕在非正方形屏幕上被拉伸成椭圆
    vec2 px = v_texCoord * u_resolution;
    vec2 uv = px / u_resolution.x;
    float aspect = u_resolution.y / u_resolution.x;

    // 根据“均等分布”原则，将中心点设在高度的 1/8, 3/8, 5/8, 7/8 处
    vec2 p1 = vec2(0.5, (1.0 - 0.125) * aspect);
    vec2 p2 = vec2(0.5, (1.0 - 0.375) * aspect);
    vec2 p3 = vec2(0.5, (1.0 - 0.625) * aspect);
    vec2 p4 = vec2(0.5, (1.0 - 0.875) * aspect);

    // 计算当前像素到各个色块中心的距离
    float d1 = length(uv - p1);
    float d2 = length(uv - p2);
    float d3 = length(uv - p3);
    float d4 = length(uv - p4);

    // 反距离权重插值 (IDW) 算法。
    // power 值控制光晕的边缘清晰度：越大则单色光晕越独立，越小则融合越糊
    float power = 4.0;
    float w1 = 1.0 / (pow(d1, power) + 0.0001);
    float w2 = 1.0 / (pow(d2, power) + 0.0001);
    float w3 = 1.0 / (pow(d3, power) + 0.0001);
    float w4 = 1.0 / (pow(d4, power) + 0.0001);

    float sumW = w1 + w2 + w3 + w4;
    vec3 gradColor = (c1 * w1 + c2 * w2 + c3 * w3 + c4 * w4) / sumW;

    // ==========================================
    // 4. 椭圆蒙版计算与运动轨迹 (修改为基于 UV 比例的椭圆)
    // ==========================================
    // 直接使用 0.0~1.0 的 UV 坐标，这样在长方形屏幕上画出的圆自然会被拉伸成椭圆
    vec2 uv_mask = v_texCoord;

    // 将原本的像素尺寸转换为 UV 比例 (约等于原视觉大小)
    float R = 0.8;          // 遮罩半径比例
    float startY = -0.9;    // 从屏幕上方外部开始
    float endY = 0.5;       // 运动到屏幕正中心

    vec2 center = vec2(0.5, mix(startY, endY, progress));
    float dist = length(uv_mask - center);

    float feather = 0.1;    // 羽化边缘比例
    float maskAlpha = smoothstep(R, R - feather, dist);

    // ==========================================
    // 5. 最终合成与输出
    // ==========================================
    float finalAlpha = maskAlpha * globalAlpha;

    // 输出预乘 Alpha 的 RGBA
    gl_FragColor = vec4(gradColor * finalAlpha, finalAlpha);
}