package cn.nubia.redmagickyi.achievement;

import android.app.Application;
import android.content.Context;
import android.graphics.Typeface;

public class RedmagickyiApplication extends Application {
    private static Context context;
    private final String TAG = getClass().getSimpleName();

    public void onCreate() {
        super.onCreate();
        context = this;
    }

    public static Context getContext() {
        return context;
    }

}
