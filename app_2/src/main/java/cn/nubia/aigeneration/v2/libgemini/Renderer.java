package cn.nubia.aigeneration.v2.libgemini;

import android.graphics.Bitmap;

public interface Renderer {
    /**
     * 恢复播放
     */
    void resume();

    /**
     * 暂停播放
     */
    void pause();

    /**
     * 设置四边圆角
     * @param radius 为0表示无圆角
     */
    void setRoundCorner(float radius);

    /**
     * 【生成中】的图片
     */
    void setGeneratingBitmap(Bitmap bitmap);

    /**
     * 【已生成】的图片
     */
    void setGeneratedBitmap(Bitmap bitmap);

    /**
     * 变更状态
     */
    void performChangeState(@GradientRenderer.State int state);
}
