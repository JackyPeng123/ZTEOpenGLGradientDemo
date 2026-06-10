package cn.nubia.redmagickyi.achievement;

import android.app.Activity;
import android.os.Bundle;

import cn.nubia.redmagickyi.achievement.libgemini.GradientSurfaceView;
import cn.nubia.redmagickyi.achievement.libgemini.GradientTextureView;

public class MainActivity extends Activity {
    private GradientSurfaceView glSurfaceView;
    private GradientTextureView glTextureView;

    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        glSurfaceView = findViewById(R.id.surfaceView);
        glSurfaceView.setRoundCorner(52f);
//        glTextureView = findViewById(R.id.textureView);
//        glTextureView.setRoundCorner(52f);
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