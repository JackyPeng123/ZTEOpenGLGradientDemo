uniform mat4 uMVPMatrix;
attribute vec2 a_position;
attribute vec2 a_uv; // x: 顶点距离起点的绝对物理长度, y: 横向坐标(-1~1)
varying vec2 v_uv;

void main() {
    gl_Position = uMVPMatrix * vec4(a_position, 0.0, 1.0);
    v_uv = a_uv;
}