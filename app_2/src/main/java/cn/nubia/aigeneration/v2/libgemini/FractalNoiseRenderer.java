package cn.nubia.aigeneration.v2.libgemini;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.SystemClock;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 这是实现了分形杂色蒙版的Shader，当质疑效果时可以给动效看看
 */
public class FractalNoiseRenderer implements GLSurfaceView.Renderer {

    private int programId;
    private int aPositionLocation;
    private int uTimeLocation;
    private int uResolutionLocation;

    private FloatBuffer vertexBuffer;
    private long startTime;
    private int width, height;

    private static final String VERTEX_SHADER_STRING = "attribute vec4 a_position;\n" +
            "attribute vec2 a_texCoord;\n" +
            "varying vec2 v_texCoord;\n" +
            "\n" +
            "void main() {\n" +
            "    gl_Position = a_position;\n" +
            "    v_texCoord = a_texCoord;\n" +
            "}\n";

    private static final String FRAGMENT_SHADER_STRING = "precision mediump float;\n" +
            "\n" +
            "uniform vec2 u_resolution;\n" +
            "uniform float u_time;\n" +
            "\n" +
            "// 1. 杂色类型：样条 (Spline) 的底层支持\n" +
            "// 生成 3D 伪随机梯度方向\n" +
            "vec3 hash(vec3 p) {\n" +
            "    p = vec3( dot(p,vec3(127.1, 311.7,  74.7)),\n" +
            "              dot(p,vec3(269.5, 183.3, 246.1)),\n" +
            "              dot(p,vec3(113.5, 271.9, 124.6)));\n" +
            "    return -1.0 + 2.0 * fract(sin(p) * 43758.5453123);\n" +
            "}\n" +
            "\n" +
            "// 标准 3D 梯度噪声 (类似 Perlin Noise)\n" +
            "float noise3D(vec3 p) {\n" +
            "    vec3 i = floor(p);\n" +
            "    vec3 f = fract(p);\n" +
            "    // 样条平滑插值 (Quintic curve: 6x^5 - 15x^4 + 10x^3)\n" +
            "    vec3 u = f * f * f * (f * (f * 6.0 - 15.0) + 10.0);\n" +
            "\n" +
            "    return mix( mix( mix( dot( hash( i + vec3(0.0,0.0,0.0) ), f - vec3(0.0,0.0,0.0) ), \n" +
            "                          dot( hash( i + vec3(1.0,0.0,0.0) ), f - vec3(1.0,0.0,0.0) ), u.x),\n" +
            "                     mix( dot( hash( i + vec3(0.0,1.0,0.0) ), f - vec3(0.0,1.0,0.0) ), \n" +
            "                          dot( hash( i + vec3(1.0,1.0,0.0) ), f - vec3(1.0,1.0,0.0) ), u.x), u.y),\n" +
            "                mix( mix( dot( hash( i + vec3(0.0,0.0,1.0) ), f - vec3(0.0,0.0,1.0) ), \n" +
            "                          dot( hash( i + vec3(1.0,0.0,1.0) ), f - vec3(1.0,0.0,1.0) ), u.x),\n" +
            "                     mix( dot( hash( i + vec3(0.0,1.0,1.0) ), f - vec3(0.0,1.0,1.0) ), \n" +
            "                          dot( hash( i + vec3(1.0,1.0,1.0) ), f - vec3(1.0,1.0,1.0) ), u.x), u.y), u.z );\n" +
            "}\n" +
            "\n" +
            "void main() {\n" +
            "    vec2 uv = gl_FragCoord.xy / u_resolution.xy;\n" +
            "    uv.x *= u_resolution.x / u_resolution.y;\n" +
            "\n" +
            "    // 1. 整体网格缩放\n" +
            "    // 如果觉得原视频的整体纹理比你的更密，可以把 3.0 改成 3.5 或 4.0\n" +
            "    uv *= 3.0; \n" +
            "\n" +
            "    // 2. 演化速度\n" +
            "    float time = u_time * 0.2; \n" +
            "\n" +
            "    float f = 0.0;\n" +
            "    float amp = 1.0;\n" +
            "    float maxAmp = 0.0;\n" +
            "    vec3 p = vec3(uv, time);\n" +
            "\n" +
            "    for(int i = 0; i < 2; i++) {\n" +
            "        float n = noise3D(p);\n" +
            "        // 湍流锐化 + 反转\n" +
            "        f += amp * (1.0 - abs(n));\n" +
            "        \n" +
            "        maxAmp += amp;\n" +
            "        amp *= 0.5;   \n" +
            "        p *= 2.0;     \n" +
            "    }\n" +
            "    f /= maxAmp;\n" +
            "\n" +
            "    // ==================【核心视觉微调区】==================\n" +
            "\n" +
            "    // 3. 创造极细的核心白线\n" +
            "    // 将下限拉到 0.91，上限 0.99。只有最顶峰的噪声能形成这条纯白亮线\n" +
            "    float coreLine = smoothstep(0.96, 0.99, f);\n" +
            "\n" +
            "    // 4. 创造宽广、明显的烟雾光晕\n" +
            "    // 降低指数到 5.5（让光晕扩散得更宽），加大系数到 0.65（让光晕更亮、更明显）\n" +
            "    float wideGlow = pow(f, 18.5) * 2.0;\n" +
            "\n" +
            "    // 5. 混合最终颜色 (OpenGL 会自动把超过 1.0 的部分截断为纯白)\n" +
            "    float finalColor = coreLine + wideGlow;\n" +
            "\n" +
            "    // ====================================================\n" +
            "\n" +
            "    gl_FragColor = vec4(vec3(finalColor), 1.0);\n" +
            "}";

