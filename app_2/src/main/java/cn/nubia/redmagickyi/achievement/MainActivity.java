package cn.nubia.redmagickyi.achievement;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import cn.nubia.redmagickyi.achievement2.R;
import cn.nubia.redmagickyi.achievement.libgemini.GradientSurfaceView;
import cn.nubia.redmagickyi.achievement.libgemini.GradientTextureView;

public class MainActivity extends Activity {
    private FrameLayout container;
    private ImageView imageView;
    private GradientSurfaceView glSurfaceView;
    private GradientTextureView glTextureView;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        glSurfaceView = findViewById(R.id.surfaceView);
//        glSurfaceView.setRoundCorner(52f);
//        glTextureView = findViewById(R.id.textureView);
//        glTextureView.setRoundCorner(52f);

        container = findViewById(R.id.container);
        imageView = findViewById(R.id.imageView);

        container.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int containerWidth = right - left;
                int containerHeight = bottom - top;

                if (containerWidth > 0 && containerHeight > 0) {
                    Bitmap bitmap = ((BitmapDrawable)(imageView.getDrawable())).getBitmap();
                    glSurfaceView.setImageBitmap(bitmap);
                    adjustGLSurfaceViewSize(containerWidth, containerHeight, bitmap.getWidth(), bitmap.getHeight());
                }
            }
        });
    }

    /**
     * 核心算法：模拟 ImageView 的 fitCenter 计算出精确的 Rect，并应用到 GLSurfaceView
     */
    private void adjustGLSurfaceViewSize(int containerW, int containerH, int bitmapW, int bitmapH) {
        float containerRatio = (float) containerW / containerH;
        float bitmapRatio = (float) bitmapW / bitmapH;

        float targetW, targetH;

        if (bitmapRatio > containerRatio) {
            // 图片更宽，以容器宽度为准，缩放高度
            targetW = containerW;
            targetH = containerW / bitmapRatio;
        } else {
            // 图片更高，以容器高度为准，缩放宽度
            targetW = containerH * bitmapRatio;
            targetH = containerH;
        }

        // 动态修改 GLSurfaceView 的 LayoutParams
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) glSurfaceView.getLayoutParams();
        params.width = Math.round(targetW);
        params.height = Math.round(targetH);

        // 这样可以确保它在 FrameLayout 里绝对居中且大小与 fitCenter 的图片完全一致
        glSurfaceView.setLayoutParams(params);
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (glSurfaceView != null) {
            glSurfaceView.pause();
        }
        if (glTextureView != null) {
            glTextureView.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (glSurfaceView != null) {
            glSurfaceView.resume();
        }
        if (glTextureView != null) {
            glTextureView.resume();
        }
    }
}