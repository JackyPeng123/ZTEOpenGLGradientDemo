package cn.nubia.redmagickyi.achievement.libgemini;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import cn.nubia.redmagickyi.achievement.RedmagickyiApplication;

public class GradientRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "GradientRenderer";

    private int program = 0;
    private long startTime;

    // 缓存 Shader 字符串，避免在 OpenGL 线程中高频做 I/O 和按行生成 String
    private final String vertexShaderSource;
    private final String fragmentShaderSource;

    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    // Uniform 句柄
    private int positionHandle;
    private int texCoordHandle;
    private int timeHandle;
    private int resolutionHandle;

    // 运行时屏幕分辨率缓存
    private float screenWidth = 1080f;
    private float screenHeight = 1920f;

    private final float[] vertices = {
            -1.0f, -1.0f,
            1.0f, -1.0f,
            -1.0f,  1.0f,
            1.0f,  1.0f
    };

    private final float[] texCoords = {
            0.0f, 1.0f,
            1.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f
    };

    public GradientRenderer() {
        // 1. 内存缓冲区初始化保持高效不变
        vertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(vertices);
        vertexBuffer.position(0);

        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .put(texCoords);
        texCoordBuffer.position(0);

        // 【优化点 1】: 在构造函数中提前一次性读完文件，规避 OpenGL 线程重建时的 I/O 卡顿
        vertexShaderSource = loadShaderFast("gemini/vertex_shader.glsl");
        fragmentShaderSource = loadShaderFast("gemini/fragment_shader.glsl");
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // 【优化点 2】: 防御性显存清理。如果旧程序存在，先销毁，防止切后台/旋转屏幕时显存无限制泄漏
        if (program != 0) {
            GLES20.glDeleteProgram(program);
            program = 0;
        }

        // 创建并编译 Program
        program = GLES20.glCreateProgram();
        int vertexShaderId = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER);
        int fragmentShaderId = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER);

        GLES20.glShaderSource(vertexShaderId, vertexShaderSource);
        GLES20.glCompileShader(vertexShaderId);

        GLES20.glShaderSource(fragmentShaderId, fragmentShaderSource);
        GLES20.glCompileShader(fragmentShaderId);

        GLES20.glAttachShader(program, vertexShaderId);
        GLES20.glAttachShader(program, fragmentShaderId);
        GLES20.glLinkProgram(program);

        // 编译完立即释放 Shader 单体连接，进一步压榨并优化显存空间
        GLES20.glDeleteShader(vertexShaderId);
        GLES20.glDeleteShader(fragmentShaderId);

        // 获取属性及各种 Uniform 位置
        positionHandle = GLES20.glGetAttribLocation(program, "a_position");
        texCoordHandle = GLES20.glGetAttribLocation(program, "a_texCoord");
        timeHandle = GLES20.glGetUniformLocation(program, "u_time");
        resolutionHandle = GLES20.glGetUniformLocation(program, "u_resolution");

        startTime = System.currentTimeMillis();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        GLES20.glViewport(0, 0, width, height);
        // 捕获真实物理分辨率供着色器做长宽比校准
        this.screenWidth = (float) width;
        this.screenHeight = (float) height;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        // 必须是 (0,0,0,0) 完全清空，不能带任何颜色权重！
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        if (program == 0) return;
        GLES20.glUseProgram(program);

        // 1. 递送时间驱动
        float time = (System.currentTimeMillis() - startTime) / 1000.0f;
        GLES20.glUniform1f(timeHandle, time);

        // 2. 递送物理分辨率（规避图像拉伸变形）
        GLES20.glUniform2f(resolutionHandle, screenWidth, screenHeight);

        // 4. 设置顶点属性指针
        GLES20.glEnableVertexAttribArray(positionHandle);
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);

        GLES20.glEnableVertexAttribArray(texCoordHandle);
        GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        // 5. 绘制全屏流体
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        // 6. 状态清理
        GLES20.glDisableVertexAttribArray(positionHandle);
        GLES20.glDisableVertexAttribArray(texCoordHandle);
    }

    /**
     * 【优化点 3】: 高速流式批量读取。抛弃掉极其低效的 readLine() 机制，
     * 采用内存块直读技术，瞬间完成加载，零多余临时的 String 产生，从源头上斩断 GC 带来的卡顿。
     */
    private String loadShaderFast(String fileName) {
        InputStream is = null;
        ByteArrayOutputStream os = null;
        try {
            is = RedmagickyiApplication.getContext().getAssets().open(fileName);
            os = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096]; // 4KB 高速缓存块
            int len;
            while ((len = is.read(buffer)) != -1) {
                os.write(buffer, 0, len);
            }
            return os.toString("UTF-8");
        } catch (IOException e) {
            Log.e(TAG, "Shader file read failed: " + fileName, e);
            throw new RuntimeException(e);
        } finally {
            try {
                if (os != null) os.close();
                if (is != null) is.close();
            } catch (IOException ignored) {}
        }
    }

    public void onDestroy() {
        if (program != 0) {
            GLES20.glDeleteProgram(program);
        }
    }
}