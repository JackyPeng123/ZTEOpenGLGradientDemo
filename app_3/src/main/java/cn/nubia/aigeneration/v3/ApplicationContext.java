package cn.nubia.aigeneration.v3;

import android.content.Context;

public class ApplicationContext {
    private static Context sContext;
    public static void setContext(Context context) {
        sContext = context;
    }
    public static Context getContext() {
        return sContext;
    }
    public static String getPackageName() {
        return BuildConfig.APPLICATION_ID;
    }
}
