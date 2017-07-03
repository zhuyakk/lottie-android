package com.airbnb.lottie;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.support.annotation.CallSuper;
import android.support.annotation.FloatRange;
import android.support.annotation.Nullable;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

abstract class BaseLayer implements DrawingContent, BaseKeyframeAnimation.AnimationListener {
  private static final int SAVE_FLAGS = Canvas.CLIP_SAVE_FLAG | Canvas.CLIP_TO_LAYER_SAVE_FLAG |
      Canvas.MATRIX_SAVE_FLAG;

  @Nullable
  static BaseLayer forModel(
    Layer layerModel, LottieDrawable drawable, LottieComposition composition) {
    switch (layerModel.getLayerType()) {
      case Shape:
        return new ShapeLayer(drawable, layerModel);
      case PreComp:
        return new CompositionLayer(drawable, layerModel,
            composition.getPrecomps(layerModel.getRefId()), composition);
      case Solid:
        return new SolidLayer(drawable, layerModel);
      case Image:
        return new ImageLayer(drawable, layerModel, composition.getDpScale());
      case Null:
        return new NullLayer(drawable, layerModel);
      case Text:
      case Unknown:
      default:
        // Do nothing
        Log.w(L.TAG, "Unknown layer type " + layerModel.getLayerType());
        return null;
    }
  }

  private final Path path = new Path();
  private final Matrix matrix = new Matrix();
  private final Paint contentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint maskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint mattePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final Paint clearPaint = new Paint();
  private final RectF rect = new RectF();
  private final RectF maskBoundsRect = new RectF();
  private final RectF matteBoundsRect = new RectF();
  private final RectF tempMaskBoundsRect = new RectF();
  final Matrix boundsMatrix = new Matrix();
  final LottieDrawable lottieDrawable;
  final Layer layerModel;
  @Nullable private MaskKeyframeAnimation mask;
  @Nullable private BaseLayer matteLayer;
  @Nullable private BaseLayer parentLayer;
  private List<BaseLayer> parentLayers;

  private final List<BaseKeyframeAnimation<?, ?>> animations = new ArrayList<>();
  final TransformKeyframeAnimation transform;
  private boolean visible = true;

  private boolean isProgressLayer = false;//标识是否进度层
  private float maxProgress = 1f;//记录进度层进度

  private float progress = 0f;

  //当json文件中标记这两个值时，只有当进度层的maxProgress在这两个值之间，该图层才被绘制
  private float minDrawProgress = 0f;//进度满足的最小值
  private float maxDrawProgress = 1f;//进度满足的最大值
  private boolean progressNotDraw = false;//记录是否绘制当前图层,防止in/out anim覆盖掉结果

  private boolean canbeGone = false;//标识当前图层是否可以被隐藏

