package cn.nubia.aigeneration.v5.libgemini;

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
    void setGeneratingBitmap(Bitmap bitmap, float left, float top, float right, float bottom);

    /**
     * 【已生成】的图片位于【生成中】图片的左上右下间距
     */
    void setGeneratedBitmap(Bitmap bitmap);

    /**
     * 变更状态
     */
    void performChangeState(@GradientRenderer.State int state);
}
