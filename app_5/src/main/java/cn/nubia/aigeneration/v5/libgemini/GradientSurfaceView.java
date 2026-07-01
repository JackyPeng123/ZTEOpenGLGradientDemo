package cn.nubia.aigeneration.v5.libgemini;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewOutlineProvider;

import androidx.annotation.NonNull;

public class GradientSurfaceView extends GLSurfaceView implements Renderer {
    private Renderer renderer = new GradientRenderer();

    // 【新增】暂存圆角参数与当前计算出来的绘制区间
    private float mCornerRadius = 0f;
    private int mDrawLeft, mDrawTop, mDrawWidth, mDrawHeight;

    public GradientSurfaceView(Context context) {
        super(context);
        init();
    }

    public GradientSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);

        setRenderer(renderer);
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {}

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {}

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                if (renderer != null && renderer instanceof GradientRenderer) {
                    ((GradientRenderer)renderer).onDestroy();
                }
            }
        });
        // 【修改】在此处直接捕获并关联 Renderer 抛出的坐标变换事件
        if (renderer != null && renderer instanceof GradientRenderer) {
            ((GradientRenderer) renderer).setOnDrawRectChangedListener(new GradientRenderer.OnDrawRectChangedListener() {
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
        pause();
    }

    @Override
    public void resume() {
        onResume();
    }

    @Override
    public void pause() {
        onPause();
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
        if (renderer != null && renderer instanceof GradientRenderer) {
            ((GradientRenderer)renderer).setGeneratingBitmap(bitmap);
        }
        requestRender();
    }

    @Override
    public void setGeneratedPixelOffset(float left, float top, float right, float bottom) {
        if (renderer != null && renderer instanceof GradientRenderer) {
            ((GradientRenderer)renderer).setGeneratedPixelOffset(left, top, right, bottom);
        }
        requestRender();
    }

    @Override
    public void performChangeState(@GradientRenderer.State int state) {
        if (renderer != null && renderer instanceof GradientRenderer) {
            ((GradientRenderer)renderer).performChangeState(state);
        }
        requestRender();
    }
}