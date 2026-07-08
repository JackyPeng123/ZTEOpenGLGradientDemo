package cn.nubia.aigeneration.v5;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class CheckerboardView extends View {

    private Paint mPaint;

    public CheckerboardView(Context context) {
        super(context);
        init(context);
    }

    public CheckerboardView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public CheckerboardView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        // 1. 定义格子的颜色（提取自你提供的深色棋盘格截图）
        int colorDark = Color.parseColor("#333333");
        int colorLight = Color.parseColor("#444444");

        // 2. 定义单个格子的边长 (这里设为 12dp，并转换为 px 以保证多屏一致性)
        int squareSizeInDp = 12;
        int squareSize = (int) (squareSizeInDp * context.getResources().getDisplayMetrics().density);

        // 3. 创建一个 2x2 格子大小的 Bitmap 作为平铺的“基础贴图”
        Bitmap bitmap = Bitmap.createBitmap(squareSize * 2, squareSize * 2, Bitmap.Config.ARGB_8888);
        Canvas bitmapCanvas = new Canvas(bitmap);

        Paint tempPaint = new Paint();

        // 4. 绘制基础纹理单元
        // 绘制深色块 (左上 和 右下)
        tempPaint.setColor(colorDark);
        bitmapCanvas.drawRect(0, 0, squareSize, squareSize, tempPaint);
        bitmapCanvas.drawRect(squareSize, squareSize, squareSize * 2, squareSize * 2, tempPaint);

        // 绘制浅色块 (右上 和 左下)
        tempPaint.setColor(colorLight);
        bitmapCanvas.drawRect(squareSize, 0, squareSize * 2, squareSize, tempPaint);
        bitmapCanvas.drawRect(0, squareSize, squareSize, squareSize * 2, tempPaint);

        // 5. 将画好的 Bitmap 制作为 Shader，并设置为 REPEAT（X和Y方向都重复平铺）
        BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT);
        mPaint.setShader(shader);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // 6. 在 onDraw 中只需画一个填满 View 的大矩形，Shader 会自动用棋盘格填充它
        canvas.drawRect(0, 0, getWidth(), getHeight(), mPaint);
    }
}