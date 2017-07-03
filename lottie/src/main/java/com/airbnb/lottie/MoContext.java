package com.airbnb.lottie;

import android.content.Context;

/**
 * Created by pc on 2017/6/23.
 */

public class MoContext {
  private static MoContext sInstance = null;
  private static Context mBase;
  private MoContext(){
  }

  public static MoContext getInstance(){
    if (sInstance == null){
      synchronized (MoContext.class){
        sInstance = new MoContext();
      }
    }
    return sInstance;
  }

  public void setContext(Context base){
    mBase = base;
  }

  public Context getContext(){
    return mBase;
  }
}
