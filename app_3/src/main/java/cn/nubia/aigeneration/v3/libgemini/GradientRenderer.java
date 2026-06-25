package cn.nubia.aigeneration.v3.libgemini;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

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

import cn.nubia.aigeneration.v3.ApplicationContext;

public abstract class GradientRenderer implements GLSurfaceView.Renderer {
    private static final String TAG = "GradientRenderer";
    private static final int STATE_IDEL = 0;
    public static final int STATE_GENERATING = 1, STATE_GENERATED = 2;
    @Retention(RetentionPolicy.SOURCE) @IntDef({STATE_IDEL, STATE_GENERATING, STATE_GENERATED}) @interface State {}

    private volatile @State int state = STATE_IDEL, stateTemp = STATE_IDEL;

    private int finalProgram = 0;
    private final String vertexShaderSource;
    private final String finalShaderSource;

    private FloatBuffer vertexBuffer;
    private FloatBuffer texCoordBuffer;

    private int finalGeneratingBitmapTextureHandle;
    private int finalGeneratedBitmapTextureHandle;
    private int finalResHandle;
    private int stateHandle;
    private int mvpMatrixHandle;

    private float screenWidth = 1080f;
    private float screenHeight = 1920f;

    private long startTime;

    private Bitmap mGeneratingBitmap;
    private boolean mGeneratingBitmapChanged = false;
    private int mGeneratingBitmapTextureId = -1;

    private Bitmap mGeneratedBitmap;
    private boolean mGeneratedBitmapChanged = false;
    private int mGeneratedBitmapTextureId = -1;

    private int maxTextureSize = 4096;

    private final float[] vertices = {-1.0f, -1.0f, 1.0f, -1.0f, -1.0f, 1.0f, 1.0f, 1.0f};
    private final float[] texCoords = {0.0f, 1.0f, 1.0f, 1.0f, 0.0f, 0.0f, 1.0f, 0.0f};

    // 引入新的变换控制器
    private final TransformController transformController;
    // 新增涂鸦控制器
    private DoodleController doodleController;

    public GradientRenderer() {
        vertexBuffer = ByteBuffer.allocateDirect(vertices.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertices);
        vertexBuffer.position(0);
        texCoordBuffer = ByteBuffer.allocateDirect(texCoords.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(texCoords);
        texCoordBuffer.position(0);

        vertexShaderSource = loadShaderFast("vertex_shader.glsl");
        finalShaderSource = loadShaderFast("final_blend_fragment_shader.glsl");

        transformController = new TransformController(this::requestRender);
        doodleController = new DoodleController(this::requestRender);
    }

    public void setGeneratingBitmap(Bitmap bitmap) {
        synchronized (this) {
            this.mGeneratingBitmap = bitmap;
            this.mGeneratingBitmapChanged = true;
            if (bitmap != null) {
                transformController.setImageSize(bitmap.getWidth(), bitmap.getHeight());
            }
        }
        requestRender();
    }

    public void setGeneratedBitmap(Bitmap bitmap) {
        synchronized (this) {
            this.mGeneratedBitmap = bitmap;
            this.mGeneratedBitmapChanged = true;
            if (bitmap != null) {
                transformController.setImageSize(bitmap.getWidth(), bitmap.getHeight());
            }
        }
        requestRender();
    }

    public boolean onTouchEvent(MotionEvent event) {
        int pointerCount = event.getPointerCount();
        if (pointerCount == 1) {
            // 单指涂鸦
            boolean handled = doodleController.onTouchEvent(event, transformController.getMvpMatrix());
            if (handled) return true;
        } else {
            // 多指交给 TransformController
            doodleController.cancelCurrentStroke(); // 如果正在涂鸦，取消或结束当前笔画
            return transformController.onTouchEvent(event);
        }
        return false;
    }

    protected abstract void requestRender();

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        int[] maxTexSize = new int[1];
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxTexSize, 0);
        maxTextureSize = maxTexSize[0] > 0 ? maxTexSize[0] : 4096;

        finalProgram = createGLProgram(vertexShaderSource, finalShaderSource);
        finalGeneratingBitmapTextureHandle = GLES20.glGetUniformLocation(finalProgram, "u_generating_bitmapTexture");
        finalGeneratedBitmapTextureHandle = GLES20.glGetUniformLocation(finalProgram, "u_generated_bitmapTexture");
        finalResHandle = GLES20.glGetUniformLocation(finalProgram, "u_resolution");
        stateHandle = GLES20.glGetUniformLocation(finalProgram, "u_state");
        mvpMatrixHandle = GLES20.glGetUniformLocation(finalProgram, "uMVPMatrix");

