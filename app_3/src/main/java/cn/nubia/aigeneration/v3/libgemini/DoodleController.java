package cn.nubia.aigeneration.v3.libgemini;

import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import cn.nubia.aigeneration.v3.ApplicationContext;

public class DoodleController {
    private static final String TAG = "DoodleController";
    // ========== 动画控制标志 ==========
    //控制是否松手后尾巴追逐头部
    private static final boolean ENABLE_CATCHUP_ON_RELEASE = true;
    //控制是否在静止不动时尾巴追逐头部
    private static final boolean ENABLE_CATCH_UP_ON_STILL = false;

    private final GradientRenderer.RenderCallback renderCallback;
    private int viewWidth, viewHeight;

    // ========== Doodle 光效 Shader ==========
    private int doodleProgram;
    private int aPositionLocation;
    private int aUvLocation;
    private int uMVPMatrixLocation;
    private int uTotalLengthLocation;
    private int uStrokeWidthLocation;
    private int uTailLengthLocation;

    // ========== 基础路径 Shader (50% 白色) ==========
    private int pathProgram;
    private int pathPositionLoc;
    private int pathUvLoc;
    private int pathMvpLoc;
    private int pathTotalLengthLoc;
    private int pathStrokeWidthLoc;

    // ========== Composite Shader ==========
    private int compositeProgram;
    private int compositePositionLoc;
    private int compositeTexCoordLoc;
    private int compositeMvpLoc;
    private int compositeTextureLoc;
    private int compositeSweepTextureLoc;
    private int compositeSweepAlphaLoc;

    // ========== Sweep Light Shader ==========
    private int sweepProgram;
    private int sweepPositionLoc;
    private int sweepTexCoordLoc;
    private int sweepMvpLoc;
    private int sweepTimeLoc;
    private int sweepDurationLoc;
    private int sweepResLoc;

    // ========== 离屏 FBO ==========
    private int strokeFboId = -1;
    private int strokeFboTextureId = -1;
    private int strokeFboWidth, strokeFboHeight;

    // ========== Sweep FBO ==========
    private int sweepFboId = -1;
    private int sweepFboTextureId = -1;

    private FloatBuffer quadVertexBuffer;
    private FloatBuffer quadTexCoordBuffer;
    private FloatBuffer sweepQuadTexCoordBuffer; // Y 翻转，匹配 GradientRenderer_e2 的 UV 约定

    // ========== Sweep 动画状态 ==========
    // 与 GradientRenderer_e2 保持一致:
    // GENERATING_SWEEP_START_TIMESTAMP = 0f, GENERATING_SWEEP_END_TIMESTAMP = 0.6f
    private static final float SWEEP_DURATION = 0.6f; // 秒
    private boolean isSweepAnimating = false;
    private long sweepStartTime = 0;

    // ========== 笔画数据 ==========
    private List<Point> currentStroke = new ArrayList<>();
    private List<CompletedStroke> completedStrokes = new CopyOnWriteArrayList<>();
    private boolean isDrawing = false;
    private boolean isCatchingUp = false;
    private float currentTotalLength = 0f;

    // ========== 尾巴追赶动画逻辑 ==========
    private float currentTailPos = 0f;
    private long lastRenderTime = 0;
    private long lastMoveTime = 0;
    private static final long STILL_TIMEOUT_MS = 20;
    private static final float MAX_TAIL_LENGTH = 0.6f;
    private static final float TAIL_CATCH_UP_SPEED = 2.0f;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable renderRunnable = new Runnable() {
        @Override
        public void run() {
            if (isCatchingUp) {
                renderCallback.requestRender();
                handler.postDelayed(this, 16);
            }
        }
    };

    // Sweep 动画渲染回调
    private final Runnable sweepRenderRunnable = new Runnable() {
        @Override
        public void run() {
            if (isSweepAnimating) {
                float elapsed = (SystemClock.elapsedRealtime() - sweepStartTime) / 1000f;
                if (elapsed > SWEEP_DURATION) {
                    isSweepAnimating = false;
                    renderCallback.requestRender(); // 最后一帧清理
                } else {
                    renderCallback.requestRender();
                    handler.postDelayed(this, 16);
                }
            }
        }
    };

    private float[] invertedMatrix = new float[16];
    private float[] normalizedPoint = new float[4];
    private float[] mappedPoint = new float[4];

    private static final float STROKE_WIDTH = 0.1f;
    private static final int GL_MAX = 0x8008;

