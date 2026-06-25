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
    private static final boolean ENABLE_CATCHUP_ON_RELEASE = true;
    private static final boolean ENABLE_CATCH_UP_ON_STILL = true;

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

    // ========== 新增：纯色掩码 Shader ==========
    private int maskProgram;
    private int maskPositionLoc;
    private int maskUvLoc;
    private int maskMvpLoc;
    private int maskTotalLengthLoc;
    private int maskStrokeWidthLoc;

    // ========== 新增：全屏边缘描边合成 Shader ==========
    private int outlineProgram;
    private int outlinePositionLoc;
    private int outlineTexCoordLoc;
    private int outlineMvpLoc;
    private int outlineTextureLoc;
    private int outlineTexelSizeLoc;
    private int outlineColorLoc;

    // ========== 离屏 FBO ==========
    private int strokeFboId = -1;
    private int strokeFboTextureId = -1;
    private int strokeFboWidth, strokeFboHeight;

    // ========== Sweep FBO ==========
    private int sweepFboId = -1;
    private int sweepFboTextureId = -1;

    // ========== 新增：独立的掩码图层 FBO ==========
    private int maskFboId = -1;
    private int maskFboTextureId = -1;

    private FloatBuffer quadVertexBuffer;
    private FloatBuffer quadTexCoordBuffer;
    private FloatBuffer sweepQuadTexCoordBuffer; // Y 翻转，匹配 GradientRenderer_e2 的 UV 约定

    // ========== Sweep 动画与描边状态 ==========
    private static final float SWEEP_DURATION = 0.6f; // 秒
    private volatile boolean isSweepAnimating = false;
    private long sweepStartTime = 0;
    private volatile boolean isSweepCompleted = false; // 新增：用于精细控制描边图层的显示时机

    // ========== 笔画数据 ==========
    private List<Point> currentStroke = new ArrayList<>();
    private List<CompletedStroke> completedStrokes = new CopyOnWriteArrayList<>();
    private volatile boolean isDrawing = false;
    private volatile boolean isCatchingUp = false;
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
                    isSweepCompleted = true;
                    // 连续请求重绘，确保多缓冲区都能更新到最终的描边图层
                    renderCallback.requestRender();
                    handler.postDelayed(() -> renderCallback.requestRender(), 16);
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
                    "    // 1. 完美复刻 doodle 源码的首尾圆角坐标映射逻辑\n" +
                    "    if (v_uv.x < u_stroke_width) {\n" +
                    "        dx = (u_stroke_width - v_uv.x) / u_stroke_width;\n" +
                    "    } else if (v_uv.x > u_total_length - u_stroke_width) {\n" +
                    "        dx = (v_uv.x - (u_total_length - u_stroke_width)) / u_stroke_width;\n" +
                    "    }\n" +
                    "    float dist_to_center = sqrt(dx * dx + v_uv.y * v_uv.y);\n" +
                    "    \n" +
                    "    // 2. 核心对齐：Doodle 的高斯白核在 0.52 附近达到饱合并开始向外快速衰减\n" +
                    "    // 将此半径作为实体路径边界，即可与 Doodle 的视觉粗细完美贴合\n" +
                    "    float max_radius = 0.52;\n" +
                    "    \n" +
                    "    if (dist_to_center > max_radius) {\n" +
                    "        discard;\n" +
                    "    }\n" +
                    "    \n" +
                    "    // 3. 消除整体渐变：核心内部（<= 0.50）呈现绝对均匀的 50% 不透明度（0.5）\n" +
                    "    // 仅在最外侧极窄圈 [0.50, 0.52] 内使用 smoothstep 进行边缘抗锯齿，防止出现毛糙锯齿\n" +
                    "    float alpha = smoothstep(max_radius, max_radius - 0.02, dist_to_center) * 0.5;\n" +
                    "    \n" +
                    "    // 遵循 FBO 预乘混合规范输出\n" +
                    "    gl_FragColor = vec4(alpha, alpha, alpha, alpha);\n" +
                    "}\n";

    // 新增：掩码片元着色器，剔除外部虚边，输出 1.0 纯白绝对实体掩码
    private static final String MASK_FRAGMENT_SRC =
            "precision highp float;\n" +
                    "varying vec2 v_uv;\n" +
                    "uniform float u_total_length;\n" +
                    "uniform float u_stroke_width;\n" +
                    "void main() {\n" +
                    "    float dx = 0.0;\n" +
                    "    if (v_uv.x < u_stroke_width) {\n" +
                    "        dx = (u_stroke_width - v_uv.x) / u_stroke_width;\n" +
                    "    } else if (v_uv.x > u_total_length - u_stroke_width) {\n" +
                    "        dx = (v_uv.x - (u_total_length - u_stroke_width)) / u_stroke_width;\n" +
                    "    }\n" +
                    "    float dist_to_center = sqrt(dx * dx + v_uv.y * v_uv.y);\n" +
                    "    if (dist_to_center > 0.42) discard;\n" + // 根据核心区边界硬切断
                    "    gl_FragColor = vec4(1.0, 1.0, 1.0, 1.0);\n" +
                    "}\n";

    // 新增：全屏边缘扩张描边着色器（屏幕空间 Dilation），自动跳过内部交叠区
    private static final String OUTLINE_FRAGMENT_SRC =
            "precision highp float;\n" +
                    "varying vec2 v_texCoord;\n" +
                    "uniform sampler2D u_maskTexture;\n" +
                    "uniform vec2 u_texelSize;\n" +
                    "uniform vec4 u_outlineColor;\n" +
                    "void main() {\n" +
                    "    float center = texture2D(u_maskTexture, v_texCoord).a;\n" +
                    "    if (center > 0.5) {\n" +
                    "        discard;\n" + // 像素内部不着色
                    "    }\n" +
                    "    float strokeCount = 0.0;\n" +
                    "    // 半径为 3 像素的全方位膨胀边缘搜索 (可微调控制外描边粗细)\n" +
                    "    for (int x = -3; x <= 3; x++) {\n" +
                    "        for (int y = -3; y <= 3; y++) {\n" +
                    "            if (x == 0 && y == 0) continue;\n" +
                    "            vec2 offset = vec2(float(x), float(y)) * u_texelSize;\n" +
                    "            if (texture2D(u_maskTexture, v_texCoord + offset).a > 0.5) {\n" +
                    "                strokeCount = 1.0;\n" +
                    "                break;\n" +
                    "            }\n" +
                    "        }\n" +
                    "        if (strokeCount > 0.5) break;\n" +
                    "    }\n" +
                    "    if (strokeCount > 0.5) {\n" +
                    "        gl_FragColor = u_outlineColor;\n" +
                    "    } else {\n" +
                    "        discard;\n" +
                    "    }\n" +
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

        // 初始化 Sweep Shader
        String sweepFragSrc = loadShaderFast("sweep_light_fragment_shader.glsl");
        sweepProgram = createGLProgram(COMPOSITE_VERTEX_SRC, sweepFragSrc);
        sweepPositionLoc  = GLES20.glGetAttribLocation(sweepProgram, "a_position");
        sweepTexCoordLoc  = GLES20.glGetAttribLocation(sweepProgram, "a_texCoord");
        sweepMvpLoc       = GLES20.glGetUniformLocation(sweepProgram, "uMVPMatrix");
        sweepTimeLoc      = GLES20.glGetUniformLocation(sweepProgram, "u_time");
        sweepDurationLoc  = GLES20.glGetUniformLocation(sweepProgram, "u_duration");
        sweepResLoc       = GLES20.glGetUniformLocation(sweepProgram, "u_resolution");

        // 新增：初始化 掩码 Shader 变量
        maskProgram = createGLProgram(PATH_VERTEX_SRC, MASK_FRAGMENT_SRC);
        maskPositionLoc = GLES20.glGetAttribLocation(maskProgram, "a_position");
        maskUvLoc = GLES20.glGetAttribLocation(maskProgram, "a_uv");
        maskMvpLoc = GLES20.glGetUniformLocation(maskProgram, "uMVPMatrix");
        maskTotalLengthLoc = GLES20.glGetUniformLocation(maskProgram, "u_total_length");
        maskStrokeWidthLoc = GLES20.glGetUniformLocation(maskProgram, "u_stroke_width");

        // 新增：初始化 全屏边缘描边 Shader 变量
        outlineProgram = createGLProgram(COMPOSITE_VERTEX_SRC, OUTLINE_FRAGMENT_SRC);
        outlinePositionLoc = GLES20.glGetAttribLocation(outlineProgram, "a_position");
        outlineTexCoordLoc = GLES20.glGetAttribLocation(outlineProgram, "a_texCoord");
        outlineMvpLoc = GLES20.glGetUniformLocation(outlineProgram, "uMVPMatrix");
        outlineTextureLoc = GLES20.glGetUniformLocation(outlineProgram, "u_maskTexture");
        outlineTexelSizeLoc = GLES20.glGetUniformLocation(outlineProgram, "u_texelSize");
        outlineColorLoc = GLES20.glGetUniformLocation(outlineProgram, "u_outlineColor");

        float[] quadVerts = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f};
        float[] quadTexs  = { 0f,  0f, 1f,  0f,  0f, 1f, 1f, 1f};
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
        createMaskFBO(width, height); // 新增：构建独立的 Mask 层
    }

    public void onDestroy() {
        handler.removeCallbacks(renderRunnable);
        handler.removeCallbacks(sweepRenderRunnable);
        destroyStrokeFBO();
        destroySweepFBO();
        destroyMaskFBO(); // 新增：清理 FBO 资源
        if (doodleProgram != 0) GLES20.glDeleteProgram(doodleProgram);
        if (pathProgram != 0) GLES20.glDeleteProgram(pathProgram);
        if (compositeProgram != 0) GLES20.glDeleteProgram(compositeProgram);
        if (sweepProgram != 0) GLES20.glDeleteProgram(sweepProgram);
        if (maskProgram != 0) GLES20.glDeleteProgram(maskProgram); // 新增
        if (outlineProgram != 0) GLES20.glDeleteProgram(outlineProgram); // 新增
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

    // 新增：构建独立的 Mask FBO 像素阵列空间
    private void createMaskFBO(int width, int height) {
        destroyMaskFBO();
        int[] texIds = new int[1];
        GLES20.glGenTextures(1, texIds, 0);
        maskFboTextureId = texIds[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskFboTextureId);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);

        int[] fboIds = new int[1];
        GLES20.glGenFramebuffers(1, fboIds, 0);
        maskFboId = fboIds[0];
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, maskFboId);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, maskFboTextureId, 0);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void destroyMaskFBO() {
        if (maskFboId != -1) GLES20.glDeleteFramebuffers(1, new int[]{maskFboId}, 0);
        if (maskFboTextureId != -1) GLES20.glDeleteTextures(1, new int[]{maskFboTextureId}, 0);
        maskFboId = -1;
        maskFboTextureId = -1;
    }

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
                isSweepCompleted = false; // 新增：开始新的一笔画时，重置并隐藏上一轮的描边层
                handler.removeCallbacks(renderRunnable);

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
                        startSweepAnimation();
                    }
                }
                break;
        }
        return true;
    }

    public void cancelCurrentStroke() {
        handler.removeCallbacks(renderRunnable);
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

        List<Point> renderPoints = null;
        float renderTotalLength = 0f;
        synchronized (currentStroke) {
            if (currentStroke.size() >= 2) {
                renderPoints = new ArrayList<>(currentStroke);
                renderTotalLength = currentTotalLength;
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

        float aspect = getModelAspect(mvpMatrix);

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

        for (CompletedStroke cs : completedStrokes) {
            if (cs.vertexBuffer == null || Math.abs(cs.aspect - aspect) > 0.01f) {
                cs.vertexBuffer = generateVertexBuffer(cs.points, aspect);
                cs.aspect = aspect;
                cs.numVertices = cs.points.size() * 2;
            }
            GLES20.glUniform1f(pathTotalLengthLoc, cs.totalLength);
            drawBuffer(cs.vertexBuffer, cs.numVertices, pathPositionLoc, pathUvLoc);
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

        // ================= 新增：渲染纯实体色块到 Mask FBO =================
        if (isSweepCompleted && maskFboId != -1) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, maskFboId);
            GLES20.glViewport(0, 0, strokeFboWidth, strokeFboHeight);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

            GLES20.glUseProgram(maskProgram);
            GLES20.glUniformMatrix4fv(maskMvpLoc, 1, false, mvpMatrix, 0);
            GLES20.glUniform1f(maskStrokeWidthLoc, STROKE_WIDTH);

            // 将所有完成的历史轨迹合并填充到扁平 Mask 图层，消除线段相交引发的几何重叠
            for (CompletedStroke cs : completedStrokes) {
                if (cs.vertexBuffer != null) {
                    GLES20.glUniform1f(maskTotalLengthLoc, cs.totalLength);
                    drawBuffer(cs.vertexBuffer, cs.numVertices, maskPositionLoc, maskUvLoc);
                }
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        // ================= Composite 原有核心合并层渲染到屏幕 =================
        compositeToScreen(sweepAlpha);

        // ================= 新增：如果扫光完结，叠加全新的外轮廓描边图层到屏幕 =================
        if (isSweepCompleted && maskFboId != -1) {
            compositeOutlineToScreen();
        }
    }

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

    // 新增：利用 Mask FBO 纹理计算并显示外圈不自重叠描边
    private void compositeOutlineToScreen() {
        GLES20.glViewport(0, 0, viewWidth, viewHeight);
        GLES20.glUseProgram(outlineProgram);

        GLES20.glEnable(GLES20.GL_BLEND);
        // 标准 Alpha 混合
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        float[] identity = new float[16];
        Matrix.setIdentityM(identity, 0);
        GLES20.glUniformMatrix4fv(outlineMvpLoc, 1, false, identity, 0);

        // 传递倒数像素尺寸，通知着色器屏幕像素步长
        GLES20.glUniform2f(outlineTexelSizeLoc, 1.0f / viewWidth, 1.0f / viewHeight);

        // 设置描边颜色：例如纯白色描边 vec4(1.0, 1.0, 1.0, 1.0)，可根据业务随意调整
        GLES20.glUniform4f(outlineColorLoc, 1.0f, 1.0f, 1.0f, 1.0f);

        // 绑定 Mask FBO 纹理
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, maskFboTextureId);
        GLES20.glUniform1i(outlineTextureLoc, 0);

        GLES20.glEnableVertexAttribArray(outlinePositionLoc);
        GLES20.glVertexAttribPointer(outlinePositionLoc, 2, GLES20.GL_FLOAT, false, 0, quadVertexBuffer);

        GLES20.glEnableVertexAttribArray(outlineTexCoordLoc);
        GLES20.glVertexAttribPointer(outlineTexCoordLoc, 2, GLES20.GL_FLOAT, false, 0, quadTexCoordBuffer);

        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glDisableVertexAttribArray(outlinePositionLoc);
        GLES20.glDisableVertexAttribArray(outlineTexCoordLoc);
        GLES20.glDisable(GLES20.GL_BLEND);
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

    private void compositeToScreen(float sweepAlpha) {
        GLES20.glViewport(0, 0, viewWidth, viewHeight);
        GLES20.glUseProgram(compositeProgram);

        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);

        float[] identity = new float[16];
        Matrix.setIdentityM(identity, 0);
        GLES20.glUniformMatrix4fv(compositeMvpLoc, 1, false, identity, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, strokeFboTextureId);
        GLES20.glUniform1i(compositeTextureLoc, 0);

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