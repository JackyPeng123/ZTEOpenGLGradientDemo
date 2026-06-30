package cn.nubia.aigeneration.v4.libgemini;

import android.graphics.Bitmap;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.IntDef;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import cn.nubia.aigeneration.v4.ApplicationContext;

public class GradientRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "GradientRenderer";
    private static final int STATE_IDEL = 0;
    public static final int STATE_GENERATING = 1, STATE_GENERATED = 2;
    @Retention(RetentionPolicy.SOURCE) @IntDef({STATE_IDEL, STATE_GENERATING, STATE_GENERATED}) @interface State {}

    private static final float GENERATING_SWEEP_START_TIMESTAMP = 0f;
    private static final float GENERATING_SWEEP_END_TIMESTAMP = GENERATING_SWEEP_START_TIMESTAMP + 0.6f;
    private static final float GENERATING_GRADIENT_BG_ENTER_START_TIMESTAMP = 0.25f;
    private static final float GENERATING_GRADIENT_BG_ENTER_END_TIMESTAMP = GENERATING_GRADIENT_BG_ENTER_START_TIMESTAMP + 0.5f;
    private static final float GENERATING_GLOW_ENTER_START_TIMESTAMP = 0.5f;
    private static final float GENERATING_GLOW_ENTER_END_TIMESTAMP = GENERATING_GLOW_ENTER_START_TIMESTAMP + 0.3f;

    private static final float GENERATING_GRADIENT_BG_EXIT_START_TIMESTAMP = 0f;
    private static final float GENERATING_GRADIENT_BG_EXIT_END_TIMESTAMP = GENERATING_GRADIENT_BG_EXIT_START_TIMESTAMP + 0.15f;
    private static final float GENERATING_GLOW_EXIT_START_TIMESTAMP = 0f;
    private static final float GENERATING_GLOW_EXIT_END_TIMESTAMP = GENERATING_GLOW_EXIT_START_TIMESTAMP + 0.15f;

    private volatile @State int state = STATE_IDEL, stateTemp = STATE_IDEL;

    private int generatingSweepProgram = 0;
    private int generatingGradientBgProgram = 0;
    private int generatingGlowProgram = 0;
    private int finalProgram = 0;

    private final String vertexShaderSource;
    private final String generatingSweepShaderSource;
    private final String generatingGradientBgShaderSource;
    private final String generatingGlowShaderSource;
    private final String finalShaderSource;

    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    private int[] fboIds = new int[3];
    private int[] fboTextureIds = new int[3];

    private float[] generatedPixelOffset = new float[4];

    private int generatingSweepTimeHandle, generatingSweepDurationHandler, generatingSweepResHandle;
    private int generatingGradientBgTimeHandle, generatingGradientBgResHandle;
    private int generatingGlowGradientBgTexHandle, generatingGlowResHandle, generatingGlowTimeHandle, generatingGlowIntensityHandle, generatingGlowRadiusHandle;
    private int finalGeneratingBitmapTextureHandle, finalGeneratingSweepTextureHandle, finalGeneratingSweepAlphaHandle, finalGeneratingGradientBgTextureHandle, finalGeneratingGradientBgAlphaHandle, finalGeneratingGlowTextureHandle, finalGeneratingGlowAlphaHandle;
    private int finalResHandle;
    private int finalGeneratedOffsetHandle;
    private int finalGeneratedProgressHandle;
    private int finalDensityHandle;
    private int finalOutsideFadeProgressHandle;
    private int finalBorderFadeOutProgressHandle;
    private int finalGridFadeOutProgressHandle;

    private float screenWidth = 1080f;
    private float screenHeight = 1920f;

    // 【新增】内部自适应裁剪区域和尺寸改变标志位
    private int drawX = 0, drawY = 0, drawW = 0, drawH = 0;
    private boolean mSizeChanged = false;

    // 【新增】用于通知 View 层刷新圆角 Outline 的回调
    public interface OnDrawRectChangedListener {
        void onDrawRectChanged(int left, int top, int width, int height);
    }
    private OnDrawRectChangedListener mOnDrawRectChangedListener;
    public void setOnDrawRectChangedListener(OnDrawRectChangedListener listener) {
        this.mOnDrawRectChangedListener = listener;
    }

    private long startTime;

    private Bitmap mGeneratingBitmap;
    private boolean mGeneratingBitmapChanged = false;
    private int mGeneratingBitmapTextureId = -1;

    private float exitStartGradientBgAlpha = 0f;
    private float exitStartGlowAlpha = 0f;

    private final float[] vertices = {-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f};
    private final float[] texCoords = {0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f};

    public GradientRenderer() {
        vertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices);
        vertexBuffer.position(0);
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(texCoords);
        texCoordBuffer.position(0);

        vertexShaderSource = loadShaderFast("vertex_shader.glsl");
        generatingSweepShaderSource = loadShaderFast("generating_sweep_light_fragment_shader.glsl");
        generatingGradientBgShaderSource = loadShaderFast("generating_gradient_background_fragment_shader.glsl");
        generatingGlowShaderSource = loadShaderFast("generating_glow_edge_fragment_shader.glsl");
        finalShaderSource = loadShaderFast("final_blend_fragment_shader.glsl");
    }

    public void setGeneratingBitmap(Bitmap bitmap) {
        synchronized (this) {
            this.mGeneratingBitmap = bitmap;
            this.mGeneratingBitmapChanged = true;
            this.mSizeChanged = true; // 【修改】传入图片时触发重新计算区域
        }
    }

    public void setGeneratedPixelOffset(float left, float top, float right, float bottom) {
        synchronized (this) {
            this.generatedPixelOffset[0] = left;
            this.generatedPixelOffset[1] = top;
            this.generatedPixelOffset[2] = right;
            this.generatedPixelOffset[3] = bottom;
        }
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        generatingSweepProgram = createGLProgram(vertexShaderSource, generatingSweepShaderSource);
        generatingSweepTimeHandle = GLES20.glGetUniformLocation(generatingSweepProgram, "u_time");
        generatingSweepDurationHandler = GLES20.glGetUniformLocation(generatingSweepProgram, "u_duration");
        generatingSweepResHandle = GLES20.glGetUniformLocation(generatingSweepProgram, "u_resolution");

        generatingGradientBgProgram = createGLProgram(vertexShaderSource, generatingGradientBgShaderSource);
        generatingGradientBgTimeHandle = GLES20.glGetUniformLocation(generatingGradientBgProgram, "u_time");
        generatingGradientBgResHandle = GLES20.glGetUniformLocation(generatingGradientBgProgram, "u_resolution");

        generatingGlowProgram = createGLProgram(vertexShaderSource, generatingGlowShaderSource);
        generatingGlowGradientBgTexHandle = GLES20.glGetUniformLocation(generatingGlowProgram, "u_gradientBgTexture");
        generatingGlowResHandle = GLES20.glGetUniformLocation(generatingGlowProgram, "u_resolution");
        generatingGlowTimeHandle = GLES20.glGetUniformLocation(generatingGlowProgram, "u_time");
        generatingGlowIntensityHandle = GLES20.glGetUniformLocation(generatingGlowProgram, "u_glowIntensity");
        generatingGlowRadiusHandle = GLES20.glGetUniformLocation(generatingGlowProgram, "u_glowRadius");

        finalProgram = createGLProgram(vertexShaderSource, finalShaderSource);
        finalGeneratingBitmapTextureHandle = GLES20.glGetUniformLocation(finalProgram, "u_generating_bitmapTexture");
        finalGeneratingSweepTextureHandle = GLES20.glGetUniformLocation(finalProgram, "u_generating_sweepTexture");
        finalGeneratingSweepAlphaHandle = GLES20.glGetUniformLocation(finalProgram, "u_generating_sweepAlpha");
        finalGeneratingGradientBgTextureHandle = GLES20.glGetUniformLocation(finalProgram, "u_generating_gradientBgTexture");
        finalGeneratingGradientBgAlphaHandle = GLES20.glGetUniformLocation(finalProgram, "u_generating_gradientBgAlpha");
        finalGeneratingGlowTextureHandle = GLES20.glGetUniformLocation(finalProgram, "u_generating_glowTexture");
        finalGeneratingGlowAlphaHandle = GLES20.glGetUniformLocation(finalProgram, "u_generating_glowAlpha");
        finalResHandle = GLES20.glGetUniformLocation(finalProgram, "u_resolution");

        finalGeneratedOffsetHandle = GLES20.glGetUniformLocation(finalProgram, "u_generatedOffset");
        finalGeneratedProgressHandle = GLES20.glGetUniformLocation(finalProgram, "u_generatedProgress");
        finalDensityHandle = GLES20.glGetUniformLocation(finalProgram, "u_density");
        finalOutsideFadeProgressHandle = GLES20.glGetUniformLocation(finalProgram, "u_outsideFadeProgress");
        finalBorderFadeOutProgressHandle = GLES20.glGetUniformLocation(finalProgram, "u_borderFadeOutProgress");
        finalGridFadeOutProgressHandle = GLES20.glGetUniformLocation(finalProgram, "u_gridFadeOutProgress");

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        mGeneratingBitmapTextureId = textures[0];

        synchronized (this) {
            if (mGeneratingBitmap != null) {
                mGeneratingBitmapChanged = true;
            }
        }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        this.screenWidth = (float) width;
        this.screenHeight = (float) height;
        synchronized (this) {
            this.mSizeChanged = true; // 【修改】容器改变时重新计算区域
        }
    }

    private void initFBOs(int width, int height) {
        GLES20.glGenTextures(3, fboTextureIds, 0);
        GLES20.glGenFramebuffers(3, fboIds, 0);

        for (int i = 0; i < 3; i++) {
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
        // 【修改】核心自适应逻辑：动态按 fitCenter 计算最终的 drawRect
        synchronized (this) {
            if (mSizeChanged && mGeneratingBitmap != null && screenWidth > 0 && screenHeight > 0) {
                int bitmapW = mGeneratingBitmap.getWidth();
                int bitmapH = mGeneratingBitmap.getHeight();

                float containerRatio = screenWidth / screenHeight;
                float bitmapRatio = (float) bitmapW / bitmapH;
                float targetW, targetH;

                if (bitmapRatio > containerRatio) {
                    targetW = screenWidth;
                    targetH = screenWidth / bitmapRatio;
                } else {
                    targetW = screenHeight * bitmapRatio;
                    targetH = screenHeight;
                }

                int newDrawX = Math.round((screenWidth - targetW) / 2f);
                int newDrawY = Math.round((screenHeight - targetH) / 2f);
                int newDrawW = Math.round(targetW);
                int newDrawH = Math.round(targetH);

                if (newDrawX != drawX || newDrawY != drawY || newDrawW != drawW || newDrawH != drawH) {
                    this.drawX = newDrawX;
                    this.drawY = newDrawY;
                    this.drawW = newDrawW;
                    this.drawH = newDrawH;

                    // 仅在真实渲染区间确定后重建刚好匹配大小的 FBO 节省内存
                    deleteFBOs();
                    if (drawW > 0 && drawH > 0) {
                        initFBOs(drawW, drawH);
                    }
                    // 回调通知外层 View 去主线程刷新圆角裁剪
                    if (mOnDrawRectChangedListener != null) {
                        mOnDrawRectChangedListener.onDrawRectChanged(drawX, drawY, drawW, drawH);
                    }
                }
                mSizeChanged = false;
            }
        }

        if (generatingSweepProgram == 0 || generatingGradientBgProgram == 0 || generatingGlowProgram == 0 || finalProgram == 0 || drawW == 0 || drawH == 0 || fboIds[0] == 0) return;

        synchronized (this) {
            if (mGeneratingBitmapChanged && mGeneratingBitmap != null) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mGeneratingBitmapTextureId);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, mGeneratingBitmap, 0);
                mGeneratingBitmapChanged = false;
            }
        }

        float keepSecond = onStateChanged();

        // ==========================================
        // PASS 0：渲染扫光 -> 视口完全匹配图片实际尺寸 drawW, drawH
        // ==========================================
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0]);
        GLES20.glViewport(0, 0, drawW, drawH);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(generatingSweepProgram);
        GLES20.glUniform1f(generatingSweepTimeHandle, keepSecond - GENERATING_SWEEP_START_TIMESTAMP);
        GLES20.glUniform1f(generatingSweepDurationHandler, GENERATING_SWEEP_END_TIMESTAMP - GENERATING_SWEEP_START_TIMESTAMP);
        GLES20.glUniform2f(generatingSweepResHandle, (float) drawW, (float) drawH);
        drawQuad(GLES20.glGetAttribLocation(generatingSweepProgram, "a_position"), GLES20.glGetAttribLocation(generatingSweepProgram, "a_texCoord"));

        // ==========================================
        // PASS 1：渲染流体 -> 视口完全匹配图片实际尺寸 drawW, drawH
        // ==========================================
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[1]);
        GLES20.glViewport(0, 0, drawW, drawH);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(generatingGradientBgProgram);
        GLES20.glUniform1f(generatingGradientBgTimeHandle, keepSecond - GENERATING_GRADIENT_BG_ENTER_START_TIMESTAMP);
        GLES20.glUniform2f(generatingGradientBgResHandle, (float) drawW, (float) drawH);
        drawQuad(GLES20.glGetAttribLocation(generatingGradientBgProgram, "a_position"), GLES20.glGetAttribLocation(generatingGradientBgProgram, "a_texCoord"));

        // ==========================================
        // PASS 2：查找边缘 -> 视口完全匹配图片实际尺寸 drawW, drawH
        // ==========================================
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[2]);
        GLES20.glViewport(0, 0, drawW, drawH);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(generatingGlowProgram);
        GLES20.glUniform2f(generatingGlowResHandle, (float) drawW, (float) drawH);
        GLES20.glUniform1f(generatingGlowTimeHandle, keepSecond - GENERATING_GLOW_ENTER_START_TIMESTAMP);
        GLES20.glUniform1f(generatingGlowIntensityHandle, 3.0f);
        GLES20.glUniform1f(generatingGlowRadiusHandle, 4.0f);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mGeneratingBitmapTextureId);
        GLES20.glUniform1i(generatingGlowGradientBgTexHandle, 0);
        drawQuad(GLES20.glGetAttribLocation(generatingGlowProgram, "a_position"), GLES20.glGetAttribLocation(generatingGlowProgram, "a_texCoord"));

        // ==========================================
        // PASS 3：最终合成 -> 映射居中到全屏窗口上
        // ==========================================
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        GLES20.glViewport(0, 0, (int) screenWidth, (int) screenHeight);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        float currentDrawX = drawX;
        float currentDrawY = drawY;
        float currentDrawW = drawW;
        float currentDrawH = drawH;

        float outsideFadeProgress = 0f; // 【新增】用于记录区域外的淡出进度

        if (state == STATE_GENERATED) {
            float timeSinceGenerated = keepSecond;
            float zoomDelay = 0.4f + Math.max(GENERATING_GRADIENT_BG_EXIT_END_TIMESTAMP, GENERATING_GLOW_EXIT_END_TIMESTAMP);      // 400ms 等待
            float zoomDuration = 0.25f;  // 【修改】250ms 放大动画时长
            float fadeDelay = zoomDelay + zoomDuration; // 650ms 开始淡出
            float fadeDuration = 0.25f;  // 【新增】250ms 区域外淡出时长

            // 1. 处理缩放
            if (timeSinceGenerated > zoomDelay) {
                float rawProgress = Math.min(1.0f, (timeSinceGenerated - zoomDelay) / zoomDuration);
                float curvedProgress = getCubicBezierY(rawProgress, 0.33f, 0.00f, 0.00f, 1.00f);

                float subW_norm = Math.max(0.01f, 1.0f - generatedPixelOffset[0] - generatedPixelOffset[2]);
                float subH_norm = Math.max(0.01f, 1.0f - generatedPixelOffset[1] - generatedPixelOffset[3]);

                float startSubW = drawW * subW_norm;
                float startSubH = drawH * subH_norm;

                float scaleX = screenWidth / startSubW;
                float scaleY = screenHeight / startSubH;
                float finalScale = Math.min(scaleX, scaleY);

                float targetDrawW = drawW * finalScale;
                float targetDrawH = drawH * finalScale;

                float subCenterX_rel = targetDrawW * (generatedPixelOffset[0] + subW_norm / 2.0f);
                float subCenterY_rel = targetDrawH * (generatedPixelOffset[1] + subH_norm / 2.0f);

                float targetDrawX = (screenWidth / 2.0f) - subCenterX_rel;
                float targetDrawY = (screenHeight / 2.0f) - subCenterY_rel;

                currentDrawX = drawX + (targetDrawX - drawX) * curvedProgress;
                currentDrawY = drawY + (targetDrawY - drawY) * curvedProgress;
                currentDrawW = drawW + (targetDrawW - drawW) * curvedProgress;
                currentDrawH = drawH + (targetDrawH - drawH) * curvedProgress;
            }

            // 2. 【新增】处理区域外淡出
            if (timeSinceGenerated > fadeDelay) {
                outsideFadeProgress = Math.min(1.0f, (timeSinceGenerated - fadeDelay) / fadeDuration);
            }
        }

        // 视口及Uniform传值
        int glDrawY = (int) screenHeight - (int) currentDrawY - (int) currentDrawH;
        GLES20.glViewport((int) currentDrawX, glDrawY, (int) currentDrawW, (int) currentDrawH);

        // =================【核心修复：显式开启 Alpha 混合模式】=================
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);
        // ===================================================================

        GLES20.glUseProgram(finalProgram);
        GLES20.glUniform2f(finalResHandle, currentDrawW, currentDrawH);

        float density = ApplicationContext.getContext().getResources().getDisplayMetrics().density;
        GLES20.glUniform1f(finalDensityHandle, density);
        GLES20.glUniform4f(finalGeneratedOffsetHandle, generatedPixelOffset[0], generatedPixelOffset[1], generatedPixelOffset[2], generatedPixelOffset[3]);

        float generatedProgress = 0f;
        float borderFadeOutProgress = 0f; // 【新增】虚线框淡出进度
        float gridFadeOutProgress = 0f;   // 【新增】辅助线淡出进度

        if (state == STATE_GENERATED) {
            float timeSinceGenerated = keepSecond;

            // 原有的入场渐隐逻辑保持不变
            float zoomDelay = Math.max(GENERATING_GRADIENT_BG_EXIT_END_TIMESTAMP, GENERATING_GLOW_EXIT_END_TIMESTAMP);
            if (timeSinceGenerated > zoomDelay) {
                generatedProgress = Math.min(1.0f, (timeSinceGenerated - zoomDelay) / 0.25f);
            }

            // 【新增】1. 虚线框：525ms 后开始，持续 125ms
            float borderFadeDelay = Math.max(GENERATING_GRADIENT_BG_EXIT_END_TIMESTAMP, GENERATING_GLOW_EXIT_END_TIMESTAMP) + 0.525f;
            float borderFadeDuration = 0.125f;
            if (timeSinceGenerated > borderFadeDelay) {
                borderFadeOutProgress = Math.min(1.0f, (timeSinceGenerated - borderFadeDelay) / borderFadeDuration);
            }

            // 【新增】2. 辅助线：800ms 后开始，持续 250ms
            float gridFadeDelay = Math.max(GENERATING_GRADIENT_BG_EXIT_END_TIMESTAMP, GENERATING_GLOW_EXIT_END_TIMESTAMP) + 0.800f;
            float gridFadeDuration = 0.250f;
            if (timeSinceGenerated > gridFadeDelay) {
                gridFadeOutProgress = Math.min(1.0f, (timeSinceGenerated - gridFadeDelay) / gridFadeDuration);
            }
        }

        GLES20.glUniform1f(finalGeneratedProgressHandle, generatedProgress);
        GLES20.glUniform1f(finalOutsideFadeProgressHandle, outsideFadeProgress);
        // 【新增】传递两个淡出进度给 Shader
        GLES20.glUniform1f(finalBorderFadeOutProgressHandle, borderFadeOutProgress);
        GLES20.glUniform1f(finalGridFadeOutProgressHandle, gridFadeOutProgress);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mGeneratingBitmapTextureId);
        GLES20.glUniform1i(finalGeneratingBitmapTextureHandle, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureIds[0]);
        GLES20.glUniform1i(finalGeneratingSweepTextureHandle, 1);
        GLES20.glUniform1f(finalGeneratingSweepAlphaHandle, getSweepAlpha(state, keepSecond));

        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureIds[1]);
        GLES20.glUniform1i(finalGeneratingGradientBgTextureHandle, 2);
        GLES20.glUniform1f(finalGeneratingGradientBgAlphaHandle, getGradientBgAlpha(state, keepSecond));

        GLES20.glActiveTexture(GLES20.GL_TEXTURE3);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fboTextureIds[2]);
        GLES20.glUniform1i(finalGeneratingGlowTextureHandle, 3);
        GLES20.glUniform1f(finalGeneratingGlowAlphaHandle, getGlowAlpha(state, keepSecond));

        // 执行绘制
        drawQuad(GLES20.glGetAttribLocation(finalProgram, "a_position"), GLES20.glGetAttribLocation(finalProgram, "a_texCoord"));

        // =================【修复结束：绘制完后关闭混合，恢复现场】=================
        GLES20.glDisable(GLES20.GL_BLEND);
        // ===================================================================
    }

    private float getSweepAlpha(int state, float time) {
        float alpha = 1.0f;
        if (state == STATE_GENERATING) {
            if (time < GENERATING_SWEEP_START_TIMESTAMP) {
                alpha = 1.0f;
            } else if (time > GENERATING_SWEEP_END_TIMESTAMP) {
                alpha = 0f;
            } else {
                float duration = GENERATING_SWEEP_END_TIMESTAMP - GENERATING_SWEEP_START_TIMESTAMP;
                alpha = Math.max(0f, (1.0f - (time - GENERATING_SWEEP_START_TIMESTAMP) / duration));
            }
        } else {
            alpha = 0f;
        }
        return alpha;
    }

    private float getGradientBgAlpha(int state, float time) {
        float alpha = 0f;
        if (state == STATE_GENERATING) {
            if (time < GENERATING_GRADIENT_BG_ENTER_START_TIMESTAMP) {
                alpha = 0f;
            } else if (time > GENERATING_GRADIENT_BG_ENTER_END_TIMESTAMP) {
                float duration = 1.0f;
                float progress = (time - GENERATING_GRADIENT_BG_ENTER_END_TIMESTAMP) / duration;
                float pingpong = Math.abs((float) Math.sin(progress * Math.PI / 2.0));
                alpha = 0.15f + (0.50f - 0.15f) * pingpong;
            } else {
                float enterDuration = GENERATING_GRADIENT_BG_ENTER_END_TIMESTAMP - GENERATING_GRADIENT_BG_ENTER_START_TIMESTAMP;
                alpha = Math.min(0.15f, 0.15f * ((time - GENERATING_GRADIENT_BG_ENTER_START_TIMESTAMP) / enterDuration));
            }
            exitStartGradientBgAlpha = alpha;
        } else if (state == STATE_GENERATED) {
            if (time < GENERATING_GRADIENT_BG_EXIT_START_TIMESTAMP) {
                alpha = exitStartGradientBgAlpha;
            } else if (time > GENERATING_GRADIENT_BG_EXIT_END_TIMESTAMP) {
                alpha = exitStartGradientBgAlpha = 0f;
            } else {
                float exitDuration = GENERATING_GRADIENT_BG_EXIT_END_TIMESTAMP - GENERATING_GRADIENT_BG_EXIT_START_TIMESTAMP;
                alpha = Math.max(0f, exitStartGradientBgAlpha * (1.0f - (time - GENERATING_GRADIENT_BG_EXIT_START_TIMESTAMP) / exitDuration));
            }
        }
        return alpha;
    }

    private float getGlowAlpha(int state, float time) {
        float alpha = 0f;
        if (state == STATE_GENERATING) {
            if (time < GENERATING_GLOW_ENTER_START_TIMESTAMP) {
                alpha = 0f;
            } else if (time > GENERATING_GLOW_ENTER_END_TIMESTAMP) {
                alpha = 0.3f;
            } else {
                float enterDuration = GENERATING_GLOW_ENTER_END_TIMESTAMP - GENERATING_GLOW_ENTER_START_TIMESTAMP;
                alpha = Math.min(0.3f, 0.3f * ((time - GENERATING_GLOW_ENTER_START_TIMESTAMP) / enterDuration));
            }
            exitStartGlowAlpha = alpha;
        } else if (state == STATE_GENERATED) {
            if (time < GENERATING_GLOW_EXIT_START_TIMESTAMP) {
                alpha = exitStartGlowAlpha;
            } else if (time > GENERATING_GLOW_EXIT_END_TIMESTAMP) {
                alpha = exitStartGlowAlpha = 0f;
            } else {
                float exitDuration = GENERATING_GLOW_EXIT_END_TIMESTAMP - GENERATING_GLOW_EXIT_START_TIMESTAMP;
                alpha = Math.max(0f, exitStartGlowAlpha * (1.0f - (time - GENERATING_GLOW_EXIT_START_TIMESTAMP) / exitDuration));
            }
        }
        return alpha;
    }

    /**
     * 根据时间进度(0~1)计算三阶贝塞尔曲线的 Y 值
     * 参数对应 CSS 的 cubic-bezier(x1, y1, x2, y2)
     */
    private float getCubicBezierY(float time, float x1, float y1, float x2, float y2) {
        if (time <= 0f) return 0f;
        if (time >= 1f) return 1f;

        float lower = 0f;
        float upper = 1f;
        float t = time;
        // 12次迭代精度达到 1/4096，对于60fps/120fps动画足够平滑
        for (int i = 0; i < 12; i++) {
            float currentX = evaluateCubic(x1, x2, t);
            if (Math.abs(time - currentX) < 0.001f) break;
            if (time > currentX) {
                lower = t;
            } else {
                upper = t;
            }
            t = (upper + lower) / 2f;
        }
        return evaluateCubic(y1, y2, t);
    }

    private float evaluateCubic(float p1, float p2, float t) {
        float mt = 1f - t;
        return 3f * mt * mt * t * p1 + 3f * mt * t * t * p2 + t * t * t;
    }

    public void performChangeState(@State int state) {
        this.stateTemp = state;
    }

    private float onStateChanged() {
        switch (stateTemp) {
            case STATE_IDEL:
            case STATE_GENERATING:
            case STATE_GENERATED:
                if (stateTemp != state) {
                    startTime = SystemClock.elapsedRealtime();
                }
                break;
            default:
                break;
        }
        this.state = stateTemp;
        return (SystemClock.elapsedRealtime() - startTime) / 1000.0f;
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
            GLES20.glDeleteFramebuffers(3, fboIds, 0);
            for (int i = 0; i < 3; i++) fboIds[i] = 0;
        }
        if (fboTextureIds[0] != 0) {
            GLES20.glDeleteTextures(3, fboTextureIds, 0);
            for (int i = 0; i < 3; i++) fboTextureIds[i] = 0;
        }
    }

    private String loadShaderFast(String fileName) {
        InputStream is = null;
        ByteArrayOutputStream os = null;
        try {
            is = ApplicationContext.getContext().getAssets().open(fileName);
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
        if (generatingSweepProgram != 0) GLES20.glDeleteProgram(generatingSweepProgram);
        if (generatingGradientBgProgram != 0) GLES20.glDeleteProgram(generatingGradientBgProgram);
        if (generatingGlowProgram != 0) GLES20.glDeleteProgram(generatingGlowProgram);
        if (mGeneratingBitmapTextureId != -1) GLES20.glDeleteTextures(1, new int[]{mGeneratingBitmapTextureId}, 0);
        if (finalProgram != 0) GLES20.glDeleteProgram(finalProgram);
    }
}