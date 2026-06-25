package cn.nubia.aigeneration.v3.libgemini;

import android.animation.Animator;
import android.animation.ValueAnimator;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.animation.DecelerateInterpolator;

/**
 * 这个类封装了所有的缩放、位移、回弹逻辑和矩阵计算。
 */
public class TransformController {
    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 5.0f;
    private static final long REBOUND_DURATION = 260L;

    private float currentScale = 1.0f;
    private float lastSpan = 0f;
    private float lastFocusX = 0f;
    private float lastFocusY = 0f;
    private boolean hadScaleGesture = false;

    private float screenWidth = 1080f;
    private float screenHeight = 1920f;
    private float imageWidth = 0f;
    private float imageHeight = 0f;

    private final float[] projectionMatrix = new float[16];
    private final float[] viewMatrix = new float[16];
    private final float[] modelMatrix = new float[16];
    private final float[] mvpMatrix = new float[16];

    private float orthoLeft = -1f;
    private float orthoRight = 1f;
    private float orthoBottom = -1f;
    private float orthoTop = 1f;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private ValueAnimator reboundAnimator;
    private final GradientRenderer.RenderCallback renderCallback;

    public TransformController(GradientRenderer.RenderCallback renderCallback) {
        this.renderCallback = renderCallback;
        android.opengl.Matrix.setIdentityM(modelMatrix, 0);
    }

    public void setImageSize(float width, float height) {
        this.imageWidth = width;
        this.imageHeight = height;
        resetTransformToFitCenter();
        updateFitCenterMatrix();
    }

    public void setScreenSize(float width, float height) {
        this.screenWidth = width;
        this.screenHeight = height;
        updateFitCenterMatrix();
    }

