precision highp float;
varying vec2 v_texCoord;

uniform sampler2D u_generating_bitmapTexture;
uniform sampler2D u_generated_bitmapTexture;
uniform int u_state; // 1: GENERATING, 2: GENERATED

void main() {
    if (u_state == 2) {
        gl_FragColor = texture2D(u_generated_bitmapTexture, v_texCoord);
    } else {
        gl_FragColor = texture2D(u_generating_bitmapTexture, v_texCoord);
    }
}