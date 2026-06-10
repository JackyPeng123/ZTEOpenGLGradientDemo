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

    // 三个独立的着色器程序
    private int maskProgram = 0;
    private int glowProgram = 0;
    private int finalProgram = 0;
    private long startTime;

    private final String vertexShaderSource;
    private final String maskShaderSource;
    private final String glowShaderSource;
    private final String finalShaderSource;

    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    // FBO 阵列：[0] 给 Mask 纹理，[1] 给 Glow 边缘发光纹理
    private int[] fboIds = new int[2];
    private int[] fboTextureIds = new int[2];

    // 各 Program 句柄
    private int maskTimeHandle, maskResHandle;
    private int glowMaskTexHandle, glowResHandle, glowTimeHandle, glowIntensityHandle, glowRadiusHandle;
    private int finalTextureHandle, finalMaskTextureHandle, finalGlowTextureHandle, finalAlphaHandle;

    private float screenWidth = 1080f;
    private float screenHeight = 1920f;

    private Bitmap mBitmap;
    private boolean mBitmapChanged = false;
    private int mTextureId = -1; // 底图

    private final float[] vertices = {-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f}; //
    private final float[] texCoords = {0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f}; //

    public GradientRenderer() {
        vertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices);
        vertexBuffer.position(0);
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(texCoords);
        texCoordBuffer.position(0);

        // 批量读入 4 个着色器
        vertexShaderSource = loadShaderFast("vertex_shader.txt");
        maskShaderSource = loadShaderFast("mask1_fragment_shader.txt");
        glowShaderSource = loadShaderFast("glow_edge_fragment_shader.txt");
        finalShaderSource = loadShaderFast("final_blend_fragment_shader.txt");
    }

    public void setBitmap(Bitmap bitmap) {
        synchronized (this) {
            this.mBitmap = bitmap;
            this.mBitmapChanged = true;
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        // Pass 1: Mask Program
        maskProgram = createGLProgram(vertexShaderSource, maskShaderSource);
        maskTimeHandle = GLES20.glGetUniformLocation(maskProgram, "u_time"); //
        maskResHandle = GLES20.glGetUniformLocation(maskProgram, "u_resolution"); //

        // Pass 2: Glow Program
        glowProgram = createGLProgram(vertexShaderSource, glowShaderSource);
        glowMaskTexHandle = GLES20.glGetUniformLocation(glowProgram, "u_maskTexture");
        glowResHandle = GLES20.glGetUniformLocation(glowProgram, "u_resolution");
        glowTimeHandle = GLES20.glGetUniformLocation(glowProgram, "u_time");
        glowIntensityHandle = GLES20.glGetUniformLocation(glowProgram, "u_glowIntensity");
        glowRadiusHandle = GLES20.glGetUniformLocation(glowProgram, "u_glowRadius");

        // Pass 3: Final Blend Program
        finalProgram = createGLProgram(vertexShaderSource, finalShaderSource);
        finalTextureHandle = GLES20.glGetUniformLocation(finalProgram, "u_texture"); //
        finalMaskTextureHandle = GLES20.glGetUniformLocation(finalProgram, "u_maskTexture");
        finalGlowTextureHandle = GLES20.glGetUniformLocation(finalProgram, "u_glowTexture");
        finalAlphaHandle = GLES20.glGetUniformLocation(finalProgram, "u_alpha");

        // 生成底图纹理句柄
        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        mTextureId = textures[0];

        startTime = System.currentTimeMillis();
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        this.screenWidth = (float) width;
        this.screenHeight = (float) height;

        // 旋转或尺寸变动时重建双通道 FBO
        deleteFBOs();
        initFBOs(width, height);
    }

    private void initFBOs(int width, int height) {
        GLES20.glGenTextures(2, fboTextureIds, 0);
        GLES20.glGenFramebuffers(2, fboIds, 0);

        for (int i = 0; i < 2; i++) {
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureIds[i]);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[i]);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fboTextureIds[i], 0);
        }
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (maskProgram == 0 || glowProgram == 0 || finalProgram == 0 || fboIds[0] == 0) return;

        // 动态加载更新外部 Bitmap 到旧纹理
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
        // PASS 1：渲染流体，并保存至 fboIds[0] (Mask纹理)
        // ==========================================
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0]);
        GLES20.glViewport(0, 0, (int) screenWidth, (int) screenHeight);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(maskProgram);
        GLES20.glUniform1f(maskTimeHandle, time); // [cite: 3]
        GLES20.glUniform2f(maskResHandle, screenWidth, screenHeight); // [cite: 3]
        drawQuad(GLES20.glGetAttribLocation(maskProgram, "a_position"), GLES20.glGetAttribLocation(maskProgram, "a_texCoord")); // [cite: 44]

        // ==========================================
        // PASS 2：【核心修改】查找背景图片的边缘并发光 -> fboIds[1]
        // ==========================================
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[1]);
        GLES20.glViewport(0, 0, (int) screenWidth, (int) screenHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(glowProgram);
        GLES20.glUniform2f(glowResHandle, screenWidth, screenHeight);
        GLES20.glUniform1f(glowTimeHandle, time);

        // 调整 AE 发光参数
        GLES20.glUniform1f(glowIntensityHandle, 3.0f); // 发光强度
        GLES20.glUniform1f(glowRadiusHandle, 4.0f);    // 发光扩散半径

        // ⚠️【关键修改点】：将原先绑定的 fboTextureIds[0] 改为 mTextureId (背景图片纹理)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureId); // 直接把背景图传给 Sobel 算子
        GLES20.glUniform1i(glowMaskTexHandle, 0);

        drawQuad(GLES20.glGetAttribLocation(glowProgram, "a_position"), GLES20.glGetAttribLocation(glowProgram, "a_texCoord"));

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0); // 解绑回主屏幕

        // ==========================================
        // PASS 3：最终合成（底图 + 流体Mask + 背景边缘发光） -> 屏幕
         // ==========================================
        GLES20.glViewport(0, 0, (int) screenWidth, (int) screenHeight);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        GLES20.glUseProgram(finalProgram);

        // 绑定纹理 0：背景底图
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mTextureId);
        GLES20.glUniform1i(finalTextureHandle, 0);

        // 绑定纹理 1：流体 Mask 纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureIds[0]); // Pass 1 生成的独立流体纹理
        GLES20.glUniform1i(finalMaskTextureHandle, 1);

        // 绑定纹理 2：背景发光 Glow 纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureIds[1]); // Pass 2 生成的背景边缘发光纹理
        GLES20.glUniform1i(finalGlowTextureHandle, 2);

        // 计算 15% 到 50% 的流体呼吸曲线 (Pingpong)
        float duration = 1.0f;
        float progress = time / duration;
        float pingpong = Math.abs((float) Math.sin(progress * Math.PI / 2.0));
        float currentAlpha = 0.15f + (0.50f - 0.15f) * pingpong;
        GLES20.glUniform1f(finalAlphaHandle, currentAlpha);

        int finalTimeHandle = GLES20.glGetUniformLocation(finalProgram, "u_time");
        GLES20.glUniform1f(finalTimeHandle, time);

        drawQuad(GLES20.glGetAttribLocation(finalProgram, "a_position"), GLES20.glGetAttribLocation(finalProgram, "a_texCoord"));
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

    private void deleteFBOs() {
        if (fboIds[0] != 0) {
            GLES20.glDeleteFramebuffers(2, fboIds, 0);
            fboIds[0] = 0; fboIds[1] = 0;
        }
        if (fboTextureIds[0] != 0) {
            GLES20.glDeleteTextures(2, fboTextureIds, 0);
            fboTextureIds[0] = 0; fboTextureIds[1] = 0;
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
        deleteFBOs();
        if (maskProgram != 0) GLES20.glDeleteProgram(maskProgram);
        if (glowProgram != 0) GLES20.glDeleteProgram(glowProgram);
        if (finalProgram != 0) GLES20.glDeleteProgram(finalProgram);
        if (mTextureId != -1) GLES20.glDeleteTextures(1, new int[]{mTextureId}, 0);
    }
}