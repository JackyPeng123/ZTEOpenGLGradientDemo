package cn.nubia.aigeneration.v2;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import cn.nubia.aigeneration.v2.libgemini.GradientSurfaceView;
import cn.nubia.aigeneration.v2.libgemini.GradientTextureView;
import cn.nubia.aigeneration.v2.libgemini.GradientRenderer;
import cn.nubia.aigeneration.v2.libgemini.Renderer;

public class MainActivity extends Activity {
    private FrameLayout container;
    private ImageView imageView;
    private Renderer renderer;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        GradientSurfaceView glSurfaceView = findViewById(R.id.surfaceView);
        glSurfaceView.setRoundCorner(52f);
        GradientTextureView glTextureView = findViewById(R.id.textureView);
        glTextureView.setRoundCorner(52f);
        renderer = glSurfaceView.getVisibility() == View.VISIBLE? glSurfaceView : glTextureView;

        container = findViewById(R.id.container);
        imageView = findViewById(R.id.imageView);
        imageView.setVisibility(View.INVISIBLE);

        container.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
                int containerWidth = right - left;
                int containerHeight = bottom - top;

                if (containerWidth > 0 && containerHeight > 0) {
                    Bitmap bitmap = ((BitmapDrawable)(imageView.getDrawable())).getBitmap();
                    if (renderer != null) {
                        renderer.setGeneratingBitmap(bitmap);
                    }
                    adjustRendererViewSize(containerWidth, containerHeight, bitmap.getWidth(), bitmap.getHeight());
                }
            }
        });
        test();
    }

    /**
     * 核心算法：模拟 ImageView 的 fitCenter 计算出精确的 Rect，并应用到 GLSurfaceView
     */
    private void adjustRendererViewSize(int containerW, int containerH, int bitmapW, int bitmapH) {
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
        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) ((View) renderer).getLayoutParams();
        params.width = Math.round(targetW);
        params.height = Math.round(targetH);

        // 这样可以确保它在 FrameLayout 里绝对居中且大小与 fitCenter 的图片完全一致
        ((View) renderer).setLayoutParams(params);
    }

    private void test() {
        if (renderer != null) {
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    renderer.performChangeState(GradientRenderer.STATE_GENERATING);
                    handler.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            // 1. 获取目标 Bitmap 的尺寸
                            Bitmap targetBitmap = ((BitmapDrawable)(imageView.getDrawable())).getBitmap();
                            int targetWidth = targetBitmap.getWidth();
                            int targetHeight = targetBitmap.getHeight();

                            // 2. 解析未知尺寸的 bg3
                            Bitmap rawBitmapB = BitmapFactory.decodeResource(getResources(), R.mipmap.bg3);

                            // 3. 将 bg3 转换为与目标尺寸一致的 fitCenter Bitmap
                            Bitmap bitmapB = createFitCenterBitmap(rawBitmapB, targetWidth, targetHeight);

                            // 回收原始解析出来的 bitmap，节省内存
                            if (rawBitmapB != bitmapB) {
                                rawBitmapB.recycle();
                            }

                            // 4. 设置 BitmapB 并执行状态切换
                            renderer.setGeneratedBitmap(bitmapB);
                            renderer.performChangeState(GradientRenderer.STATE_GENERATED);
                        }
                    }, 6000);
                }
            }, 1000);
        }
    }

    /**
     * 将源 Bitmap 按照 fitCenter 的方式缩放，并绘制到目标尺寸的 Bitmap 中心
     */
    private Bitmap createFitCenterBitmap(Bitmap source, int targetWidth, int targetHeight) {
        if (source == null) return null;
        if (source.getWidth() == targetWidth && source.getHeight() == targetHeight) {
            return source;
        }

        // 计算 fitCenter 缩放比例
        float scale = Math.min((float) targetWidth / source.getWidth(), (float) targetHeight / source.getHeight());
        int scaledWidth = Math.round(source.getWidth() * scale);
        int scaledHeight = Math.round(source.getHeight() * scale);

        // 缩放源 Bitmap
        Bitmap scaledBitmap = Bitmap.createScaledBitmap(source, scaledWidth, scaledHeight, true);

        // 创建目标尺寸的空白 Bitmap
        Bitmap outputBitmap = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(outputBitmap);

        // 计算居中绘制的偏移量
        float left = (targetWidth - scaledWidth) / 2f;
        float top = (targetHeight - scaledHeight) / 2f;

        // 将缩放后的图片绘制到中心
        canvas.drawBitmap(scaledBitmap, left, top, null);

        // 释放中间产生的缩放 Bitmap
        if (scaledBitmap != source) {
            scaledBitmap.recycle();
        }

        return outputBitmap;
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (renderer != null) {
            renderer.pause();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (renderer != null) {
            renderer.resume();
        }
    }
}