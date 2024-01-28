package info.amsa.slideshow;

import android.app.Application;
import android.content.Context;

import com.google.firebase.FirebaseApp;

public class MainApplication extends Application {
    public static final String TAG = "SlideShow";

    @Override
    public void onCreate() {
        super.onCreate();
        FirebaseApp.initializeApp(this);
        Context context = getApplicationContext();
    }
}
