package info.amsa.slideshow;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Random;

public class MainActivity extends Activity {
    private ImageView imageView;
    private File filesDir;
    private Random random = new Random();
    Handler handler = new Handler();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 0);
        }
        //if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
        //    requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 0);
        //}

        imageView = (ImageView) findViewById(R.id.imageView);
        imageView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);

        File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        filesDir = new File(picturesDir, "Photo Frame");
        if (!filesDir.exists()) {
            return;
        }

        screenOn();

        new PhotoLoaderTask().execute();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    }
    private void screenOn() {
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        runAtNextHourMinute(11, 30, new Runnable() {
            @Override
            public void run() {
                screenOff();
            }
        });
    }
    private void screenOff() {
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        runAtNextHourMinute(6, 30, new Runnable() {
            @Override
            public void run() {
                screenOn();
            }
        });
    }

    private void runAtNextHourMinute(int hour, int minute, Runnable runnable) {
        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.HOUR_OF_DAY, hour);
        cal.set(Calendar.MINUTE, minute);
        if (cal.before(Calendar.getInstance())) {
            cal.add(Calendar.HOUR_OF_DAY, 24);
        }
        long intervalToNextSleepTime = cal.getTimeInMillis() - System.currentTimeMillis();
        long postTime = SystemClock.uptimeMillis() + intervalToNextSleepTime;
        handler.postAtTime(runnable, postTime);
    }

    private class PhotoLoaderTask extends AsyncTask<Void, Void, Bitmap> {
        @Override
        protected Bitmap doInBackground(Void[] params) {
            List<File> imageFiles = new ArrayList<>();
            File[] fileList = filesDir.listFiles();
            if (fileList == null) {
                return null;
            }

            for (File imageFile : fileList) {
                if (!imageFile.isFile() || !imageFile.getPath().toLowerCase().endsWith(".jpg")) {
                    continue;
                }
                imageFiles.add(imageFile);
            }

            if (imageFiles.isEmpty()) {
                return null;
            }
            int index = Math.abs(random.nextInt()) % imageFiles.size();
            File imageFile = imageFiles.get(index);
            Bitmap bm = BitmapFactory.decodeFile(imageFile.getAbsolutePath());

            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

            return bm;
        }

        @Override
        protected void onPostExecute(Bitmap bm) {
            if (bm != null) {
                imageView.setImageBitmap(bm);
            }
            new PhotoLoaderTask().execute();
        }
    }
}
