package cn.nubia.aigeneration.v4.libgemini;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;

public class GradientTextureView extends TextureView implements Renderer {
    private static final String TAG = "GradientTextureView";
    private GLRenderThread renderThread;

    // 【核心修复点】：缓存用户真实的生命周期意图（默认用户希望对齐 Activity 自动运行）
    private boolean mUserWantToRun = true;

    // 【新增】暂存圆角参数与当前计算出来的绘制区间
    private float mCornerRadius = 0f;
    private int mDrawLeft, mDrawTop, mDrawWidth, mDrawHeight;

    public GradientTextureView(@NonNull Context context) { super(context); init(); }
    public GradientTextureView(@NonNull Context context, @Nullable AttributeSet attrs) { super(context, attrs); init(); }
    public GradientTextureView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); init(); }
    public GradientTextureView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) { super(context, attrs, defStyleAttr, defStyleRes); init(); }

    private void init() {
        renderThread = new GLRenderThread();
        setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                // 每次可用时创建新线程，并将当前的生命周期意图 mUserWantToRun 传递进去
                renderThread.init(surface, width, height, mUserWantToRun);
                renderThread.start();
                // 【修改】在此处直接捕获并关联 Renderer 抛出的坐标变换事件
                if (renderThread.renderer instanceof GradientRenderer) {
                    ((GradientRenderer) renderThread.renderer).setOnDrawRectChangedListener(new GradientRenderer.OnDrawRectChangedListener() {
                        @Override
                        public void onDrawRectChanged(final int left, final int top, final int width, final int height) {
                            // 确保切回 UI 主线程安全设置圆角
                            post(new Runnable() {
                                @Override
                                public void run() {
                                    mDrawLeft = left;
                                    mDrawTop = top;
                                    mDrawWidth = width;
                                    mDrawHeight = height;
                                    applyRoundCornerInternal();
                                }
                            });
                        }
                    });
                }
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
                if (renderThread != null) {
                    renderThread.queueEvent(() -> renderThread.handleSizeChanged(width, height));
                }
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                if (renderThread != null) {
                    renderThread.safeRelease();
                    renderThread = null;
                }
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {}
        });
    }

    @Override
    public void resume() {
        mUserWantToRun = true;
        if (renderThread != null) {
            renderThread.onResume();
        } else {
            Log.d(TAG, "resume() called before thread initialization. Cached intent.");
        }
    }

    @Override
    public void pause() {
        mUserWantToRun = false;
        if (renderThread != null) {
            renderThread.onPause();
        } else {
            Log.d(TAG, "pause() called before thread initialization. Cached intent.");
        }
    }

    @Override
    public void setRoundCorner(float radius) {
        this.mCornerRadius = radius;
        post(new Runnable() {
            @Override
            public void run() {
                applyRoundCornerInternal();
            }
        });
    }

    // 【新增】合并刷新圆角，将边缘完美贴合在图片居中边缘
    private void applyRoundCornerInternal() {
        if (mCornerRadius > 0 && mDrawWidth > 0 && mDrawHeight > 0) {
            setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(mDrawLeft, mDrawTop, mDrawLeft + mDrawWidth, mDrawTop + mDrawHeight, mCornerRadius);
                }
            });
            setClipToOutline(true);
        }
    }

    @Override
    public void setGeneratingBitmap(Bitmap bitmap) {
        Log.i("TAG", "=========setGeneratingBitmap: " + bitmap);
        if (renderThread != null) {
            renderThread.queueEvent(() -> renderThread.setGeneratingBitmap(bitmap));
        }
    }

    @Override
    public void setGeneratedPixelOffset(float left, float top, float right, float bottom) {
        if (renderThread != null) {
            renderThread.queueEvent(() -> renderThread.setGeneratedPixelOffset(left, top, right, bottom));
        }
    }

    @Override
    public void performChangeState(@GradientRenderer.State int state) {
        if (renderThread != null) {
            renderThread.queueEvent(() -> renderThread.performChangeState(state));
        }
    }

    /**
     * 优化后的渲染线程
     */
    private static class GLRenderThread extends Thread {
        private final GLSurfaceView.Renderer renderer = new GradientRenderer();
        private SurfaceTexture surfaceTexture;
        private int width;
        private int height;

        private final Object mLock = new Object();
        private boolean isRunning = true;
        private boolean isPaused; // 状态由构造函数直接决定
        private boolean sizeChanged = false;

        private final ArrayList<Runnable> mEventQueue = new ArrayList<>();

        private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
        private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;

        // 【核心修复点】：构造函数接收初始暂停状态
        private void init(SurfaceTexture surfaceTexture, int width, int height, boolean startImmediately) {
            this.surfaceTexture = surfaceTexture;
            this.width = width;
            this.height = height;
            // 如果用户希望运行，则不暂停(!startImmediately)
            this.isPaused = !startImmediately;
        }

        public void queueEvent(Runnable r) {
            if (r == null) return;
            synchronized (mLock) {
                mEventQueue.add(r);
                mLock.notifyAll();
            }
        }

        public void handleSizeChanged(int width, int height) {
            this.width = width;
            this.height = height;
            this.sizeChanged = true;
        }

        public void setGeneratingBitmap(Bitmap bitmap) {
            if (renderer != null && renderer instanceof GradientRenderer) {
                ((GradientRenderer)renderer).setGeneratingBitmap(bitmap);
            }
        }

        public void setGeneratedPixelOffset(float left, float top, float right, float bottom) {
            if (renderer != null && renderer instanceof GradientRenderer) {
                ((GradientRenderer)renderer).setGeneratedPixelOffset(left, top, right, bottom);
            }
        }

        public void performChangeState(@GradientRenderer.State int state) {
            if (renderer != null && renderer instanceof GradientRenderer) {
                ((GradientRenderer)renderer).performChangeState(state);
            }
        }

        @Override
        public void run() {
            try {
                initEGL();
            } catch (Exception e) {
                Log.e(TAG, "EGL init failed, exit thread.", e);
                releaseEGL();
                return;
            }

            if (renderer != null) {
                renderer.onSurfaceCreated(null, null);
                renderer.onSurfaceChanged(null, width, height);
            }

            while (true) {
                Runnable eventToRun = null;

                synchronized (mLock) {
                    while (isRunning && isPaused && mEventQueue.isEmpty()) {
                        try {
                            mLock.wait();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    }

                    if (!isRunning) {
                        break;
                    }

                    if (!mEventQueue.isEmpty()) {
                        eventToRun = mEventQueue.remove(0);
                    }
                }

                if (eventToRun != null) {
                    eventToRun.run();
                    if (sizeChanged && renderer != null) {
                        renderer.onSurfaceChanged(null, width, height);
                        sizeChanged = false;
                    }
                    continue;
                }

                long startTime = System.currentTimeMillis();
                if (renderer != null) {
                    renderer.onDrawFrame(null);
                    if (!EGL14.eglSwapBuffers(eglDisplay, eglSurface)) {
                        Log.e(TAG, "eglSwapBuffers failed!");
                    }
                }

                long diff = System.currentTimeMillis() - startTime;
                if (diff < 16) {
                    try {
                        Thread.sleep(16 - diff);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }

            releaseRenderer();
            releaseEGL();

            synchronized (mLock) {
                mLock.notifyAll();
            }
        }

        private void initEGL() {
            eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] version = new int[2];
            EGL14.eglInitialize(eglDisplay, version, 0, version, 1);

            int[] configAttribs = {
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_NONE
            };

            EGLConfig[] configs = new EGLConfig[1];
            int[] numConfigs = new int[1];
            EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, configs.length, numConfigs, 0);

            int[] contextAttribs = {
                    EGL14.EGL_CONTEXT_CLIENT_VERSION, 2,
                    EGL14.EGL_NONE
            };
            eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, contextAttribs, 0);

            int[] surfaceAttribs = { EGL14.EGL_NONE };
            eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], surfaceTexture, surfaceAttribs, 0);

            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext);
        }

        public void onPause() {
            synchronized (mLock) {
                isPaused = true;
                mLock.notifyAll();
            }
        }

        public void onResume() {
            synchronized (mLock) {
                isPaused = false;
                mLock.notifyAll();
            }
        }

        public void safeRelease() {
            synchronized (mLock) {
                isRunning = false;
                isPaused = false;
                mLock.notifyAll();
            }
            try {
                join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private void releaseRenderer() {
            if (renderer instanceof GradientRenderer) {
                ((GradientRenderer) renderer).onDestroy();
            }
        }

        private void releaseEGL() {
            if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface);
                if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext);
                EGL14.eglTerminate(eglDisplay);
            }
            eglDisplay = EGL14.EGL_NO_DISPLAY;
            eglContext = EGL14.EGL_NO_CONTEXT;
            eglSurface = EGL14.EGL_NO_SURFACE;
        }
    }
}