package info.amsa.slideshow;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Point;
import android.graphics.drawable.BitmapDrawable;
import android.media.ExifInterface;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.Scanner;
import java.util.regex.MatchResult;

public class MainActivity extends Activity {
    private final static String TAG = "SlideShow";

    private ImageView imageView;
    private File filesDir;
    private Random random = new Random();
    private Handler handler = new Handler();
    private Point displaySize = new Point();
    private boolean screenOn;
    private AsyncTask<Void, Void, Bitmap> photoLoaderTask = null;
    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK |
                PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG);

        Display display = getWindowManager().getDefaultDisplay();
        display.getRealSize(displaySize);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 0);
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WAKE_LOCK) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WAKE_LOCK}, 0);
        }

        imageView = (ImageView) findViewById(R.id.imageView);
        imageView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);

        File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        filesDir = new File(picturesDir, "Slide Show");
        if (!filesDir.exists()) {
            return;
        }

        screenOn();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    }

    private synchronized void screenOn() {
        Log.d(TAG, "Screen on");
        assert photoLoaderTask == null;
        getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        );
        screenOn = true;
        wakeLock.acquire();
        startPhotoLoader();

        runAtNextHourMinute(21, 39, new Runnable() {
            @Override
            public void run() {
                screenOff();
            }
        });
    }

    private synchronized void startPhotoLoader() {
        photoLoaderTask = new PhotoLoaderTask().execute();
    }

    private synchronized void screenOff() {
        Log.d(TAG, "Screen off");
        wakeLock.release();
        getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );
        screenOn = false;
        photoLoaderTask.cancel(true);
        photoLoaderTask = null;
        runAtNextHourMinute(21, 41, new Runnable() {
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
                if (!imageFile.isFile() || !imageFile.getPath().toLowerCase().endsWith(".jpg") || !oldEnough(imageFile)) {
                    continue;
                }
                imageFiles.add(imageFile);
            }

            if (imageFiles.isEmpty()) {
                return null;
            }
            int index = Math.abs(random.nextInt()) % imageFiles.size();
            File imageFile = imageFiles.get(index);
            Log.d(TAG, imageFile.getPath());

            Bitmap origImage = new BitmapDrawable(getApplicationContext().getResources(), imageFile.getAbsolutePath()).getBitmap();
            int newHeight = (int) (origImage.getHeight() * (displaySize.x / (float) origImage.getWidth()));
            Bitmap scaled = Bitmap.createScaledBitmap(origImage, displaySize.x, newHeight, true);

            Bitmap bm = BitmapFactory.decodeFile(imageFile.getAbsolutePath());

            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                return null;
            }

            return bm;
        }

        @Override
        protected void onPostExecute(Bitmap bm) {
            if (bm != null) {
                imageView.setImageBitmap(bm);
            }
            startPhotoLoader();
        }

        @Override
        protected void onCancelled() {
            int i = 0;
        }
    }

    private boolean oldEnough(File imageFile) {
        ExifInterface exif;
        try {
            exif = new ExifInterface(imageFile.getAbsolutePath());
        } catch (IOException e) {
            Log.i(TAG, "Exception while getting EXIF time", e);
            return false;
        }

        String dateString = exif.getAttribute(ExifInterface.TAG_DATETIME);
        if (dateString == null) {
            return true;
        }

        Scanner scanner = new Scanner(dateString);
        scanner.findInLine("(\\d+):(\\d+):(\\d+) (\\d+):(\\d+):(\\d)");
        MatchResult result = scanner.match();
        if (result.groupCount() != 6) {
            return true;
        }
        int year   = Integer.parseInt(result.group(1));
        int month  = Integer.parseInt(result.group(2));
        int day    = Integer.parseInt(result.group(3));
        int hour   = Integer.parseInt(result.group(4));
        int minute = Integer.parseInt(result.group(5));
        int second = Integer.parseInt(result.group(6));

        Calendar cal = Calendar.getInstance();
        cal.set(Calendar.YEAR, year);
        cal.set(Calendar.MONTH, month);
        cal.set(Calendar.DAY_OF_MONTH, day);
        cal.set(Calendar.HOUR, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, second);

        Calendar cutoff = Calendar.getInstance();
        cutoff.add(Calendar.YEAR, -1);

        return cal.before(cutoff);
    }
}