  BaseLayer(LottieDrawable lottieDrawable, Layer layerModel) {
    this.lottieDrawable = lottieDrawable;
    this.layerModel = layerModel;
    clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));
    maskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    if (layerModel.getMatteType() == Layer.MatteType.Invert) {
      mattePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
    } else {
      mattePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
    }

    this.transform = layerModel.getTransform().createAnimation();
    transform.addListener(this);
    transform.addAnimationsToLayer(this);

    if (layerModel.getMasks() != null && !layerModel.getMasks().isEmpty()) {
      this.mask = new MaskKeyframeAnimation(layerModel.getMasks());
      for (BaseKeyframeAnimation<?, Path> animation : mask.getMaskAnimations()) {
        addAnimation(animation);
        animation.addUpdateListener(this);
      }
    }
    setupInOutAnimations();
  }

  @Override public void onValueChanged() {
    invalidateSelf();
  }

  Layer getLayerModel() {
    return layerModel;
  }

  void setMatteLayer(@Nullable BaseLayer matteLayer) {
    this.matteLayer = matteLayer;
  }

  boolean hasMatteOnThisLayer() {
    return matteLayer != null;
  }

  void setParentLayer(@Nullable BaseLayer parentLayer) {
    this.parentLayer = parentLayer;
  }

  private void setupInOutAnimations() {
    if (!layerModel.getInOutKeyframes().isEmpty()) {
      final FloatKeyframeAnimation inOutAnimation =
          new FloatKeyframeAnimation(layerModel.getInOutKeyframes());
      inOutAnimation.setIsDiscrete();
      inOutAnimation.addUpdateListener(new BaseKeyframeAnimation.AnimationListener() {
        @Override
        public void onValueChanged() {
          if (!progressNotDraw) {
            setVisible(inOutAnimation.getValue() == 1f);
          }
        }
      });
      if (!progressNotDraw) {
        setVisible(inOutAnimation.getValue() == 1f);
      }
      addAnimation(inOutAnimation);
    } else {
      setVisible(true);
    }
  }

  private void invalidateSelf() {
    lottieDrawable.invalidateSelf();
  }

  void addAnimation(BaseKeyframeAnimation<?, ?> newAnimation) {
    if (!(newAnimation instanceof StaticKeyframeAnimation)) {
      animations.add(newAnimation);
    }
  }

  @CallSuper @Override public void getBounds(RectF outBounds, Matrix parentMatrix) {
    boundsMatrix.set(parentMatrix);
    boundsMatrix.preConcat(transform.getMatrix());
  }

  @Override
  public void draw(Canvas canvas, Matrix parentMatrix, int parentAlpha) {
    if (!visible) {
      return;
    }
    buildParentLayerListIfNeeded();
    matrix.reset();
    matrix.set(parentMatrix);
    for (int i = parentLayers.size() - 1; i >= 0; i--) {
      matrix.preConcat(parentLayers.get(i).transform.getMatrix());
    }
    int alpha = (int)
        ((parentAlpha / 255f * (float) transform.getOpacity().getValue() / 100f) * 255);
    if (!hasMatteOnThisLayer() && !hasMasksOnThisLayer()) {
      matrix.preConcat(transform.getMatrix());
      drawLayer(canvas, matrix, alpha);
      return;
    }

    rect.set(0, 0, 0, 0);
    getBounds(rect, matrix);
    intersectBoundsWithMatte(rect, matrix);

    matrix.preConcat(transform.getMatrix());
    intersectBoundsWithMask(rect, matrix);

    rect.set(0, 0, Utils.getScreenWidth(MoContext.getInstance().getContext()), Utils
        .getScreenHeight(MoContext.getInstance().getContext()));
    //rect.set(0, 0, canvas.getWidth(), canvas.getHeight());//TODO 使用canvas.getHeight canvas.getWidth拿到的图案可能绘制不全
    canvas.saveLayer(rect, contentPaint, Canvas.ALL_SAVE_FLAG);
    // Clear the off screen buffer. This is necessary for some phones.
    clearCanvas(canvas);
    drawLayer(canvas, matrix, alpha);

    if (hasMasksOnThisLayer()) {
      applyMasks(canvas, matrix);
    }

    if (hasMatteOnThisLayer()) {
      canvas.saveLayer(rect, mattePaint, SAVE_FLAGS);
      clearCanvas(canvas);
      //noinspection ConstantConditions
      matteLayer.draw(canvas, parentMatrix, alpha);
      canvas.restore();
    }

    canvas.restore();
  }

  private void clearCanvas(Canvas canvas) {
    // If we don't pad the clear draw, some phones leave a 1px border of the graphics buffer.
    canvas.drawRect(rect.left - 1, rect.top - 1, rect.right + 1, rect.bottom + 1, clearPaint);
  }

  private void intersectBoundsWithMask(RectF rect, Matrix matrix) {
    maskBoundsRect.set(0, 0, 0, 0);
    if (!hasMasksOnThisLayer()) {
      return;
    }
    //noinspection ConstantConditions
    int size = mask.getMasks().size();
    for (int i = 0; i < size; i++) {
      Mask mask = this.mask.getMasks().get(i);
      BaseKeyframeAnimation<?, Path> maskAnimation = this.mask.getMaskAnimations().get(i);
      Path maskPath = maskAnimation.getValue();
      path.set(maskPath);
      path.transform(matrix);

      switch (mask.getMaskMode()) {
        case MaskModeSubtract:
          // If there is a subtract mask, the mask could potentially be the size of the entire
          // canvas so we can't use the mask bounds.
          return;
        case MaskModeAdd:
        default:
          path.computeBounds(tempMaskBoundsRect, false);
          // As we iterate through the masks, we want to calculate the union region of the masks.
          // We initialize the rect with the first mask. If we don't call set() on the first call,
          // the rect will always extend to (0,0).
          if (i == 0) {
            maskBoundsRect.set(tempMaskBoundsRect);
          } else {
            maskBoundsRect.set(
              Math.min(maskBoundsRect.left, tempMaskBoundsRect.left),
              Math.min(maskBoundsRect.top, tempMaskBoundsRect.top),
              Math.max(maskBoundsRect.right, tempMaskBoundsRect.right),
              Math.max(maskBoundsRect.bottom, tempMaskBoundsRect.bottom)
            );
          }
      }
    }

    rect.set(
        Math.max(rect.left, maskBoundsRect.left),
        Math.max(rect.top, maskBoundsRect.top),
        Math.min(rect.right, maskBoundsRect.right),
        Math.min(rect.bottom, maskBoundsRect.bottom)
    );
  }

  private void intersectBoundsWithMatte(RectF rect, Matrix matrix) {
    if (!hasMatteOnThisLayer()) {
      return;
    }
    if (layerModel.getMatteType() == Layer.MatteType.Invert) {
      // We can't trim the bounds if the mask is inverted since it extends all the way to the
      // composition bounds.
      return;
    }
    //noinspection ConstantConditions
    matteLayer.getBounds(matteBoundsRect, matrix);
    rect.set(
        Math.max(rect.left, matteBoundsRect.left),
        Math.max(rect.top, matteBoundsRect.top),
        Math.min(rect.right, matteBoundsRect.right),
        Math.min(rect.bottom, matteBoundsRect.bottom)
    );
  }

  abstract void drawLayer(Canvas canvas, Matrix parentMatrix, int parentAlpha);

  private void applyMasks(Canvas canvas, Matrix matrix) {
    canvas.saveLayer(rect, maskPaint, SAVE_FLAGS);
    clearCanvas(canvas);

    //noinspection ConstantConditions
    int size = mask.getMasks().size();
    for (int i = 0; i < size; i++) {
      Mask mask = this.mask.getMasks().get(i);
      BaseKeyframeAnimation<?, Path> maskAnimation = this.mask.getMaskAnimations().get(i);
      Path maskPath = maskAnimation.getValue();
      path.set(maskPath);
      path.transform(matrix);

      switch (mask.getMaskMode()) {
        case MaskModeSubtract:
          path.setFillType(Path.FillType.INVERSE_WINDING);
          break;
        case MaskModeAdd:
        default:
          path.setFillType(Path.FillType.WINDING);
      }
      canvas.drawPath(path, contentPaint);
    }
    canvas.restore();
  }

  boolean hasMasksOnThisLayer() {
    return mask != null && !mask.getMaskAnimations().isEmpty();
  }

  private void setVisible(boolean visible) {
    if (visible != this.visible) {
      this.visible = visible;
      invalidateSelf();
    }
  }

  void setLayerVisible(boolean visible) {
    if (canbeGone && visible != this.visible) {
      this.visible = visible;
      invalidateSelf();
    }
  }

  /** for initial*/
  void setCanbeGone(boolean canbeGone){
    this.canbeGone = canbeGone;
  }

  void setProgressLayer(boolean isProgressLayer){
    this.isProgressLayer = isProgressLayer;
    if (matteLayer != null) {
      matteLayer.setProgressLayer(isProgressLayer);
    }
    for (int i = 0; i < animations.size(); i++) {
      animations.get(i).setProgressLayer(isProgressLayer);
    }
  }

  void setMaxProgress(@FloatRange(from = 0f, to = 1f) float maxProgress) {

    if (isProgressLayer) {
      if (maxProgress == this.maxProgress){
        return;
      }

      this.maxProgress = maxProgress;

      if (matteLayer != null) {
        matteLayer.setMaxProgress(maxProgress);
      }
      for (int i = 0; i < animations.size(); i++) {
        animations.get(i).setMaxProgress(maxProgress);
      }
    }

    if (maxProgress >= this.minDrawProgress && maxProgress <= this.maxDrawProgress){//正常
      progressNotDraw = false;
      setVisible(true);
    }else {
      progressNotDraw = true;
      setVisible(false);
    }
  }

  void resetProgress(){
    this.progress = 0f;

    if (matteLayer != null) {
      matteLayer.resetProgress();
    }
    for (int i = 0; i < animations.size(); i++) {
      animations.get(i).resetProgress();
    }
  }

  void setProgress(@FloatRange(from = 0f, to = 1f) float progress) {
    if (isProgressLayer) {
      if (this.progress > this.maxProgress){//实际进度比max大,立刻刷新
        setRealProgress(this.maxProgress);
        return;
      }

      if (progress > this.maxProgress) {//最多走到max进度
        if (this.progress < this.maxProgress){//如果progress已经最大了,this.progress还没到max，直接赋值为最大
          setRealProgress(this.maxProgress);
        }
        return;
      }

      if (this.progress > progress){//走到指定进度后不再动画
        return;
      }
    }

   setRealProgress(progress);
  }

  private void setRealProgress(@FloatRange(from = 0f, to = 1f) float progress){
    this.progress = progress;

    if (matteLayer != null) {
      matteLayer.setProgress(this.progress);
    }
    for (int i = 0; i < animations.size(); i++) {
      animations.get(i).setProgress(this.progress);
    }
  }

  void setMinDrawProgress(float minDrawProgress){
    this.minDrawProgress = minDrawProgress;
  }
  void setMaxDrawProgress(float maxDrawProgress){
    this.maxDrawProgress = maxDrawProgress;
  }

  private void buildParentLayerListIfNeeded() {
    if (parentLayers != null) {
      return;
    }
    if (parentLayer == null) {
      parentLayers = Collections.emptyList();
      return;
    }

    parentLayers = new ArrayList<>();
    BaseLayer layer = parentLayer;
    while (layer != null) {
      parentLayers.add(layer);
      layer = layer.parentLayer;
    }
  }

  @Override public String getName() {
    return layerModel.getName();
  }

  @Override public void setContents(List<Content> contentsBefore, List<Content> contentsAfter) {
    // Do nothing
  }

  @Override public void addColorFilter(@Nullable String layerName, @Nullable String contentName,
      @Nullable ColorFilter colorFilter) {
    // Do nothing
  }
}
