precision highp float; // 如果换上后还报 0x502，可以尝试降为 precision mediump float;

varying vec2 v_texCoord;
uniform sampler2D u_texture;     // 边缘静态底图
uniform vec2 u_resolution;
uniform float u_time;            // 动态流光演化时间

// ==========================================
// 1. 噪声算法核心
// ==========================================
vec3 hash(vec3 p) {
    p = vec3( dot(p, vec3(127.1, 311.7,  74.7)),
              dot(p, vec3(269.5, 183.3, 246.1)),
              dot(p, vec3(113.5, 271.9, 124.6)));
    return -1.0 + 2.0 * fract(sin(p) * 43758.5453123);
}

float noise3D(vec3 p) {
    vec3 i = floor(p);
    vec3 f = fract(p);
    vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);

    return mix( mix( mix( dot( hash( i + vec3(0.0,0.0,0.0) ), f - vec3(0.0,0.0,0.0) ),
                          dot( hash( i + vec3(1.0,0.0,0.0) ), f - vec3(1.0,0.0,0.0) ), u.x),
                     mix( dot( hash( i + vec3(0.0,1.0,0.0) ), f - vec3(0.0,1.0,0.0) ),
                          dot( hash( i + vec3(1.0,1.0,0.0) ), f - vec3(1.0,1.0,0.0) ), u.x), u.y),
                mix( mix( dot( hash( i + vec3(0.0,0.0,1.0) ), f - vec3(0.0,0.0,1.0) ),
                          dot( hash( i + vec3(1.0,0.0,1.0) ), f - vec3(1.0,0.0,1.0) ), u.x),
                     mix( dot( hash( i + vec3(0.0,1.0,1.0) ), f - vec3(0.0,1.0,1.0) ),
                          dot( hash( i + vec3(1.0,1.0,1.0) ), f - vec3(1.0,1.0,1.0) ), u.x), u.y), u.z );
}

void main() {
    // 纠正上下颠倒
    vec2 correctedUV = vec2(v_texCoord.x, 1.0 - v_texCoord.y);

    // ==========================================
    // 2. 边缘检测蒙版 (Edge Mask)
    // ==========================================
    vec2 edgeStep = vec2(1.0) / u_resolution;
    if(u_resolution.x <= 0.0) edgeStep = vec2(1.0 / 1080.0, 1.0 / 2400.0);

    float c00 = dot(texture2D(u_texture, correctedUV + vec2(-edgeStep.x, -edgeStep.y)).rgb, vec3(0.299, 0.587, 0.114));
    float c10 = dot(texture2D(u_texture, correctedUV + vec2( 0.0,        -edgeStep.y)).rgb, vec3(0.299, 0.587, 0.114));
    float c20 = dot(texture2D(u_texture, correctedUV + vec2( edgeStep.x, -edgeStep.y)).rgb, vec3(0.299, 0.587, 0.114));
    float c01 = dot(texture2D(u_texture, correctedUV + vec2(-edgeStep.x,  0.0)).rgb,        vec3(0.299, 0.587, 0.114));
    float c21 = dot(texture2D(u_texture, correctedUV + vec2( edgeStep.x,  0.0)).rgb,        vec3(0.299, 0.587, 0.114));
    float c02 = dot(texture2D(u_texture, correctedUV + vec2(-edgeStep.x,  edgeStep.y)).rgb, vec3(0.299, 0.587, 0.114));
    float c12 = dot(texture2D(u_texture, correctedUV + vec2( 0.0,         edgeStep.y)).rgb, vec3(0.299, 0.587, 0.114));
    float c22 = dot(texture2D(u_texture, correctedUV + vec2( edgeStep.x,  edgeStep.y)).rgb, vec3(0.299, 0.587, 0.114));

    float h = -c00 - 2.0 * c10 - c20 + c02 + 2.0 * c12 + c22;
    float v = -c00 - 2.0 * c01 - c02 + c20 + 2.0 * c21 + c22;
    float edgeMask = sqrt(h * h + v * v);

    // 收紧边缘，只留主要干道
    edgeMask = smoothstep(0.40, 0.60, edgeMask);

    // ==========================================
    // 3. 动态流光分形噪声 (手动展开循环版)
    // ==========================================
    vec2 uv = correctedUV;
    uv.x *= u_resolution.x / u_resolution.y;
    uv *= 3.0;
    float time = u_time * 0.3;

    float f = 0.0;
    float amp = 1.0;
    float maxAmp = 0.0;
    vec3 p = vec3(uv, time);

    // --- 第 1 层噪声 (原本 loop 的 i=0) ---
    f += amp * (1.0 - abs(noise3D(p)));
    maxAmp += amp;
    amp *= 0.5;
    p *= 2.0;

    // --- 第 2 层噪声 (原本 loop 的 i=1) ---
    f += amp * (1.0 - abs(noise3D(p)));
    maxAmp += amp;

    // 归一化
    f /= maxAmp;

    // 核心细线与大光晕曲线计算
    float coreLine = smoothstep(0.96, 0.99, f);
    float wideGlow = pow(f, 18.5) * 2.0;
    float finalColor = coreLine + wideGlow;

    // ==========================================
    // 4. 遮罩合成输出
    //      如果只想输出描边那就，float resultResult = edgeMask
    //      如果只想输出蒙版那就，float resultResult = finalColor
    // ==========================================
    float resultResult = edgeMask * finalColor;

    gl_FragColor = vec4(vec3(resultResult), 1.0);
}