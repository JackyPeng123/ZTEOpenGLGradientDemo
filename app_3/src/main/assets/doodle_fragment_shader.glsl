precision highp float;
varying vec2 v_uv;

uniform float u_total_length;
uniform float u_stroke_width;
uniform float u_tail_length; // 新增：动态尾巴长度

const vec3 COLOR_BLUE = vec3(0.0, 0.286, 1.0);
const vec3 COLOR_RED = vec3(1.0, 0.0, 0.0);
const vec3 COLOR_YELLOW = vec3(1.0, 1.0, 0.0);

const float CYCLE_LENGTH = 2.0;

// 模拟高斯模糊的衰减函数
float gaussian(float x, float sigma) {
    return exp(-(x * x) / (2.0 * sigma * sigma));
}

void main() {
    float dist_from_head = u_total_length - v_uv.x;

    // 使用外部传入的动态尾巴长度
    float actual_tail_length = max(0.001, u_tail_length);

    if (dist_from_head > actual_tail_length || dist_from_head < 0.0) {
        discard;
    }

    // 1. 底层渐变色计算
    float progress = v_uv.x / CYCLE_LENGTH;
    float pingpong = 1.0 - abs(mod(progress, 2.0) - 1.0);
    vec3 baseColor;
    if (pingpong < 0.5) {
        baseColor = mix(COLOR_BLUE, COLOR_RED, pingpong * 2.0);
    } else {
        baseColor = mix(COLOR_RED, COLOR_YELLOW, (pingpong - 0.5) * 2.0);
    }

    // 计算到中心的距离
    float abs_y = abs(v_uv.y);
    float dist_to_center = abs_y;
    if (dist_from_head < u_stroke_width) {
        float dx = (u_stroke_width - dist_from_head) / u_stroke_width;
        dist_to_center = sqrt(dx * dx + v_uv.y * v_uv.y);
    }

    // ==========================================
    // 长度衰减 (白色层比彩色层稍微短一点)
    // ==========================================
    float norm_dist = dist_from_head / actual_tail_length;
    // 彩色底层的长度衰减 (AE 遮罩透明度关键帧逻辑)
    float tail_fade_base = 0.0;
    if (norm_dist <= 0.2) {
        float t = norm_dist / 0.2;
        tail_fade_base = mix(0.44, 0.90, t);
    } else {
        float t = (norm_dist - 0.2) / 0.8;
        tail_fade_base = mix(0.90, 0.0, t);
    }

    float white_norm_dist = norm_dist / 0.95;
    float tail_fade_white = 0.0;
    if (white_norm_dist <= 1.0) {
        tail_fade_white = pow(1.0 - white_norm_dist, 1.2);
    }

    // ==========================================
    // 宽度与形状计算
    // ==========================================
    float wide_shape = gaussian(dist_to_center, 0.35);
    float narrow_shape = gaussian(dist_to_center, 0.12);

    // ==========================================
    // 分层 Alpha 计算
    // ==========================================
    float color_alpha = wide_shape * tail_fade_base;
    float glow_alpha = wide_shape * tail_fade_white * 2.5;
    float highlight_alpha = narrow_shape * tail_fade_white * 3.0;

    // ==========================================
    // 混合输出 (Additive 线性减淡混合)
    // ==========================================
    vec3 final_color = baseColor * color_alpha;

    float total_white = glow_alpha + highlight_alpha;
    final_color += vec3(1.0) * total_white;

    float final_alpha = clamp(color_alpha + total_white, 0.0, 1.0);

    gl_FragColor = vec4(final_color, final_alpha);
}