package cn.nubia.aigeneration.v3;

import android.app.Application;
import android.content.Context;

public class MyApplication extends Application {
    private static Context sContext;

    @Override
    public void onCreate() {
        super.onCreate();
        sContext = this;
        ApplicationContext.setContext(this);
    }

    public static Context getContext() {
        return sContext;
    }
}
