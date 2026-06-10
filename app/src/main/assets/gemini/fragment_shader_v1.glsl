precision mediump float;
varying vec2 v_texCoord;
uniform float u_time;  // External time uniform (seconds)
uniform vec2 u_resolution; // External screen resolution (width, height)

// Specialized wiggle function that guarantees movement across the full [0,1] UV range
vec2 fullScreenWiggle(float time, float freq, vec2 seed) {
    // Generate complex paths using offset sine waves
    float x = sin(time * freq + seed.x) * 0.4 + sin(time * freq * 0.8 + seed.y * 2.0) * 0.1;
    float y = cos(time * freq * 1.1 + seed.y) * 0.4 + cos(time * freq * 0.7 + seed.x * 3.0) * 0.1;

    // Normalize result from [-0.5, 0.5] range to [0.0, 1.0] UV range
    return vec2(x + 0.5, y + 0.5);
}

void main() {
    vec2 uv = v_texCoord;
    float t = u_time * 1.5; // Apply the speed multiplier

    // 1. Core Change: Generate unique, full-screen paths for each color.
    // There are NO MORE static base points. The motion is total.
    vec2 p1 = fullScreenWiggle(t, 1.5, vec2(11.4, 34.5)); // Full screen path 1 (formerly Purple)
    vec2 p2 = fullScreenWiggle(t, 2.0, vec2(52.1, 87.3)); // Full screen path 2 (formerly Light Purple)
    vec2 p3 = fullScreenWiggle(t, 2.2, vec2(93.5, 12.8)); // Full screen path 3 (formerly Blue)
    vec2 p4 = fullScreenWiggle(t, 1.7, vec2(44.2, 69.9)); // Full screen path 4 (formerly Yellow)

    // 2. Introduce aspect ratio correction to prevent distance calculation stretching
    float aspect = u_resolution.x / u_resolution.y;
    vec2 uv_corr = vec2(uv.x * aspect, uv.y);

    // Calculate corrected distances from the UV to the animated points
    float d1 = length(uv_corr - vec2(p1.x * aspect, p1.y));
    float d2 = length(uv_corr - vec2(p2.x * aspect, p2.y));
    float d3 = length(uv_corr - vec2(p3.x * aspect, p3.y));
    float d4 = length(uv_corr - vec2(p4.x * aspect, p4.y));

    // 3. Define the Colors from the AE design file (No change here)
    vec3 c1 = vec3(0.827, 0.561, 1.000); // #D38FFF (Purple)
    vec3 c2 = vec3(0.600, 0.647, 1.000); // #99A5FF (Light Purple)
    vec3 c3 = vec3(0.549, 0.718, 1.000); // #8CB7FF (Blue)
    vec3 c4 = vec3(0.996, 0.871, 0.278); // #FEDE47 (Yellow)

    // 4. Color Blending (Optimization from previous turn)
    // Decreasing the blendPower exponent spreads the color more softly,
    // which is essential now that they move such large distances.
    float blendPower = 1.3;
    float baseRadius = 0.05; // Base radius prevents division by zero
    float w1 = 1.0 / (pow(d1, blendPower) + baseRadius);
    float w2 = 1.0 / (pow(d2, blendPower) + baseRadius);
    float w3 = 1.0 / (pow(d3, blendPower) + baseRadius);
    float w4 = 1.0 / (pow(d4, blendPower) + baseRadius);

    // Normalize weights and mix colors
    float totalWeight = w1 + w2 + w3 + w4;
    vec3 finalColor = (c1 * w1 + c2 * w2 + c3 * w3 + c4 * w4) / totalWeight;

    // 5. Post-Processing: Saturation Boost (No change)
    // Apply the requested subtle saturation adjustment (+5)
    float luma = dot(finalColor, vec3(0.299, 0.587, 0.114));
    finalColor = mix(vec3(luma), finalColor, 1.05);

    // 6. Post-Processing: Dithering (No change)
    // Apply the 80% noise to eliminate banding
    float noise = fract(sin(dot(gl_FragCoord.xy, vec2(12.9898, 78.233))) * 43758.5453);
    finalColor += (noise - 0.5) * (0.80 / 255.0);

    gl_FragColor = vec4(finalColor, 1.0);
}