    // ========== Shader 源码 ==========
    private static final String COMPOSITE_VERTEX_SRC =
            "uniform mat4 uMVPMatrix;\n" +
                    "attribute vec2 a_position;\n" +
                    "attribute vec2 a_texCoord;\n" +
                    "varying vec2 v_texCoord;\n" +
                    "void main() {\n" +
                    "    gl_Position = uMVPMatrix * vec4(a_position, 0.0, 1.0);\n" +
                    "    v_texCoord = a_texCoord;\n" +
                    "}\n";

    // 修改: 加入 sweep screen blend，用 stroke alpha 作为遮罩
    // 与 final_blend_fragment_shader_e2 中 drawGenerating 对 sweep 的处理一致:
    // refinedFilter + 1.05 亮度 + blendScreen
    private static final String COMPOSITE_FRAGMENT_SRC =
            "precision highp float;\n" +
                    "varying vec2 v_texCoord;\n" +
                    "uniform sampler2D u_texture;\n" +
                    "uniform sampler2D u_sweepTexture;\n" +
                    "uniform float u_sweepAlpha;\n" +
                    "\n" +
                    "vec3 blendScreen(vec3 base, vec3 blend) {\n" +
                    "    return 1.0 - (1.0 - base) * (1.0 - blend);\n" +
                    "}\n" +
                    "\n" +
                    "void main() {\n" +
                    "    vec4 strokeColor = texture2D(u_texture, v_texCoord);\n" +
                    "    if (u_sweepAlpha > 0.0 && strokeColor.a > 0.01) {\n" +
                    "        vec4 sweepColor = texture2D(u_sweepTexture, v_texCoord);\n" +
                    "        vec3 refinedFilter = vec3(0.95, 0.90, 1.10);\n" +
                    "        vec3 deepBlueSweep = sweepColor.rgb * refinedFilter;\n" +
                    "        deepBlueSweep = clamp(deepBlueSweep, 0.0, 1.0) * 1.05;\n" +
                    "        vec3 straightRGB = strokeColor.rgb;\n" +
                    "        vec3 blended = blendScreen(straightRGB, deepBlueSweep);\n" +
                    "        gl_FragColor = vec4(blended * 0.8, strokeColor.a);\n" +
                    "    } else {\n" +
                    "        gl_FragColor = strokeColor;\n" +
                    "    }\n" +
                    "}\n";

    private static final String PATH_VERTEX_SRC =
            "uniform mat4 uMVPMatrix;\n" +
                    "attribute vec2 a_position;\n" +
                    "attribute vec2 a_uv;\n" +
                    "varying vec2 v_uv;\n" +
                    "void main() {\n" +
                    "    gl_Position = uMVPMatrix * vec4(a_position, 0.0, 1.0);\n" +
                    "    v_uv = a_uv;\n" +
                    "}\n";

    private static final String PATH_FRAGMENT_SRC =
            "precision highp float;\n" +
                    "varying vec2 v_uv;\n" +
                    "uniform float u_total_length;\n" +
                    "uniform float u_stroke_width;\n" +
                    "void main() {\n" +
                    "    float dx = 0.0;\n" +
                    "    // 计算首尾的圆角距离\n" +
                    "    if (v_uv.x < u_stroke_width) {\n" +
                    "        dx = (u_stroke_width - v_uv.x) / u_stroke_width;\n" +
                    "    } else if (v_uv.x > u_total_length - u_stroke_width) {\n" +
                    "        dx = (v_uv.x - (u_total_length - u_stroke_width)) / u_stroke_width;\n" +
                    "    }\n" +
                    "    float dist_to_center = sqrt(dx * dx + v_uv.y * v_uv.y);\n" +
                    "    \n" +
                    "    // 使用与 doodle_fragment_shader 一致的 wide_shape (sigma=0.35)\n" +
                    "    // gaussian(x, 0.35) = exp(-(x*x) / (2 * 0.35 * 0.35)) ≈ exp(-4.0816 * x^2)\n" +
                    "    float shape = exp(-4.0816 * dist_to_center * dist_to_center);\n" +
                    "    \n" +
                    "    // 提取核心区域并赋予 50% 透明度\n" +
                    "    float alpha = smoothstep(0.2, 0.5, shape) * 0.5;\n" +
                    "    gl_FragColor = vec4(alpha, alpha, alpha, alpha);\n" +
                    "}\n";

    private static class Point {
        float x, y, lengthFromStart;
        Point(float x, float y, float lengthFromStart) {
            this.x = x;
            this.y = y;
            this.lengthFromStart = lengthFromStart;
        }
    }

