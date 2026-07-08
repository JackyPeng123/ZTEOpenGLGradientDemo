package cn.nubia.aigeneration.v5.libgemini;

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

import cn.nubia.aigeneration.v5.ApplicationContext;

public class GradientRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "GradientRenderer";
    private static final int STATE_IDEL = 0;
    public static final int STATE_GENERATING = 1, STATE_GENERATED = 2;
    @Retention(RetentionPolicy.SOURCE) @IntDef({STATE_IDEL, STATE_GENERATING, STATE_GENERATED}) @interface State {}

    private static final float GENERATING_SWEEP_START_TIMESTAMP = 0f;
    private static final float GENERATING_SWEEP_END_TIMESTAMP = GENERATING_SWEEP_START_TIMESTAMP + 0.6f;
    private static final float GENERATING_GRADIENT_BG_ENTER_START_TIMESTAMP = 0.25f;
    private static final float GENERATING_GRADIENT_BG_ENTER_END_TIMESTAMP = GENERATING_GRADIENT_BG_ENTER_START_TIMESTAMP + 0.5f;

    private static final float GENERATING_GRADIENT_BG_EXIT_START_TIMESTAMP = 0f;
    private static final float GENERATING_GRADIENT_BG_EXIT_END_TIMESTAMP = GENERATING_GRADIENT_BG_EXIT_START_TIMESTAMP + 0.15f;

    private volatile @State int state = STATE_IDEL, stateTemp = STATE_IDEL;

    private int generatingSweepProgram = 0;
    private int generatingGradientBgProgram = 0;
    private int finalProgram = 0;

    private final String vertexShaderSource;
    private final String generatingSweepShaderSource;
    private final String generatingGradientBgShaderSource;
    private final String finalShaderSource;

    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    private int[] fboIds = new int[2];
    private int[] fboTextureIds = new int[2];

    private float[] generatingBitmapPixelOffset = new float[4];

    private int generatingSweepTimeHandle, generatingSweepDurationHandler, generatingSweepResHandle;
    private int generatingGradientBgTimeHandle, generatingGradientBgResHandle;
    private int finalGeneratingBitmapTextureHandle, finalGeneratingSweepTextureHandle, finalGeneratingSweepAlphaHandle, finalGeneratingGradientBgTextureHandle, finalGeneratingGradientBgAlphaHandle;
    private int finalResHandle, finalGeneratingBitmapRectHandle;

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
        finalShaderSource = loadShaderFast("final_blend_fragment_shader.glsl");
    }

    public void setGeneratingBitmap(Bitmap bitmap, float left, float top, float right, float bottom) {
        synchronized (this) {
            this.mGeneratingBitmap = bitmap;
            this.generatingBitmapPixelOffset[0] = left;
            this.generatingBitmapPixelOffset[1] = top;
            this.generatingBitmapPixelOffset[2] = right;
            this.generatingBitmapPixelOffset[3] = bottom;
            this.mGeneratingBitmapChanged = true;
            this.mSizeChanged = true; // 【修改】传入图片时触发重新计算区域
        }
    }

    public void setGeneratedBitmap(Bitmap bitmap) {
        synchronized (this) {
            this.mSizeChanged = true;
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

        finalProgram = createGLProgram(vertexShaderSource, finalShaderSource);
        finalGeneratingBitmapTextureHandle = GLES20.glGetUniformLocation(finalProgram, "u_generating_bitmapTexture");
        finalGeneratingSweepTextureHandle = GLES20.glGetUniformLocation(finalProgram, "u_generating_sweepTexture");
        finalGeneratingSweepAlphaHandle = GLES20.glGetUniformLocation(finalProgram, "u_generating_sweepAlpha");
        finalGeneratingGradientBgTextureHandle = GLES20.glGetUniformLocation(finalProgram, "u_generating_gradientBgTexture");
        finalGeneratingGradientBgAlphaHandle = GLES20.glGetUniformLocation(finalProgram, "u_generating_gradientBgAlpha");
        finalResHandle = GLES20.glGetUniformLocation(finalProgram, "u_resolution");
        finalGeneratingBitmapRectHandle = GLES20.glGetUniformLocation(finalProgram, "u_generating_bitmapRect");

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
        // 【修改】核心自适应逻辑：动态按 fitCenter 计算最终的 drawRect
        // ==================== 修改前的代码片段 ====================
/*
float containerRatio = screenWidth / screenHeight;
// ... (省略中间代码)
int newDrawX = Math.round((screenWidth - targetW) / 2f);
int newDrawY = Math.round((screenHeight - targetH) / 2f);
*/

// ==================== 修改后的代码 ====================
        synchronized (this) {
            if (mSizeChanged && mGeneratingBitmap != null && screenWidth > 0 && screenHeight > 0) {
                int bitmapW = mGeneratingBitmap.getWidth();
                int bitmapH = mGeneratingBitmap.getHeight();

                // 1. 获取传入的边距。兼容你之前传入 0.1f(百分比) 或者 100f(像素) 的情况
                float padLeft = generatingBitmapPixelOffset[0];
                float padTop = generatingBitmapPixelOffset[1];
                float padRight = generatingBitmapPixelOffset[2];
                float padBottom = generatingBitmapPixelOffset[3];

                // 如果传入的是小于 1 的浮点数，说明是百分比，换算成像素
                if (padLeft <= 1.0f && padRight <= 1.0f && (padLeft > 0 || padRight > 0)) {
                    padLeft *= screenWidth;
                    padRight *= screenWidth;
                    padTop *= screenHeight;
                    padBottom *= screenHeight;
                }

                // 2. 减去边距，计算实际可以在屏幕上绘制的"可用宽/高"
                float availableW = screenWidth - padLeft - padRight;
                float availableH = screenHeight - padTop - padBottom;

                if (availableW > 0 && availableH > 0) {
                    float targetW = availableW;
                    float targetH = availableH;

                    // 4. 核心：左上角对齐！
                    // X 起点就是左边距，Y 起点就是顶边距 (如果不要求左上角而是居中，这里就是 pad + (available - target)/2 )
                    int newDrawX = Math.round(padLeft);
                    int newDrawY = Math.round(padTop);
                    int newDrawW = Math.round(targetW);
                    int newDrawH = Math.round(targetH);

                    if (newDrawX != drawX || newDrawY != drawY || newDrawW != drawW || newDrawH != drawH) {
                        this.drawX = newDrawX;
                        this.drawY = newDrawY;
                        this.drawW = newDrawW;
                        this.drawH = newDrawH;

                        deleteFBOs();
                        if (screenWidth > 0 && screenHeight > 0) {
                            // 【修改1】FBO 从图片尺寸放大到全屏尺寸
                            initFBOs((int)screenWidth, (int)screenHeight);
                        }
                        if (mOnDrawRectChangedListener != null) {
                            // 【修改2】动效既然全铺满，那么圆角也应作用于整个容器边缘
                            mOnDrawRectChangedListener.onDrawRectChanged(0, 0, (int)screenWidth, (int)screenHeight);
                        }
                    }
                }
                mSizeChanged = false;
            }
        }

        if (generatingSweepProgram == 0 || generatingGradientBgProgram == 0 || finalProgram == 0 || drawW == 0 || drawH == 0 || fboIds[0] == 0) return;

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
        // PASS 0：渲染扫光 -> 视口匹配全屏容器
        // ==========================================
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[0]);
        GLES20.glViewport(0, 0, (int)screenWidth, (int)screenHeight);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(generatingSweepProgram);
        GLES20.glUniform1f(generatingSweepTimeHandle, keepSecond - GENERATING_SWEEP_START_TIMESTAMP);
        GLES20.glUniform1f(generatingSweepDurationHandler, GENERATING_SWEEP_END_TIMESTAMP - GENERATING_SWEEP_START_TIMESTAMP);
        GLES20.glUniform2f(generatingSweepResHandle, screenWidth, screenHeight); // 传入全屏尺寸
        drawQuad(GLES20.glGetAttribLocation(generatingSweepProgram, "a_position"), GLES20.glGetAttribLocation(generatingSweepProgram, "a_texCoord"));

        // ==========================================
        // PASS 1：渲染流体 -> 视口匹配全屏容器
        // ==========================================
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboIds[1]);
        GLES20.glViewport(0, 0, (int)screenWidth, (int)screenHeight);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(generatingGradientBgProgram);
        GLES20.glUniform1f(generatingGradientBgTimeHandle, keepSecond - GENERATING_GRADIENT_BG_ENTER_START_TIMESTAMP);
        GLES20.glUniform2f(generatingGradientBgResHandle, screenWidth, screenHeight); // 传入全屏尺寸
        drawQuad(GLES20.glGetAttribLocation(generatingGradientBgProgram, "a_position"), GLES20.glGetAttribLocation(generatingGradientBgProgram, "a_texCoord"));

        // ==========================================
        // PASS 3：最终合成 -> 全屏输出到屏幕
        // ==========================================
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        GLES20.glViewport(0, 0, (int) screenWidth, (int) screenHeight);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        GLES20.glUseProgram(finalProgram);
        GLES20.glUniform2f(finalResHandle, screenWidth, screenHeight);

        // 【新增】：将 Bitmap 所在的坐标转换为 0~1 的 UV 比例，传递给 Shader
        float minX = (float) drawX / screenWidth;
        float maxX = (float) (drawX + drawW) / screenWidth;
        float minY = (float) drawY / screenHeight;
        float maxY = (float) (drawY + drawH) / screenHeight;
        GLES20.glUniform4f(finalGeneratingBitmapRectHandle, minX, minY, maxX, maxY);

        // 绑定各个纹理
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

        drawQuad(GLES20.glGetAttribLocation(finalProgram, "a_position"), GLES20.glGetAttribLocation(finalProgram, "a_texCoord"));
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
                alpha = 0.25f - (0.25f - 0.1f) * pingpong;
            } else {
                float enterDuration = GENERATING_GRADIENT_BG_ENTER_END_TIMESTAMP - GENERATING_GRADIENT_BG_ENTER_START_TIMESTAMP;
                alpha = Math.min(0.25f, 0.25f * ((time - GENERATING_GRADIENT_BG_ENTER_START_TIMESTAMP) / enterDuration));
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
            GLES20.glDeleteFramebuffers(2, fboIds, 0);
            for (int i = 0; i < 2; i++) fboIds[i] = 0;
        }
        if (fboTextureIds[0] != 0) {
            GLES20.glDeleteTextures(2, fboTextureIds, 0);
            for (int i = 0; i < 2; i++) fboTextureIds[i] = 0;
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
        if (mGeneratingBitmapTextureId != -1) GLES20.glDeleteTextures(1, new int[]{mGeneratingBitmapTextureId}, 0);
        if (finalProgram != 0) GLES20.glDeleteProgram(finalProgram);
    }
}