    // 全屏矩形顶点数据 (两个三角形)
    private final float[] vertices = {
            -1.0f,  1.0f,  // 左上
            -1.0f, -1.0f,  // 左下
            1.0f,  1.0f,  // 右上
            1.0f, -1.0f   // 右下
    };

    public FractalNoiseRenderer() {
        ByteBuffer bb = ByteBuffer.allocateDirect(vertices.length * 4);
        bb.order(ByteOrder.nativeOrder());
        vertexBuffer = bb.asFloatBuffer();
        vertexBuffer.put(vertices);
        vertexBuffer.position(0);
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // 在这里编译并链接你的 Vertex Shader 和 Fragment Shader
        // Utils.loadProgram 是你需要自己写的一个通用工具方法，负责加载着色器字符串
        programId = createGLProgram(VERTEX_SHADER_STRING, FRAGMENT_SHADER_STRING);

        aPositionLocation = GLES20.glGetAttribLocation(programId, "a_position");
        uTimeLocation = GLES20.glGetUniformLocation(programId, "u_time");
        uResolutionLocation = GLES20.glGetUniformLocation(programId, "u_resolution");

        startTime = SystemClock.uptimeMillis();
    }

    private int createGLProgram(String vertexSrc, String fragSrc) {
        int program = GLES20.glCreateProgram();
        int vShader = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        int fShader = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);
        GLES20.glShaderSource(vShader, vertexSrc);
        GLES20.glCompileShader(vShader);
        GLES20.glShaderSource(fShader, fragSrc);
        GLES20.glCompileShader(fShader);
        GLES20.glAttachShader(program, vShader);
        GLES20.glAttachShader(program, fShader);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vShader);
        GLES20.glDeleteShader(fShader);
        return program;
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        this.width = width;
        this.height = height;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(programId);

        // 1. 传递顶点位置
        vertexBuffer.position(0);
        GLES20.glVertexAttribPointer(aPositionLocation, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(aPositionLocation);

        // 2. 传递分辨率 u_resolution
        GLES20.glUniform2f(uResolutionLocation, (float) width, (float) height);

        // 3. 传递时间 u_time (控制动画的核心)
        float time = (SystemClock.uptimeMillis() - startTime) / 1000.0f;
        GLES20.glUniform1f(uTimeLocation, time);

        // 4. 绘制全屏矩形 (使用 GL_TRIANGLE_STRIP)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(aPositionLocation);
    }
}