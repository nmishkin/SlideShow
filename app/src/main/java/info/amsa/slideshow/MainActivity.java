package info.amsa.slideshow;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.media.ExifInterface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
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
    private PictureHistoryDb dbh;
    private PrintStream logStream;

    public static class Picture {
        Picture(File file, Date dateTaken) {
            this.file = file;
            this.dateTaken = dateTaken;
        }
        File file;
        Date dateTaken;
    }

    private ImageView imageView;
    private final Random random = new Random();
    private final Handler handler = new Handler();
    private final Point displaySize = new Point();
    private AsyncTask<Boolean, Void, Bitmap> photoLoaderTask = null;
    private PowerManager.WakeLock wakeLock;
    private final List<Picture> pictures = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        WindowManager wm = getWindowManager();
        Display display = wm.getDefaultDisplay();
        DisplayMetrics displayMetrics = new DisplayMetrics();
        display.getMetrics(displayMetrics);

        try {
            FileOutputStream logFile = getApplicationContext().openFileOutput(TAG + ".log", MODE_APPEND);
            logStream = new PrintStream(logFile);
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Can't open log file", e);
        }

        logStream.format("%Tc Application starting\n", new Date());
        setContentView(R.layout.activity_main);

        PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK |
                PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG + ":lock" +
                        "");

        display.getRealSize(displaySize);

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, 0);
        }

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.WAKE_LOCK) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WAKE_LOCK}, 0);
        }

        imageView = findViewById(R.id.imageView);
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
            Log.e(TAG, "Can't find Slide Show picture directory: " + filesDir);
            return;
        }

        final File[] fileList = filesDir.listFiles();
        if (fileList == null) {
            Log.e(TAG, "Can't get list of pictures");
            return;
        }
        logStream.format("%Tc %d files found\n", new Date(), fileList.length);

        for (final File file : fileList) {
            if (file.isFile() && file.getPath().toLowerCase().endsWith(".jpg")) {
                pictures.add(new Picture(file, getDateTaken(file)));
            }
        }

        if (pictures.isEmpty()) {
            Log.e(TAG, "No pictures found");
        }
        logStream.format("%Tc %d pictures found\n", new Date(), pictures.size());

        dbh = new PictureHistoryDb(getApplicationContext());

        screenOn();
    }

    private void goFullScreen() {
        imageView.getWindowInsetsController().hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    }

    private synchronized void screenOn() {
        Log.d(TAG, "Screen on");
        assert photoLoaderTask == null;
        setTurnScreenOn(true);
        setShowWhenLocked(true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        wakeLock.acquire();
        startPhotoLoader(false);

        runAtNextHourMinute(23, 30, this::screenOff);
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
        stopPhotoLoader();
        runAtNextHourMinute(6, 30, this::screenOn);
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

            Picture picture = null;
            boolean foundOne = false;
            for (int i = 0; i < 150; i++) {
                final int index = Math.abs(random.nextInt()) % pictures.size();
                picture = pictures.get(index);
                if (!displayedRecently(picture) && oldEnough(picture)) {
                    foundOne = true;
                    break;
                }
            }
            if (!foundOne) {
                logStream.format("%Tc Didn't find an acceptable picture, proceeding anyway\n", new Date());
            }

            final String picturePath = picture.file.getAbsolutePath();
            logStream.format("%Tc Showing %s\n", new Date(), picturePath);
            dbh.insertPicture(picture);

            Bitmap bitmap = BitmapFactory.decodeFile(picturePath);

            Bitmap bitmapWithText = addTextToBitmap(bitmap, String.valueOf(picture.dateTaken.getYear() + 1900));
            return bitmapWithText;
        }

        private Bitmap addTextToBitmap(Bitmap bitmap, String textToAdd) {
            // Create a mutable copy of the original bitmap
            Bitmap bitmapWithText = bitmap.copy(Bitmap.Config.ARGB_8888, true);

            // Create a canvas to draw on the bitmap
            Canvas canvas = new Canvas(bitmapWithText);

            // Create a Paint object for styling the text
            Paint paint = new Paint();
            paint.setColor(Color.RED);  // Set the text color
            paint.setTextSize(20);      // Set the text size
            paint.setAntiAlias(true);   // Enable anti-aliasing for smoother text
            paint.setFakeBoldText(true);

            // Calculate the position to center the text on the bitmap
            float x = bitmapWithText.getWidth() - paint.measureText(textToAdd) - 10;
            float y = bitmapWithText.getHeight() - paint.getTextSize() + 15;

            // Draw the text on the bitmap
            canvas.drawText(textToAdd, x, y, paint);

            return bitmapWithText;
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (bitmap != null) {
                imageView.setBackgroundColor(Color.BLACK);
                imageView.setImageBitmap(bitmap);
            }
            startPhotoLoader(true);
        }
    }

    private boolean displayedRecently(Picture picture) {
        long t = dbh.lookupPicture(picture);
        return System.currentTimeMillis() - t < TimeUnit.DAYS.toMillis(180);
    }

    private Date getDateTaken(File imageFile) {
        final ExifInterface exif;
        try {
            exif = new ExifInterface(imageFile.getAbsolutePath());
        } catch (IOException e) {
            Log.i(TAG, "Exception while getting EXIF time", e);
            return EPOCH;
        }

        final String dateString = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
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
    private boolean oldEnough(Picture picture) {
        final Calendar cutoff = Calendar.getInstance();
        cutoff.add(Calendar.YEAR, -1);

        final Calendar cal = Calendar.getInstance();
        cal.setTime(picture.dateTaken);
        return cal.before(cutoff);
    }
}