    private static class CompletedStroke {
        List<Point> points;
        FloatBuffer vertexBuffer;
        float aspect;
        int numVertices;
        float totalLength;
    }

    public DoodleController(GradientRenderer.RenderCallback renderCallback) {
        this.renderCallback = renderCallback;
    }

    public void onSurfaceCreated() {
        String vertexSrc = loadShaderFast("doodle_vertex_shader.glsl");
        String fragSrc = loadShaderFast("doodle_fragment_shader.glsl");
        doodleProgram = createGLProgram(vertexSrc, fragSrc);
        aPositionLocation  = GLES20.glGetAttribLocation(doodleProgram, "a_position");
        aUvLocation        = GLES20.glGetAttribLocation(doodleProgram, "a_uv");
        uMVPMatrixLocation = GLES20.glGetUniformLocation(doodleProgram, "uMVPMatrix");
        uTotalLengthLocation = GLES20.glGetUniformLocation(doodleProgram, "u_total_length");
        uStrokeWidthLocation = GLES20.glGetUniformLocation(doodleProgram, "u_stroke_width");
        uTailLengthLocation = GLES20.glGetUniformLocation(doodleProgram, "u_tail_length");

        pathProgram = createGLProgram(PATH_VERTEX_SRC, PATH_FRAGMENT_SRC);
        pathPositionLoc = GLES20.glGetAttribLocation(pathProgram, "a_position");
        pathUvLoc = GLES20.glGetAttribLocation(pathProgram, "a_uv");
        pathMvpLoc = GLES20.glGetUniformLocation(pathProgram, "uMVPMatrix");
        pathTotalLengthLoc = GLES20.glGetUniformLocation(pathProgram, "u_total_length");
        pathStrokeWidthLoc = GLES20.glGetUniformLocation(pathProgram, "u_stroke_width");

        compositeProgram     = createGLProgram(COMPOSITE_VERTEX_SRC, COMPOSITE_FRAGMENT_SRC);
        compositePositionLoc = GLES20.glGetAttribLocation(compositeProgram, "a_position");
        compositeTexCoordLoc = GLES20.glGetAttribLocation(compositeProgram, "a_texCoord");
        compositeMvpLoc      = GLES20.glGetUniformLocation(compositeProgram, "uMVPMatrix");
        compositeTextureLoc  = GLES20.glGetUniformLocation(compositeProgram, "u_texture");
        compositeSweepTextureLoc = GLES20.glGetUniformLocation(compositeProgram, "u_sweepTexture");
        compositeSweepAlphaLoc   = GLES20.glGetUniformLocation(compositeProgram, "u_sweepAlpha");

        // 初始化 Sweep Shader（使用 sweep_light_fragment_shader.glsl）
        String sweepFragSrc = loadShaderFast("sweep_light_fragment_shader.glsl");
        sweepProgram = createGLProgram(COMPOSITE_VERTEX_SRC, sweepFragSrc);
        sweepPositionLoc  = GLES20.glGetAttribLocation(sweepProgram, "a_position");
        sweepTexCoordLoc  = GLES20.glGetAttribLocation(sweepProgram, "a_texCoord");
        sweepMvpLoc       = GLES20.glGetUniformLocation(sweepProgram, "uMVPMatrix");
        sweepTimeLoc      = GLES20.glGetUniformLocation(sweepProgram, "u_time");
        sweepDurationLoc  = GLES20.glGetUniformLocation(sweepProgram, "u_duration");
        sweepResLoc       = GLES20.glGetUniformLocation(sweepProgram, "u_resolution");

        float[] quadVerts = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
        float[] quadTexs  = { 0f,  0f, 1f,  0f,  0f, 1f, 1f, 1f};
        // 与 GradientRenderer_e2 一致的 Y 翻转 texCoord (y=0 在屏幕顶部)
        float[] sweepTexs = { 0f,  0f, 1f,  0f,  0f, 1f, 1f, 1f};

        quadVertexBuffer = ByteBuffer.allocateDirect(quadVerts.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(quadVerts);
        quadVertexBuffer.position(0);

        quadTexCoordBuffer = ByteBuffer.allocateDirect(quadTexs.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(quadTexs);
        quadTexCoordBuffer.position(0);

        sweepQuadTexCoordBuffer = ByteBuffer.allocateDirect(sweepTexs.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(sweepTexs);
        sweepQuadTexCoordBuffer.position(0);
    }

    public void onSurfaceChanged(int width, int height) {
        this.viewWidth = width;
        this.viewHeight = height;
        createStrokeFBO(width, height);
        createSweepFBO(width, height);
    }

    public void onDestroy() {
        handler.removeCallbacks(renderRunnable);
        handler.removeCallbacks(sweepRenderRunnable);
        destroyStrokeFBO();
        destroySweepFBO();
        if (doodleProgram != 0) GLES20.glDeleteProgram(doodleProgram);
        if (pathProgram != 0) GLES20.glDeleteProgram(pathProgram);
        if (compositeProgram != 0) GLES20.glDeleteProgram(compositeProgram);
        if (sweepProgram != 0) GLES20.glDeleteProgram(sweepProgram);
    }

    private void createStrokeFBO(int width, int height) {
        destroyStrokeFBO();
        strokeFboWidth = width;
        strokeFboHeight = height;

        int[] texIds = new int[1];
        GLES20.glGenTextures(1, texIds, 0);
        strokeFboTextureId = texIds[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, strokeFboTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

        int[] fboIds = new int[1];
        GLES20.glGenFramebuffers(1, fboIds, 0);
        strokeFboId = fboIds[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, strokeFboId);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, strokeFboTextureId, 0);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void destroyStrokeFBO() {
        if (strokeFboId != -1) GLES20.glDeleteFramebuffers(1, new int[]{strokeFboId}, 0);
        if (strokeFboTextureId != -1) GLES20.glDeleteTextures(1, new int[]{strokeFboTextureId}, 0);
        strokeFboId = -1;
        strokeFboTextureId = -1;
    }

    private void createSweepFBO(int width, int height) {
        destroySweepFBO();
        int[] texIds = new int[1];
        GLES20.glGenTextures(1, texIds, 0);
        sweepFboTextureId = texIds[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sweepFboTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

        int[] fboIds = new int[1];
        GLES20.glGenFramebuffers(1, fboIds, 0);
        sweepFboId = fboIds[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sweepFboId);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, sweepFboTextureId, 0);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void destroySweepFBO() {
        if (sweepFboId != -1) GLES20.glDeleteFramebuffers(1, new int[]{sweepFboId}, 0);
        if (sweepFboTextureId != -1) GLES20.glDeleteTextures(1, new int[]{sweepFboTextureId}, 0);
        sweepFboId = -1;
        sweepFboTextureId = -1;
    }

    /**
     * 启动 Sweep 扫光动画，与 GradientRenderer_e2 保持一致:
     * - 持续 0.6 秒 (GENERATING_SWEEP_END_TIMESTAMP - GENERATING_SWEEP_START_TIMESTAMP)
     * - alpha 从 1.0 线性衰减到 0.0 (getSweepAlpha 逻辑)
     * - sweep shader 内部也有 globalAlpha 和 easeOutQuart 缓出运动
     */
    private void startSweepAnimation() {
        isSweepAnimating = true;
        sweepStartTime = SystemClock.elapsedRealtime();
        handler.removeCallbacks(sweepRenderRunnable);
        handler.post(sweepRenderRunnable);
    }

    private float getModelAspect(float[] mvpMatrix) {
        if (viewWidth == 0 || viewHeight == 0) return 1.0f;
        float scaleX = Math.abs(mvpMatrix[0]) * viewWidth;
        float scaleY = Math.abs(mvpMatrix[5]) * viewHeight;
        if (scaleX == 0 || scaleY == 0) return 1.0f;
        return scaleY / scaleX;
    }

    public boolean onTouchEvent(MotionEvent event, float[] mvpMatrix) {
        if (event.getPointerCount() > 1) {
            cancelCurrentStroke();
            return false;
        }

        float x = event.getX();
        float y = event.getY();

        float ndcX = (x / viewWidth) * 2.0f - 1.0f;
        float ndcY = -((y / viewHeight) * 2.0f - 1.0f);

        Matrix.invertM(invertedMatrix, 0, mvpMatrix, 0);
        normalizedPoint[0] = ndcX;
        normalizedPoint[1] = ndcY;
        normalizedPoint[2] = 0.0f;
        normalizedPoint[3] = 1.0f;
        Matrix.multiplyMV(mappedPoint, 0, invertedMatrix, 0, normalizedPoint, 0);

        float modelX = mappedPoint[0] / mappedPoint[3];
        float modelY = mappedPoint[1] / mappedPoint[3];

        if (modelX < -1.0f || modelX > 1.0f || modelY < -1.0f || modelY > 1.0f) {
            if (isDrawing) cancelCurrentStroke();
            return false;
        }

        float aspect = getModelAspect(mvpMatrix);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                isDrawing = true;
                isCatchingUp = false;
                handler.removeCallbacks(renderRunnable);

                // 新笔画开始时停止上一次的 sweep 动画
                isSweepAnimating = false;
                handler.removeCallbacks(sweepRenderRunnable);

                lastMoveTime = SystemClock.elapsedRealtime();
                lastRenderTime = lastMoveTime;

                synchronized (currentStroke) {
                    if (currentStroke.size() > 1) {
                        CompletedStroke cs = new CompletedStroke();
                        cs.points = new ArrayList<>(currentStroke);
                        cs.aspect = -1f;
                        cs.totalLength = currentTotalLength;
                        completedStrokes.add(cs);
                    }
                    currentStroke.clear();
                    currentTotalLength = 0f;
                    currentTailPos = 0f;
                    currentStroke.add(new Point(modelX, modelY, 0f));
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (isDrawing) {
                    synchronized (currentStroke) {
                        Point lastPoint = currentStroke.get(currentStroke.size() - 1);
                        float dx = modelX - lastPoint.x;
                        float dy = modelY - lastPoint.y;

                        float uniformDx = dx;
                        float uniformDy = dy * aspect;
                        float dist = (float) Math.hypot(uniformDx, uniformDy);

                        if (dist > 0.01f) {
                            currentTotalLength += dist;
                            currentStroke.add(new Point(modelX, modelY, currentTotalLength));
                            lastMoveTime = SystemClock.elapsedRealtime();
                            renderCallback.requestRender();
                        }
                    }
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (isDrawing) {
                    if (ENABLE_CATCHUP_ON_RELEASE) {
                        isDrawing = false;
                        isCatchingUp = true;
                        lastRenderTime = SystemClock.elapsedRealtime();
                        handler.post(renderRunnable);
                    } else {
                        cancelCurrentStroke();
                        startSweepAnimation(); // 如果不追赶，则立即触发
                    }
                }
                break;
        }
        return true;
    }

    /**
     * 使用 Catmull-Rom 算法对触控轨迹进行曲线平滑插值
     * 并根据当前屏幕视口比例重新精准计算顶点的累加物理长度
     */
    private List<Point> smoothPoints(List<Point> rawPoints, float aspect) {
        if (rawPoints == null || rawPoints.size() < 2) {
            return rawPoints;
        }

        List<Point> smoothed = new ArrayList<>();
        int numPoints = rawPoints.size();

        // 局部临时类，用于存放纯坐标，避免高频创建带有复杂业务属性的 Point 对象
        class TempCoord {
            float x, y;
            TempCoord(float x, float y) { this.x = x; this.y = y; }
        }
        List<TempCoord> tempCoords = new ArrayList<>();

        // 遍历所有原始线段进行插值
        for (int i = 0; i < numPoints - 1; i++) {
            Point p1 = rawPoints.get(i);
            Point p2 = rawPoints.get(i + 1);

            // 边界控制点处理
            Point p0 = (i == 0) ? p1 : rawPoints.get(i - 1);
            Point p3 = (i == numPoints - 2) ? p2 : rawPoints.get(i + 2);

            // 计算当前线段在屏幕上的等效物理距离，动态决定插值点的密度
            float dx = p2.x - p1.x;
            float dy = (p2.y - p1.y) * aspect;
            float dist = (float) Math.hypot(dx, dy);

            // 阈值调优：每段长约 0.015 采样一个点。
            // 数值越小越丝滑但顶点越多；数值越大越接近直角。0.015f 是性能与视觉平衡的黄金点。
            int steps = Math.max(1, (int) (dist / 0.015f));

            for (int j = 0; j < steps; j++) {
                float t = j / (float) steps;
                float t2 = t * t;
                float t3 = t2 * t;

                // 标准 Catmull-Rom 曲线矩阵公式
                float x = 0.5f * ((2f * p1.x) +
                        (-p0.x + p2.x) * t +
                        (2f * p0.x - 5f * p1.x + 4f * p2.x - p3.x) * t2 +
                        (-p0.x + 3f * p1.x - 3f * p2.x + p3.x) * t3);

                float y = 0.5f * ((2f * p1.y) +
                        (-p0.y + p2.y) * t +
                        (2f * p0.y - 5f * p1.y + 4f * p2.y - p3.y) * t2 +
                        (-p0.y + 3f * p1.y - 3f * p2.y + p3.y) * t3);

                tempCoords.add(new TempCoord(x, y));
            }
        }

        // 补上最后一个原始终点
        Point lastRaw = rawPoints.get(numPoints - 1);
        tempCoords.add(new TempCoord(lastRaw.x, lastRaw.y));

        // 第二阶段：基于弯曲后的全新曲线，重新从 0 累加精准的 lengthFromStart
        float accumulatedLength = 0f;
        smoothed.add(new Point(tempCoords.get(0).x, tempCoords.get(0).y, 0f));

        for (int i = 1; i < tempCoords.size(); i++) {
            TempCoord prev = tempCoords.get(i - 1);
            TempCoord curr = tempCoords.get(i);

            float dx = curr.x - prev.x;
            float dy = (curr.y - prev.y) * aspect;
            accumulatedLength += (float) Math.hypot(dx, dy);

            smoothed.add(new Point(curr.x, curr.y, accumulatedLength));
        }

        return smoothed;
    }

    public void cancelCurrentStroke() {
        handler.removeCallbacks(renderRunnable);
        // 外部取消时也停止 sweep
        handler.removeCallbacks(sweepRenderRunnable);
        isSweepAnimating = false;

        synchronized (currentStroke) {
            if (currentStroke.size() > 1) {
                CompletedStroke cs = new CompletedStroke();
                cs.points = new ArrayList<>(currentStroke);
                cs.aspect = -1f;
                cs.totalLength = currentTotalLength;
                completedStrokes.add(cs);
            }
            isDrawing = false;
            isCatchingUp = false;
            currentStroke.clear();
            currentTotalLength = 0f;
        }
        renderCallback.requestRender();
    }

    /**
     * 尾巴追赶完成时调用，不停止 sweep 动画。
     * 与 cancelCurrentStroke 的区别在于：尾巴自然追完是松手流程的一部分，
     * sweep 应继续播放到 0.6s 结束。
     */
    private void finishCurrentStroke() {
        handler.removeCallbacks(renderRunnable);
        synchronized (currentStroke) {
            if (currentStroke.size() > 1) {
                CompletedStroke cs = new CompletedStroke();
                cs.points = new ArrayList<>(currentStroke);
                cs.aspect = -1f;
                cs.totalLength = currentTotalLength;
                completedStrokes.add(cs);
            }
            isDrawing = false;
            isCatchingUp = false;
            currentStroke.clear();
            currentTotalLength = 0f;
        }
        renderCallback.requestRender();
    }

    public void drawToFBO() {}
    public void drawFBOToScreen(float[] mvpMatrix) {}

    public void drawCurrentStrokeEffect(float[] mvpMatrix) {
        if (strokeFboId == -1) return;

        // 提前计算视口长宽比
        float aspect = getModelAspect(mvpMatrix);

        List<Point> renderPoints = null;
        float renderTotalLength = 0f;
        synchronized (currentStroke) {
            if (currentStroke.size() >= 2) {
                renderPoints = new ArrayList<>(currentStroke);
            }
        }

        // ==================================================
        // 核心修改 1：对当前正在绘制/追赶的笔画进行曲线平滑
        // ==================================================
        if (renderPoints != null) {
            renderPoints = smoothPoints(renderPoints, aspect);
            if (renderPoints.size() >= 2) {
                // 用平滑后更精准的曲线总长度更新渲染总长度
                renderTotalLength = renderPoints.get(renderPoints.size() - 1).lengthFromStart;
            } else {
                renderPoints = null;
            }
        }

        // ================= 计算尾巴追赶逻辑 =================
        long now = SystemClock.elapsedRealtime();
        if (lastRenderTime == 0) lastRenderTime = now;
        float dt = (now - lastRenderTime) / 1000f;
        lastRenderTime = now;

        float activeTailLength = 0.001f;
        if (renderPoints != null) {
            if (isDrawing) {
                float minTailPos = Math.max(0f, renderTotalLength - MAX_TAIL_LENGTH);
                currentTailPos = Math.max(currentTailPos, minTailPos);

                if (ENABLE_CATCH_UP_ON_STILL && (now - lastMoveTime > STILL_TIMEOUT_MS)) {
                    float maxStillTailPos = Math.max(0f, renderTotalLength - STROKE_WIDTH * 2.0f);

                    if (currentTailPos < maxStillTailPos) {
                        currentTailPos += TAIL_CATCH_UP_SPEED * dt;
                        if (currentTailPos > maxStillTailPos) {
                            currentTailPos = maxStillTailPos;
                        } else {
                            handler.post(() -> renderCallback.requestRender());
                        }
                    }
                }
            } else if (isCatchingUp) {
                currentTailPos += TAIL_CATCH_UP_SPEED * dt;
                if (currentTailPos >= renderTotalLength) {
                    currentTailPos = renderTotalLength;
                    isCatchingUp = false;
                    handler.post(() -> {
                        finishCurrentStroke();
                        startSweepAnimation();
                    });
                }
            }
            activeTailLength = Math.max(0.001f, renderTotalLength - currentTailPos);
        }
        // ==================================================

        // ================= 渲染笔画到 Stroke FBO =================
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, strokeFboId);
        GLES20.glViewport(0, 0, strokeFboWidth, strokeFboHeight);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendEquation(GL_MAX);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE);

        // ================= 绘制基础路径 =================
        GLES20.glUseProgram(pathProgram);
        GLES20.glUniformMatrix4fv(pathMvpLoc, 1, false, mvpMatrix, 0);
        GLES20.glUniform1f(pathStrokeWidthLoc, STROKE_WIDTH);

        // ==================================================
        // 核心修改 2：对已完成的历史笔画在更新顶点时也进行平滑
        // ==================================================
        for (CompletedStroke cs : completedStrokes) {
            if (cs.vertexBuffer == null || Math.abs(cs.aspect - aspect) > 0.01f) {
                List<Point> smoothedHistory = smoothPoints(cs.points, aspect);
                if (smoothedHistory.size() >= 2) {
                    cs.totalLength = smoothedHistory.get(smoothedHistory.size() - 1).lengthFromStart;
                    cs.vertexBuffer = generateVertexBuffer(smoothedHistory, aspect);
                    cs.numVertices = smoothedHistory.size() * 2;
                } else {
                    cs.numVertices = 0;
                }
                cs.aspect = aspect;
            }
            if (cs.numVertices > 0) {
                GLES20.glUniform1f(pathTotalLengthLoc, cs.totalLength);
                drawBuffer(cs.vertexBuffer, cs.numVertices, pathPositionLoc, pathUvLoc);
            }
        }

        FloatBuffer currentBuffer = null;
        int currentNumVertices = 0;
        if (renderPoints != null) {
            currentBuffer = generateVertexBuffer(renderPoints, aspect);
            currentNumVertices = renderPoints.size() * 2;
            GLES20.glUniform1f(pathTotalLengthLoc, renderTotalLength);
            drawBuffer(currentBuffer, currentNumVertices, pathPositionLoc, pathUvLoc);
        }

        // ================= 绘制光效尾巴 =================
        if (renderPoints != null && currentBuffer != null) {
            GLES20.glUseProgram(doodleProgram);
            GLES20.glUniformMatrix4fv(uMVPMatrixLocation, 1, false, mvpMatrix, 0);
            GLES20.glUniform1f(uTotalLengthLocation, renderTotalLength);
            GLES20.glUniform1f(uStrokeWidthLocation, STROKE_WIDTH);
            GLES20.glUniform1f(uTailLengthLocation, activeTailLength);

            drawBuffer(currentBuffer, currentNumVertices, aPositionLocation, aUvLocation);
        }

        GLES20.glBlendEquation(GLES20.GL_FUNC_ADD);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

        // ================= 渲染 Sweep 到 Sweep FBO =================
        float sweepAlpha = 0f;
        if (isSweepAnimating && sweepFboId != -1) {
            float sweepTime = (SystemClock.elapsedRealtime() - sweepStartTime) / 1000f;
            if (sweepTime <= SWEEP_DURATION) {
                sweepAlpha = Math.max(0f, 1.0f - sweepTime / SWEEP_DURATION);
                renderSweepToFBO(sweepTime);
            } else {
                isSweepAnimating = false;
            }
        }

        // ================= Composite 合成到屏幕 =================
        compositeToScreen(sweepAlpha);
    }

    /**
     * 将 sweep 扫光渲染到 Sweep FBO。
     * 与 GradientRenderer_e2 的 PASS 0 逻辑一致:
     * - u_time = 已过时间(秒)
     * - u_duration = 0.6(秒)
     * - u_resolution = 屏幕分辨率
     */
    private void renderSweepToFBO(float sweepTime) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, sweepFboId);
        GLES20.glViewport(0, 0, strokeFboWidth, strokeFboHeight);
        GLES20.glClearColor(0f, 0f, 0f, 0f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(sweepProgram);

        float[] identity = new float[16];
        Matrix.setIdentityM(identity, 0);
        GLES20.glUniformMatrix4fv(sweepMvpLoc, 1, false, identity, 0);

        GLES20.glUniform1f(sweepTimeLoc, sweepTime);
        GLES20.glUniform1f(sweepDurationLoc, SWEEP_DURATION);
        GLES20.glUniform2f(sweepResLoc, strokeFboWidth, strokeFboHeight);

        GLES20.glEnableVertexAttribArray(sweepPositionLoc);
        GLES20.glVertexAttribPointer(sweepPositionLoc, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);

        GLES20.glEnableVertexAttribArray(sweepTexCoordLoc);
        GLES20.glVertexAttribPointer(sweepTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, sweepQuadTexCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(sweepPositionLoc);
        GLES20.glDisableVertexAttribArray(sweepTexCoordLoc);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private FloatBuffer generateVertexBuffer(List<Point> points, float aspect) {
        int numPoints = points.size();
        int numVertices = numPoints * 2;
        float[] vertexData = new float[numVertices * 4];

        for (int i = 0; i < numPoints; i++) {
            Point current = points.get(i);
            float dx = 0, dy = 0;

            if (i == 0) {
                Point next = points.get(1);
                dx = next.x - current.x;
                dy = next.y - current.y;
            } else if (i == numPoints - 1) {
                Point prev = points.get(i - 1);
                dx = current.x - prev.x;
                dy = current.y - prev.y;
            } else {
                Point prev = points.get(i - 1);
                Point next = points.get(i + 1);
                dx = next.x - prev.x;
                dy = next.y - prev.y;
            }

            float uniformDx = dx;
            float uniformDy = dy * aspect;
            float len = (float) Math.hypot(uniformDx, uniformDy);
            if (len == 0) len = 1f;

            float nx = -uniformDy / len;
            float ny = uniformDx / len;

            float offsetX = nx * STROKE_WIDTH;
            float offsetY = (ny * STROKE_WIDTH) / aspect;

            int offset = i * 8;
            vertexData[offset]     = current.x + offsetX;
            vertexData[offset + 1] = current.y + offsetY;
            vertexData[offset + 2] = current.lengthFromStart;
            vertexData[offset + 3] = 1.0f;
            vertexData[offset + 4] = current.x - offsetX;
            vertexData[offset + 5] = current.y - offsetY;
            vertexData[offset + 6] = current.lengthFromStart;
            vertexData[offset + 7] = -1.0f;
        }

        return ByteBuffer.allocateDirect(vertexData.length * 4)
                .order(ByteOrder.nativeOrder()).asFloatBuffer().put(vertexData);
    }

    private void drawBuffer(FloatBuffer buffer, int numVertices, int posLoc, int uvLoc) {
        buffer.position(0);
        GLES20.glVertexAttribPointer(posLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, buffer);
        GLES20.glEnableVertexAttribArray(posLoc);

        buffer.position(2);
        GLES20.glVertexAttribPointer(uvLoc, 2, GLES20.GL_FLOAT, false, 4 * 4, buffer);
        GLES20.glEnableVertexAttribArray(uvLoc);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, numVertices);

        GLES20.glDisableVertexAttribArray(posLoc);
        GLES20.glDisableVertexAttribArray(uvLoc);
    }

    /**
     * 将 Stroke FBO + Sweep FBO 合成到屏幕。
     * Sweep 通过 screen blend 叠加，并以 Stroke FBO 的 alpha 作为遮罩,
     * 使扫光仅在涂鸦路径上可见。
     * 与 final_blend_fragment_shader_e2 中 drawGenerating 对 sweep 的处理方式一致。
     */
    private void compositeToScreen(float sweepAlpha) {
        GLES20.glViewport(0, 0, viewWidth, viewHeight);
        GLES20.glUseProgram(compositeProgram);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        float[] identity = new float[16];
        Matrix.setIdentityM(identity, 0);
        GLES20.glUniformMatrix4fv(compositeMvpLoc, 1, false, identity, 0);

        // 绑定 Stroke 纹理 (纹理单元 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, strokeFboTextureId);
        GLES20.glUniform1i(compositeTextureLoc, 0);

        // 绑定 Sweep 纹理 (纹理单元 1)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sweepFboTextureId != -1 ? sweepFboTextureId : 0);
        GLES20.glUniform1i(compositeSweepTextureLoc, 1);
        GLES20.glUniform1f(compositeSweepAlphaLoc, sweepAlpha);

        GLES20.glEnableVertexAttribArray(compositePositionLoc);
        GLES20.glVertexAttribPointer(compositePositionLoc, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);

        GLES20.glEnableVertexAttribArray(compositeTexCoordLoc);
        GLES20.glVertexAttribPointer(compositeTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, quadTexCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(compositePositionLoc);
        GLES20.glDisableVertexAttribArray(compositeTexCoordLoc);
        GLES20.glDisable(GLES20.GL_BLEND);
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
}