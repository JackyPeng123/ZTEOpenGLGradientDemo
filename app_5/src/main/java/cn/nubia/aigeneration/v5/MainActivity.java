package cn.nubia.aigeneration.v5;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.os.Handler;
import android.view.View;
import android.widget.FrameLayout;

import cn.nubia.aigeneration.v5.libgemini.GradientRenderer;
import cn.nubia.aigeneration.v5.libgemini.GradientSurfaceView;
import cn.nubia.aigeneration.v5.libgemini.GradientTextureView;
import cn.nubia.aigeneration.v5.libgemini.Renderer;

public class MainActivity extends Activity {
    private Renderer renderer;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        GradientSurfaceView glSurfaceView = findViewById(R.id.surfaceView);
//        glSurfaceView.setRoundCorner(52f);
        GradientTextureView glTextureView = findViewById(R.id.textureView);
//        glTextureView.setRoundCorner(52f);
        renderer = glSurfaceView.getVisibility() == View.VISIBLE? glSurfaceView : glTextureView;

        test();
    }

    private void test() {
        if (renderer != null) {
            Bitmap bitmap = BitmapFactory.decodeResource(getResources(), R.mipmap.bg2);
            if (renderer != null) {
                renderer.setGeneratingBitmap(bitmap, 300, 300, 300, 300);
            }
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    renderer.performChangeState(GradientRenderer.STATE_GENERATING);
//                    handler.postDelayed(new Runnable() {
//                        @Override
//                        public void run() {
//                            renderer.setGeneratedBitmap(0.55f, 0.5f, 0.25f, 0.41f);
//                            renderer.performChangeState(GradientRenderer.STATE_GENERATED);
//                        }
//                    }, 2000);
                }
            }, 2000);
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