    public float[] getMvpMatrix() {
        float[] tempMatrix = new float[16];
        android.opengl.Matrix.multiplyMM(tempMatrix, 0, viewMatrix, 0, modelMatrix, 0);
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, projectionMatrix, 0, tempMatrix, 0);
        return mvpMatrix;
    }

    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int pointerCount = event.getPointerCount();

        switch (action) {
            case MotionEvent.ACTION_DOWN:
                cancelReboundIfNeeded();
                break;

            case MotionEvent.ACTION_POINTER_DOWN:
                if (pointerCount == 2) {
                    float x1 = event.getX(0);
                    float y1 = event.getY(0);
                    float x2 = event.getX(1);
                    float y2 = event.getY(1);

                    if (isPointInImage(x1, y1) && isPointInImage(x2, y2)) {
                        cancelReboundIfNeeded();
                        lastSpan = (float) Math.hypot(x2 - x1, y2 - y1);
                        lastFocusX = (x1 + x2) / 2f;
                        lastFocusY = (y1 + y2) / 2f;
                        hadScaleGesture = true;
                    }
                } else if (pointerCount > 2 && hadScaleGesture) {
                    hadScaleGesture = false;
                    lastSpan = 0f;
                    lastFocusX = 0f;
                    lastFocusY = 0f;
                    animateReboundToValidState();
                }
                break;

            case MotionEvent.ACTION_MOVE:
                if (pointerCount == 2 && hadScaleGesture) {
                    float x1 = event.getX(0);
                    float y1 = event.getY(0);
                    float x2 = event.getX(1);
                    float y2 = event.getY(1);

                    float currentSpan = (float) Math.hypot(x2 - x1, y2 - y1);
                    float focusX = (x1 + x2) / 2f;
                    float focusY = (y1 + y2) / 2f;

                    boolean matrixChanged = false;

                    if (lastFocusX > 0 && lastFocusY > 0) {
                        float dx = focusX - lastFocusX;
                        float dy = focusY - lastFocusY;
                        if (Math.abs(dx) > 0.5f || Math.abs(dy) > 0.5f) {
                            setTranslation(dx, dy);
                            matrixChanged = true;
                        }
                    }

                    if (lastSpan > 0 && currentSpan > 0) {
                        float scaleFactor = currentSpan / lastSpan;
                        if (Math.abs(scaleFactor - 1.0f) > 0.001f) {
                            setScale(scaleFactor, focusX, focusY);
                            matrixChanged = true;
                        }
                    }

                    if (matrixChanged && renderCallback != null) {
                        renderCallback.requestRender();
                    }

                    lastSpan = currentSpan;
                    lastFocusX = focusX;
                    lastFocusY = focusY;
                }
                break;

            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                lastSpan = 0f;
                lastFocusX = 0f;
                lastFocusY = 0f;
                if (hadScaleGesture) {
                    hadScaleGesture = false;
                    animateReboundToValidState();
                }
                break;
        }
        return true;
    }

    private boolean isPointInImage(float x, float y) {
        if (screenWidth == 0 || screenHeight == 0) return false;

        float glX = (x / screenWidth) * (orthoRight - orthoLeft) + orthoLeft;
        float glY = orthoTop - (y / screenHeight) * (orthoTop - orthoBottom);

        float[] leftBottom = new float[4];
        float[] rightTop = new float[4];
        float[] vMin = {-1f, -1f, 0f, 1f};
        float[] vMax = {1f, 1f, 0f, 1f};

        android.opengl.Matrix.multiplyMV(leftBottom, 0, modelMatrix, 0, vMin, 0);
        android.opengl.Matrix.multiplyMV(rightTop, 0, modelMatrix, 0, vMax, 0);

        float contentLeft = leftBottom[0];
        float contentBottom = leftBottom[1];
        float contentRight = rightTop[0];
        float contentTop = rightTop[1];

        return glX >= contentLeft - 0.05f && glX <= contentRight + 0.05f &&
                glY >= contentBottom - 0.05f && glY <= contentTop + 0.05f;
    }

    /**
     * 每次更换图片时重置缩放系数
     */
    private void resetTransformToFitCenter() {
        currentScale = 1.0f;
        android.opengl.Matrix.setIdentityM(modelMatrix, 0);
    }

    private void setTranslation(float dx, float dy) {
        float glDx = dx / screenWidth * (orthoRight - orthoLeft);
        float glDy = -dy / screenHeight * (orthoTop - orthoBottom);

        float[] tempMatrix = new float[16];
        float[] currentModel = modelMatrix.clone();

        android.opengl.Matrix.setIdentityM(tempMatrix, 0);
        android.opengl.Matrix.translateM(tempMatrix, 0, glDx, glDy, 0f);
        android.opengl.Matrix.multiplyMM(modelMatrix, 0, tempMatrix, 0, currentModel, 0);
    }

    private void setScale(float scaleFactor, float focusX, float focusY) {
        currentScale *= scaleFactor;

        float normalizedX = focusX / screenWidth * 2.0f - 1.0f;
        float normalizedY = -(focusY / screenHeight * 2.0f - 1.0f);

        float glFocusX = normalizedX * (orthoRight - orthoLeft) / 2.0f + (orthoRight + orthoLeft) / 2.0f;
        float glFocusY = normalizedY * (orthoTop - orthoBottom) / 2.0f + (orthoTop + orthoBottom) / 2.0f;

        float[] tempMatrix = new float[16];
        float[] currentModel = modelMatrix.clone();

        android.opengl.Matrix.setIdentityM(tempMatrix, 0);
        android.opengl.Matrix.translateM(tempMatrix, 0, glFocusX, glFocusY, 0f);
        android.opengl.Matrix.scaleM(tempMatrix, 0, scaleFactor, scaleFactor, 1.0f);
        android.opengl.Matrix.translateM(tempMatrix, 0, -glFocusX, -glFocusY, 0f);
        android.opengl.Matrix.multiplyMM(modelMatrix, 0, tempMatrix, 0, currentModel, 0);
    }

    private void animateReboundToValidState() {
        final float targetScale = clamp(currentScale, MIN_SCALE, MAX_SCALE);
        final float[] startMatrix = modelMatrix.clone();
        final float startScale = currentScale;

        float[] targetMatrix = modelMatrix.clone();

        if (Math.abs(targetScale - currentScale) > 0.0001f) {
            float scaleCorrection = targetScale / currentScale;

            float centerX = (orthoLeft + orthoRight) / 2.0f;
            float centerY = (orthoBottom + orthoTop) / 2.0f;

            float[] scaleFixMatrix = new float[16];
            float[] currentTarget = targetMatrix.clone();

            android.opengl.Matrix.setIdentityM(scaleFixMatrix, 0);
            android.opengl.Matrix.translateM(scaleFixMatrix, 0, centerX, centerY, 0f);
            android.opengl.Matrix.scaleM(scaleFixMatrix, 0, scaleCorrection, scaleCorrection, 1f);
            android.opengl.Matrix.translateM(scaleFixMatrix, 0, -centerX, -centerY, 0f);
            android.opengl.Matrix.multiplyMM(targetMatrix, 0, scaleFixMatrix, 0, currentTarget, 0);
        }

        float[] fix = computeBorderFix(targetMatrix);
        float dx = fix[0];
        float dy = fix[1];

        if (Math.abs(dx) > 0.0001f || Math.abs(dy) > 0.0001f) {
            float[] transMatrix = new float[16];
            float[] currentTarget = targetMatrix.clone();

            android.opengl.Matrix.setIdentityM(transMatrix, 0);
            android.opengl.Matrix.translateM(transMatrix, 0, dx, dy, 0f);
            android.opengl.Matrix.multiplyMM(targetMatrix, 0, transMatrix, 0, currentTarget, 0);
        }

        startReboundAnimator(startMatrix, targetMatrix, startScale, targetScale);
    }

    private float[] computeBorderFix(float[] matrix) {
        float[] result = new float[]{0f, 0f};

        float[] leftBottom = new float[4];
        float[] rightTop = new float[4];
        float[] vMin = {-1f, -1f, 0f, 1f};
        float[] vMax = {1f, 1f, 0f, 1f};

        android.opengl.Matrix.multiplyMV(leftBottom, 0, matrix, 0, vMin, 0);
        android.opengl.Matrix.multiplyMV(rightTop, 0, matrix, 0, vMax, 0);

        float contentLeft = leftBottom[0];
        float contentBottom = leftBottom[1];
        float contentRight = rightTop[0];
        float contentTop = rightTop[1];

        float contentWidth = contentRight - contentLeft;
        float contentHeight = contentTop - contentBottom;

        float viewWidth = orthoRight - orthoLeft;
        float viewHeight = orthoTop - orthoBottom;

        float dx = 0f;
        float dy = 0f;

        if (contentWidth <= viewWidth) {
            float contentCenterX = (contentLeft + contentRight) / 2f;
            float viewCenterX = (orthoLeft + orthoRight) / 2f;
            dx = viewCenterX - contentCenterX;
        } else {
            if (contentLeft > orthoLeft) {
                dx = orthoLeft - contentLeft;
            } else if (contentRight < orthoRight) {
                dx = orthoRight - contentRight;
            }
        }

        if (contentHeight <= viewHeight) {
            float contentCenterY = (contentBottom + contentTop) / 2f;
            float viewCenterY = (orthoBottom + orthoTop) / 2f;
            dy = viewCenterY - contentCenterY;
        } else {
            if (contentBottom > orthoBottom) {
                dy = orthoBottom - contentBottom;
            } else if (contentTop < orthoTop) {
                dy = orthoTop - contentTop;
            }
        }

        result[0] = dx;
        result[1] = dy;
        return result;
    }

    private void startReboundAnimator(final float[] startMatrix, final float[] targetMatrix, final float startScale, final float targetScale) {
        cancelReboundIfNeeded();

        mainHandler.post(() -> {
            reboundAnimator = ValueAnimator.ofFloat(0f, 1f);
            reboundAnimator.setDuration(REBOUND_DURATION);
            reboundAnimator.setInterpolator(new DecelerateInterpolator());

            reboundAnimator.addUpdateListener(animation -> {
                float fraction = (float) animation.getAnimatedValue();
                for (int i = 0; i < 16; i++) {
                    modelMatrix[i] = startMatrix[i] + (targetMatrix[i] - startMatrix[i]) * fraction;
                }
                currentScale = startScale + (targetScale - startScale) * fraction;
                if (renderCallback != null) renderCallback.requestRender();
            });

            reboundAnimator.addListener(new Animator.AnimatorListener() {
                @Override
                public void onAnimationStart(Animator animation) {}

                @Override
                public void onAnimationEnd(Animator animation) {
                    System.arraycopy(targetMatrix, 0, modelMatrix, 0, 16);
                    currentScale = targetScale;
                    if (renderCallback != null) renderCallback.requestRender();
                }

                @Override
                public void onAnimationCancel(Animator animation) {}

                @Override
                public void onAnimationRepeat(Animator animation) {}
            });

            reboundAnimator.start();
        });
    }

    private void cancelReboundIfNeeded() {
        if (reboundAnimator != null) {
            reboundAnimator.cancel();
            reboundAnimator = null;
        }
    }

    private void updateFitCenterMatrix() {
        if (imageWidth == 0 || imageHeight == 0 || screenWidth == 0 || screenHeight == 0) return;

        float screenRatio = screenWidth / screenHeight;
        float imageRatio = imageWidth / imageHeight;

        orthoLeft = -1f;
        orthoRight = 1f;
        orthoBottom = -1f;
        orthoTop = 1f;

        if (screenWidth > screenHeight) {
            if (imageRatio > screenRatio) {
                orthoBottom = -imageRatio / screenRatio;
                orthoTop = imageRatio / screenRatio;
            } else {
                orthoLeft = -screenRatio / imageRatio;
                orthoRight = screenRatio / imageRatio;
            }
        } else {
            if (imageRatio > screenRatio) {
                orthoBottom = -1f / (screenRatio / imageRatio);
                orthoTop = 1f / (screenRatio / imageRatio);
            } else {
                orthoLeft = -screenRatio / imageRatio;
                orthoRight = screenRatio / imageRatio;
            }
        }

        android.opengl.Matrix.orthoM(projectionMatrix, 0, orthoLeft, orthoRight, orthoBottom, orthoTop, -1f, 1f);
        android.opengl.Matrix.setLookAtM(viewMatrix, 0, 0, 0, 1, 0, 0, 0, 0, 1, 0);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(value, max));
    }

    public void onDestroy() {
        if (mainHandler != null) {
            mainHandler.removeCallbacksAndMessages(null);
        }
        cancelReboundIfNeeded();
    }
}