package cn.nubia.aigeneration.v4.libgemini;

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
        // 设置OpenGL ES 2.0上下文
        setEGLContextClientVersion(2);
        // 让 GLSurfaceView 知道它需要一个包含 Alpha 通道的颜色缓冲区 (RGBA_8888)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        // 将 SurfaceView 的窗口顶层设置为透明
        getHolder().setFormat(PixelFormat.TRANSLUCENT);
        // 设置渲染器
        setRenderer(this.renderer = getRenderer());
        // 持续渲染模式（自动调用onDrawFrame）
        setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        //控制
        getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {

            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {

            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
                if (renderer != null && renderer instanceof GradientRenderer) {
                    ((GradientRenderer)renderer).onDestroy();
                }
            }
        });
        //马上暂停
        pause();
    }

    protected Renderer getRenderer() {
        return new GradientRenderer();
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