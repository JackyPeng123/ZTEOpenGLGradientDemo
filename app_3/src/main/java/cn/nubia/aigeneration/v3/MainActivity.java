package cn.nubia.aigeneration.v3;

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

import cn.nubia.aigeneration.v3.libgemini.GradientSurfaceView;
import cn.nubia.aigeneration.v3.libgemini.GradientTextureView;
import cn.nubia.aigeneration.v3.libgemini.GradientRenderer;
import cn.nubia.aigeneration.v3.libgemini.Renderer;

public class MainActivity extends Activity {
    private FrameLayout container;
    private ImageView imageView;
    private Renderer renderer;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        GradientSurfaceView glSurfaceView = findViewById(R.id.surfaceView);
//        glSurfaceView.setRoundCorner(52f);
        GradientTextureView glTextureView = findViewById(R.id.textureView);
//        glTextureView.setRoundCorner(52f);
        renderer = glSurfaceView.getVisibility() == View.VISIBLE? glSurfaceView : glTextureView;

        container = findViewById(R.id.container);
        imageView = findViewById(R.id.imageView);
        imageView.setVisibility(View.INVISIBLE);
        test();
    }

    private void test() {
        if (renderer != null) {
            Bitmap bitmap = ((BitmapDrawable)(imageView.getDrawable())).getBitmap();
            if (renderer != null) {
                renderer.setGeneratingBitmap(bitmap);
            }
            renderer.performChangeState(GradientRenderer.STATE_GENERATING);
        }
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