package cn.nubia.redmagickyi.achievement.libgemini;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
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

    // 两个独立渲染程序
    private int maskProgram = 0;
    private int finalProgram = 0;
    private long startTime;

    private final String vertexShaderSource;
    private final String maskShaderSource;
    private final String finalShaderSource;

    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    // FBO 核心组件
    private int[] fboId = new int[1];
    private int[] fboTextureId = new int[1]; // 专门用来挂载 Mask 的独立纹理 ID

    // 句柄缓存
    private int maskTimeHandle, maskResHandle;
    private int finalTextureHandle, finalMaskTextureHandle, finalAlphaHandle;

    private float screenWidth = 1080f;
    private float screenHeight = 1920f;

    private Bitmap mBitmap;
    private boolean mBitmapChanged = false;
    private int mTextureId = -1; // 底图纹理 ID [cite: 1]

    private final float[] vertices = {-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f}; // [cite: 44]
    private final float[] texCoords = {0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f}; // [cite: 44]

    public GradientRenderer() {
        vertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices);
        vertexBuffer.position(0);
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(texCoords);
        texCoordBuffer.position(0);

        // 提前读取三个着色器文件
        vertexShaderSource = loadShaderFast("gemini/vertex_shader.txt");
        maskShaderSource = loadShaderFast("gemini/mask1_fragment_shader.txt");
        finalShaderSource = loadShaderFast("gemini/final_blend_fragment_shader.txt");
    }

    public void setBitmap(Bitmap bitmap) {
        synchronized (this) {
            this.mBitmap = bitmap;
            this.mBitmapChanged = true;
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // 1. 编译并创建负责生成 Mask 纹理的 Program
        maskProgram = createGLProgram(vertexShaderSource, maskShaderSource);
        maskTimeHandle = GLES20.glGetUniformLocation(maskProgram, "u_time"); // [cite: 3]
        maskResHandle = GLES20.glGetUniformLocation(maskProgram, "u_resolution"); // [cite: 3]

        // 2. 编译并创建负责最终融合的 Program
        finalProgram = createGLProgram(vertexShaderSource, finalShaderSource);
        finalTextureHandle = GLES20.glGetUniformLocation(finalProgram, "u_texture"); // [cite: 1]
        finalMaskTextureHandle = GLES20.glGetUniformLocation(finalProgram, "u_maskTexture");
        finalAlphaHandle = GLES20.glGetUniformLocation(finalProgram, "u_alpha");

        // 3. 生成底图纹理 ID
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        mTextureId = textures[0];

        startTime = System.currentTimeMillis();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        this.screenWidth = (float) width;
        this.screenHeight = (float) height;

        // 【核心优化】：当屏幕尺寸发生变化时，销毁并重建匹配分辨率的独立 Mask 纹理与 FBO
        deleteFBO();
        initFBO(width, height);
    }

    private void initFBO(int width, int height) {
        // 创建 FBO 挂载的独立纹理
        GLES20.glGenTextures(1, fboTextureId, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // 创建并绑定 Framebuffer
        GLES20.glGenFramebuffers(1, fboId, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId[0]);
        // 将刚才生成的独立纹理关联到 FBO 的颜色附着点上
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTextureId[0], 0);

        // 解绑，恢复默认状态
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (maskProgram == 0 || finalProgram == 0 || mTextureId == -1 || fboId[0] == 0) return;

        // 检查并更新底层图片纹理数据
        synchronized (this) {
            if (mBitmapChanged && mBitmap != null) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureId);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, mBitmap, 0);
                mBitmapChanged = false;
            }
        }

        float time = (System.currentTimeMillis() - startTime) / 1000.0f;

        // ==========================================
        // 步骤一：进行离屏渲染，将流体画到独立的 Mask 纹理中
        // ==========================================
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboId[0]); // 切到 FBO 缓冲
        GLES20.glViewport(0, 0, (int) screenWidth, (int) screenHeight);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(maskProgram);
        GLES20.glUniform1f(maskTimeHandle, time); // [cite: 3]
        GLES20.glUniform2f(maskResHandle, screenWidth, screenHeight); // [cite: 3]

        drawQuad(GLES20.glGetAttribLocation(maskProgram, "a_position"), GLES20.glGetAttribLocation(maskProgram, "a_texCoord")); // [cite: 44]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); // 解绑 FBO，回到手机屏幕缓冲区

        // ==========================================
        // 步骤二：最终融合渲染（把底图和刚画好的 Mask 纹理混合输出）
        // ==========================================
        GLES20.glViewport(0, 0, (int) screenWidth, (int) screenHeight);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        GLES20.glUseProgram(finalProgram);

        // 绑定底图到纹理单元 0 [cite: 1]
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureId);
        GLES20.glUniform1i(finalTextureHandle, 0); // [cite: 1]

        // 绑定生成的独立 Mask 纹理到纹理单元 1
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureId[0]);
        GLES20.glUniform1i(finalMaskTextureHandle, 1);

        // 计算 0ms~1000ms 周期、15%~50% 范围的 Pingpong 透明度呼吸曲线
        float duration = 1.0f;
        float progress = time / duration;
        float pingpong = Math.abs((float) Math.sin(progress * Math.PI / 2.0));
        float currentAlpha = 0.15f + (0.50f - 0.15f) * pingpong;
        GLES20.glUniform1f(finalAlphaHandle, currentAlpha);

        // 最终绘制到屏幕
        drawQuad(GLES20.glGetAttribLocation(finalProgram, "a_position"), GLES20.glGetAttribLocation(finalProgram, "a_texCoord")); // [cite: 44]
    }

    private void drawQuad(int posHandle, int texHandle) {
        GLES20.glEnableVertexAttribArray(posHandle);
        GLES20.glVertexAttribPointer(posHandle, 2, GLES20.GL_FLOAT, false, 0, vertexBuffer);
        GLES20.glEnableVertexAttribArray(texHandle);
        GLES20.glVertexAttribPointer(texHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(posHandle);
        GLES20.glDisableVertexAttribArray(texHandle);
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

    private void deleteFBO() {
        if (fboId[0] != 0) {
            GLES20.glDeleteFramebuffers(1, fboId, 0);
            fboId[0] = 0;
        }
        if (fboTextureId[0] != 0) {
            GLES20.glDeleteTextures(1, fboTextureId, 0);
            fboTextureId[0] = 0;
        }
    }

    private String loadShaderFast(String fileName) {
        InputStream is = null;
        ByteArrayOutputStream os = null;
        try {
            is = RedmagickyiApplication.getContext().getAssets().open(fileName);
            os = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
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
        deleteFBO();
        if (maskProgram != 0) GLES20.glDeleteProgram(maskProgram);
        if (finalProgram != 0) GLES20.glDeleteProgram(finalProgram);
        if (mTextureId != -1) GLES20.glDeleteTextures(1, new int[]{mTextureId}, 0);
    }
}