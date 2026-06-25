uniform mat4 uMVPMatrix;
attribute vec4 a_position;
attribute vec2 a_texCoord;
varying vec2 v_texCoord;

void main() {
    // 应用矩阵变换
    gl_Position = uMVPMatrix * a_position;
    v_texCoord = a_texCoord;
}