        int[] textures = new int[2];
        GLES20.glGenTextures(2, textures, 0);
        mGeneratingBitmapTextureId = textures[0];
        mGeneratedBitmapTextureId = textures[1];

        synchronized (this) {
            if (mGeneratingBitmap != null) mGeneratingBitmapChanged = true;
            if (mGeneratedBitmap != null) mGeneratedBitmapChanged = true;
        }

        doodleController.onSurfaceCreated(); // 初始化 FBO 和 Shader
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int width, int height) {
        this.screenWidth = (float) width;
        this.screenHeight = (float) height;
        transformController.setScreenSize(width, height);
        doodleController.onSurfaceChanged(width, height);
        requestRender();
    }

    private void loadTextureSafe(Bitmap bitmap, int textureId) {
        if (bitmap == null || bitmap.isRecycled()) return;

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, textureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        int bw = bitmap.getWidth();
        int bh = bitmap.getHeight();

        if (bw > maxTextureSize || bh > maxTextureSize) {
            float scale = Math.min((float) maxTextureSize / bw, (float) maxTextureSize / bh);
            Matrix matrix = new Matrix();
            matrix.postScale(scale, scale);
            Bitmap scaledBitmap = Bitmap.createBitmap(bitmap, 0, 0, bw, bh, matrix, true);
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, scaledBitmap, 0);
            scaledBitmap.recycle();
        } else {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
        }
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (finalProgram == 0) return;

        // 1. 如果有新的笔画，先绘制到 Doodle FBO
        doodleController.drawToFBO();

        // 2. 绘制底层图片 (你现有的逻辑)
        synchronized (this) {
            if (mGeneratingBitmapChanged && mGeneratingBitmap != null) {
                loadTextureSafe(mGeneratingBitmap, mGeneratingBitmapTextureId);
                mGeneratingBitmapChanged = false;
            }

            if (mGeneratedBitmapChanged && mGeneratedBitmap != null && state == STATE_GENERATED) {
                loadTextureSafe(mGeneratedBitmap, mGeneratedBitmapTextureId);
                mGeneratedBitmapChanged = false;
            }
        }

        float keepSecond = onStateChanged();

        GLES20.glViewport(0, 0, (int) screenWidth, (int) screenHeight);
        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 0.0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

        GLES20.glUseProgram(finalProgram);

        // 直接从 Controller 获取最新的 MVP 矩阵
        float[] mvpMatrix = transformController.getMvpMatrix();
        GLES20.glUniformMatrix4fv(mvpMatrixHandle, 1, false, mvpMatrix, 0);

        GLES20.glUniform2f(finalResHandle, screenWidth, screenHeight);
        GLES20.glUniform1i(stateHandle, state);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mGeneratingBitmapTextureId);
        GLES20.glUniform1i(finalGeneratingBitmapTextureHandle, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, mGeneratedBitmapTextureId);
        GLES20.glUniform1i(finalGeneratedBitmapTextureHandle, 1);

        drawQuad(GLES20.glGetAttribLocation(finalProgram, "a_position"), GLES20.glGetAttribLocation(finalProgram, "a_texCoord"));

        // 3. 混合绘制 Doodle FBO 纹理到屏幕
        doodleController.drawFBOToScreen(transformController.getMvpMatrix());
        // 4. 绘制当前正在滑动的光效
        doodleController.drawCurrentStrokeEffect(transformController.getMvpMatrix());
    }

    public void performChangeState(@State int state) {
        this.stateTemp = state;
    }

    private float onStateChanged() {
        if (stateTemp != state) {
            startTime = SystemClock.elapsedRealtime();
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
        if (transformController != null) {
            transformController.onDestroy();
        }
        if (mGeneratingBitmapTextureId != -1) GLES20.glDeleteTextures(1, new int[]{mGeneratingBitmapTextureId}, 0);
        if (mGeneratedBitmapTextureId != -1) GLES20.glDeleteTextures(1, new int[]{mGeneratedBitmapTextureId}, 0);
        if (finalProgram != 0) GLES20.glDeleteProgram(finalProgram);
    }

    interface RenderCallback {
        void requestRender();
    }

}