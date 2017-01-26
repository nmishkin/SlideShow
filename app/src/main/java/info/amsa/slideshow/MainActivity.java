package info.amsa.slideshow;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Point;
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
import android.view.ViewGroup;
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
import java.util.concurrent.TimeUnit;
import java.util.regex.MatchResult;

public class MainActivity extends Activity {
    private final static String TAG = "SlideShow";
    private final static Date EPOCH = new Date(0);

    private static class Picture {
        Picture(File file, Date dateTaken) {
            this.file = file;
            this.dateTaken = dateTaken;
        }
        File file;
        Date dateTaken;
    }

    private ImageView imageView;
    private File filesDir;
    private Random random = new Random();
    private Handler handler = new Handler();
    private Point displaySize = new Point();
    private boolean screenOn;
    private AsyncTask<Boolean, Void, Bitmap> photoLoaderTask = null;
    private PowerManager powerManager;
    private PowerManager.WakeLock wakeLock;
    private List<Picture> pictures = new ArrayList<>();

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
        goFullScreen();

        imageView.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                goFullScreen();
                stopPhotoLoader();
                startPhotoLoader(false);
            }
        });

        final File picturesDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);
        final File filesDir = new File(picturesDir, "Slide Show");
        if (!filesDir.exists()) {
            return;
        }

        final File[] fileList = filesDir.listFiles();
        if (fileList == null) {
            Log.e(TAG, "Can't get list of pictures");
            return;
        }

        for (final File file : fileList) {
            if (file.isFile() && file.getPath().toLowerCase().endsWith(".jpg")) {
                pictures.add(new Picture(file, getDateTaken(file)));
            }
        }

        if (pictures.isEmpty()) {
            Log.e(TAG, "No pictures found");
        }

        screenOn();
    }

    private void goFullScreen() {
        imageView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
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
        startPhotoLoader(false);

        runAtNextHourMinute(23, 30, new Runnable() {
            @Override
            public void run() {
                screenOff();
            }
        });
    }

    private synchronized void startPhotoLoader(boolean pauseFirst) {
        photoLoaderTask = new PhotoLoaderTask().execute(pauseFirst);
    }

    private synchronized void stopPhotoLoader() {
        photoLoaderTask.cancel(true);
        photoLoaderTask = null;
    }

    private synchronized void screenOff() {
        Log.d(TAG, "Screen off");
        wakeLock.release();
        getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );
        screenOn = false;
        stopPhotoLoader();
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
        final long intervalToNextSleepTime = cal.getTimeInMillis() - System.currentTimeMillis();
        final long postTime = SystemClock.uptimeMillis() + intervalToNextSleepTime;
        handler.postAtTime(runnable, postTime);
    }

    private class PhotoLoaderTask extends AsyncTask<Boolean, Void, Bitmap> {
        @Override
        protected Bitmap doInBackground(Boolean... params) {
            assert params.length == 1;
            final boolean pauseFirst = params[0];

            if (pauseFirst) {
                try {
                    Thread.sleep(TimeUnit.DAYS.toMillis(1));
                } catch (InterruptedException e) {
                    return null;
                }
            }

            Picture picture;
            do {
                final int index = Math.abs(random.nextInt()) % pictures.size();
                picture = pictures.get(index);
            } while (!oldEnough(picture));

            final String picturePath = picture.file.getAbsolutePath();
            Log.d(TAG, picturePath);

            return BitmapFactory.decodeFile(picturePath);
        }

        @Override
        protected void onPostExecute(Bitmap bm) {
            if (bm != null) {
                imageView.setBackgroundColor(Color.BLACK);
                imageView.setImageBitmap(bm);
            }
            startPhotoLoader(true);
        }
    }

    private Date getDateTaken(File imageFile) {
        final ExifInterface exif;
        try {
            exif = new ExifInterface(imageFile.getAbsolutePath());
        } catch (IOException e) {
            Log.i(TAG, "Exception while getting EXIF time", e);
            return EPOCH;
        }

        final String dateString = exif.getAttribute(ExifInterface.TAG_DATETIME);
        if (dateString == null) {
            return EPOCH;
        }

        final Scanner scanner = new Scanner(dateString);
        scanner.findInLine("(\\d+):(\\d+):(\\d+) (\\d+):(\\d+):(\\d)");
        final MatchResult result = scanner.match();
        if (result.groupCount() != 6) {
            return EPOCH;
        }
        final int year   = Integer.parseInt(result.group(1));
        final int month  = Integer.parseInt(result.group(2));
        final int day    = Integer.parseInt(result.group(3));
        final int hour   = Integer.parseInt(result.group(4));
        final int minute = Integer.parseInt(result.group(5));
        final int second = Integer.parseInt(result.group(6));

        final Calendar cal = Calendar.getInstance();
        cal.set(Calendar.YEAR, year);
        cal.set(Calendar.MONTH, month);
        cal.set(Calendar.DAY_OF_MONTH, day);
        cal.set(Calendar.HOUR, hour);
        cal.set(Calendar.MINUTE, minute);
        cal.set(Calendar.SECOND, second);

        return cal.getTime();
    }
    private boolean oldEnough(Picture pictureFile) {
        final Calendar cutoff = Calendar.getInstance();
        cutoff.add(Calendar.YEAR, -1);

        final Calendar cal = Calendar.getInstance();
        cal.setTime(pictureFile.dateTaken);
        return cal.before(cutoff);
    }
}
