package cn.nubia.aigeneration.v2;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.animation.Interpolator;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.core.view.animation.PathInterpolatorCompat;

public class ShimmerTextView extends AppCompatTextView {

    private LinearGradient mLinearGradient;
    private Matrix mGradientMatrix;
    private Paint mPaint;
    private int mViewWidth = 0;
    private float mTranslate = 0;
    private ValueAnimator mAnimator;

    // 遮罩（高亮）的宽度，AE中红框宽度为 44dp
    private float mMaskWidth;
    // 模糊半径 (AE快速方框模糊参数)
    private float mBlurRadius;
    // 渐变总宽度
    private float mTotalGradientWidth;

    public ShimmerTextView(@NonNull Context context) {
        this(context, null);
    }

    public ShimmerTextView(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ShimmerTextView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context);
    }

    private void init(Context context) {
        // 1. 设置基础视觉参数
        setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        setTextColor(0xFFFFFFFF); // 基础颜色设为白色，具体透明度由Shader控制

        // 设置 Padding: 上下 10dp, 左右 18dp
        int paddingVertical = dp2px(10);
        int paddingHorizontal = dp2px(18);
        setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical);

        // 设置胶囊背景 (95% opacity of #505050 -> #F2505050)
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF2505050);
        // 设置一个很大的圆角半径使其变成胶囊状
        background.setCornerRadius(dp2px(100));
        setBackground(background);

        mMaskWidth = dp2px(0);
        mBlurRadius = dp2px(37); // AE中的模糊半径 37
        mTotalGradientWidth = mBlurRadius + mMaskWidth + mBlurRadius; // 37 + 44 + 37 = 118dp
        mGradientMatrix = new Matrix();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w > 0) {
            mViewWidth = w;
            mPaint = getPaint();

            int colorBase = 0xFFFFFFFF;       // 100% 纯白 (Alpha 255)
            int colorSweep = 0x1AFFFFFF;      // 10% 透明度的白 (Alpha 26)

            // 为了模拟“迭代3”的高斯平滑曲线，我们在模糊边缘插入多个节点
            int stepsPerEdge = 10; // 单侧边缘用10个节点来模拟曲线，足够平滑了
            int totalNodes = stepsPerEdge * 2 + 2; // 左边缘节点 + 右边缘节点 + 2个核心区节点

            int[] colors = new int[totalNodes];
            float[] positions = new float[totalNodes];

            // 提取 Alpha 值用于计算
            int baseAlpha = 255;
            int sweepAlpha = 26; // 255 * 10% ≈ 26

            int currentIndex = 0;

            // 1. 构建左侧模糊边缘 (从纯白 平滑过渡到 10%白)
            for (int i = 0; i <= stepsPerEdge; i++) {
                float t = (float) i / stepsPerEdge; // 0.0 到 1.0

                // 使用 SmoothStep 曲线 (3t^2 - 2t^3) 模拟迭代3的高斯衰减
                float smoothT = t * t * (3 - 2 * t);

                // 根据曲线计算当前的 Alpha 值
                int currentAlpha = (int) (baseAlpha + (sweepAlpha - baseAlpha) * smoothT);
                colors[currentIndex] = (currentAlpha << 24) | 0x00FFFFFF;

                // 计算位置比例
                positions[currentIndex] = (mBlurRadius * t) / mTotalGradientWidth;
                currentIndex++;
            }

            // 2. 核心遮罩区 (保持 10%白)
            // 左侧边缘结束点就是核心区起点，所以只需要添加核心区终点
            colors[currentIndex] = colorSweep;
            positions[currentIndex] = (mBlurRadius + mMaskWidth) / mTotalGradientWidth;
            currentIndex++;

            // 3. 构建右侧模糊边缘 (从 10%白 平滑过渡回 纯白)
            for (int i = 1; i <= stepsPerEdge; i++) {
                float t = (float) i / stepsPerEdge; // 0.0 到 1.0

                // 同样使用 SmoothStep 曲线
                float smoothT = t * t * (3 - 2 * t);

                int currentAlpha = (int) (sweepAlpha + (baseAlpha - sweepAlpha) * smoothT);
                colors[currentIndex] = (currentAlpha << 24) | 0x00FFFFFF;

                positions[currentIndex] = (mBlurRadius + mMaskWidth + mBlurRadius * t) / mTotalGradientWidth;
                currentIndex++;
            }

            // 构造线性渐变
            mLinearGradient = new LinearGradient(
                    0, 0, mTotalGradientWidth, 0,
                    colors, positions,
                    Shader.TileMode.CLAMP
            );

            mPaint.setShader(mLinearGradient);

            setupAnimator();
        }
    }

    private void setupAnimator() {
        if (mAnimator != null) {
            mAnimator.cancel();
        }
        // 动画范围必须扩大，确保包含巨大模糊半径的整个渐变块完全从屏幕外进入，再完全移出屏幕外
        mAnimator = ValueAnimator.ofFloat(-mTotalGradientWidth, mViewWidth);

        mAnimator.setDuration(2000); // 持续时间 2000ms
        mAnimator.setRepeatCount(ValueAnimator.INFINITE); // cycle
        mAnimator.setRepeatMode(ValueAnimator.RESTART);
        // AE 贝塞尔曲线 (0, 0.33, 0.33, 0)
        Interpolator interpolator = PathInterpolatorCompat.create(0f, 0.33f, 0.33f, 0f);
        mAnimator.setInterpolator(interpolator);
        mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mTranslate = (float) animation.getAnimatedValue();
                invalidate();
            }
        });
        mAnimator.start();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mGradientMatrix != null && mLinearGradient != null) {
            // 通过矩阵平移 Shader 来实现扫光位移
            mGradientMatrix.setTranslate(mTranslate, 0);
            mLinearGradient.setLocalMatrix(mGradientMatrix);
        }
        super.onDraw(canvas);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (mAnimator != null && !mAnimator.isRunning()) {
            mAnimator.start();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAnimator != null) {
            mAnimator.cancel();
        }
    }

    private int dp2px(float dp) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }
}