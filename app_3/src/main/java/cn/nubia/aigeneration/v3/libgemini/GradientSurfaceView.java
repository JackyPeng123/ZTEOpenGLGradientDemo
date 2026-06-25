package cn.nubia.aigeneration.v3.libgemini;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;
import android.view.ViewOutlineProvider;

import androidx.annotation.NonNull;

public class GradientSurfaceView extends GLSurfaceView implements Renderer {
    private Renderer renderer;

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
        setEGLConfigChooser(8, 8, 8, 8, 16, 8);
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        setRenderer(this.renderer = getRenderer());
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
        pause();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        if (renderer != null && renderer instanceof GradientRenderer) {
            return ((GradientRenderer)renderer).onTouchEvent(event);
        }
        return super.onTouchEvent(event);
    }

    protected Renderer getRenderer() {
        return new GradientRenderer() {
            @Override
            protected void requestRender() {
                GradientSurfaceView.this.requestRender();
            }
        };
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
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
            }
        });
        setClipToOutline(true);
    }

    @Override
    public void setGeneratingBitmap(Bitmap bitmap) {
        if (renderer != null && renderer instanceof GradientRenderer) {
            ((GradientRenderer)renderer).setGeneratingBitmap(bitmap);
        }
        requestRender();
    }

    @Override
    public void setGeneratedBitmap(Bitmap bitmap) {
        if (renderer != null && renderer instanceof GradientRenderer) {
            ((GradientRenderer)renderer).setGeneratedBitmap(bitmap);
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