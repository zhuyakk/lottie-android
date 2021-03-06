package com.airbnb.lottie;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.support.annotation.FloatRange;
import android.support.annotation.Nullable;
import android.support.v4.util.LongSparseArray;

import java.util.ArrayList;
import java.util.List;

class CompositionLayer extends BaseLayer {
  private final List<BaseLayer> layers = new ArrayList<>();
  private final RectF rect = new RectF();

  CompositionLayer(LottieDrawable lottieDrawable, Layer layerModel, List<Layer> layerModels,
      LottieComposition composition) {
    super(lottieDrawable, layerModel);

    LongSparseArray<BaseLayer> layerMap =
        new LongSparseArray<>(composition.getLayers().size());

    BaseLayer mattedLayer = null;
    for (int i = layerModels.size() - 1; i >= 0; i--) {
      Layer lm = layerModels.get(i);
      BaseLayer layer = BaseLayer.forModel(lm, lottieDrawable, composition);
      if (layer == null){
        continue;
      }
      layer.setProgressLayer(lm.isProgressLayer());
      layer.setCanbeGone(lm.isCanbeGone());
      layer.setMinDrawProgress(lm.getminProgress());
      layer.setMaxDrawProgress(lm.getmaxProgress());

      layerMap.put(layer.getLayerModel().getId(), layer);
      if (mattedLayer != null) {
        mattedLayer.setMatteLayer(layer);
        mattedLayer = null;
      } else {
        layers.add(0, layer);
        switch (lm.getMatteType()) {
          case Add:
          case Invert:
            mattedLayer = layer;
            break;
        }
      }
    }

    for (int i = 0; i < layerMap.size(); i++) {
      long key = layerMap.keyAt(i);
      BaseLayer layerView = layerMap.get(key);
      BaseLayer parentLayer = layerMap.get(layerView.getLayerModel().getParentId());
      if (parentLayer != null) {
        layerView.setParentLayer(parentLayer);
      }
    }
  }

  @Override void drawLayer(Canvas canvas, Matrix parentMatrix, int parentAlpha) {
    for (int i = layers.size() - 1; i >= 0 ; i--) {
      layers.get(i).draw(canvas, parentMatrix, parentAlpha);
    }
  }

  @Override public void getBounds(RectF outBounds, Matrix parentMatrix) {
    super.getBounds(outBounds, parentMatrix);
    rect.set(0, 0, 0, 0);
    for (int i = layers.size() - 1; i >= 0; i--) {
      BaseLayer content = layers.get(i);
      content.getBounds(rect, boundsMatrix);
      if (outBounds.isEmpty()) {
        outBounds.set(rect);
      } else {
        outBounds.set(
            Math.min(outBounds.left, rect.left),
            Math.min(outBounds.top, rect.top),
            Math.max(outBounds.right, rect.right),
            Math.max(outBounds.bottom, rect.bottom)
        );
      }
    }
  }

  public void setIsLayerDraw(boolean isDraw){
    for (int i = layers.size() - 1; i >= 0 ; i --){
      layers.get(i).setLayerVisible(isDraw);
    }
  }

  @Override
  public void setMaxProgress(@FloatRange(from = 0f, to = 1f) float progress) {
    progress -= layerModel.getStartProgress();
    for (int i = layers.size() - 1; i >= 0; i--) {
      layers.get(i).setMaxProgress(progress);
    }
  }

  @Override
  public void setProgress(@FloatRange(from = 0f, to = 1f) float progress) {
    super.setProgress(progress);
    progress -= layerModel.getStartProgress();
    for (int i = layers.size() - 1; i >= 0; i--) {
      layers.get(i).setProgress(progress);
    }
  }

  @Override
  public void resetProgress() {
    super.resetProgress();
    for (int i = layers.size() - 1; i >= 0; i--) {
      layers.get(i).resetProgress();
    }
  }

  boolean hasMasks() {
    for (int i = layers.size() - 1; i >= 0; i--) {
      BaseLayer layer = layers.get(i);
      if (layer instanceof ShapeLayer) {
        if (layer.hasMasksOnThisLayer()) {
          return true;
        }
      }
    }
    return false;
  }

  boolean hasMatte() {
    if (hasMatteOnThisLayer()) {
      return true;
    }

    for (int i = layers.size() - 1; i >= 0; i--) {
      if (layers.get(i).hasMatteOnThisLayer()) {
        return true;
      }
    }
    return false;
  }

  @Override public void addColorFilter(@Nullable String layerName, @Nullable String contentName,
      @Nullable ColorFilter colorFilter) {
    for (int i = 0; i < layers.size(); ++i) {
      final BaseLayer layer = layers.get(i);
      final String name = layer.getLayerModel().getName();
      if (layerName == null) {
        layer.addColorFilter(null, null, colorFilter);
      } else if (name.equals(layerName)) {
        layer.addColorFilter(layerName, contentName, colorFilter);
      }
    }
  }
}
