package info.amsa.slideshow;

import android.Manifest;
import android.app.ActionBar;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.preference.PreferenceManager;
import android.support.annotation.NonNull;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.Display;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
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

public class MainActivity extends AppCompatActivity {
    private final static String TAG = "SlideShow";
    private final static Date EPOCH = new Date(0);
    private PictureHistoryDb dbh;

    public static class Picture {
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
    private android.support.v7.app.ActionBar actionBar;
    private SharedPreferences sharedPrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Toolbar myToolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(myToolbar);

        actionBar = getSupportActionBar();

        sharedPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        boolean fooPref = sharedPrefs.getBoolean("check_box_preference_1", false);

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
//                goFullScreen();
//                stopPhotoLoader();
//                startPhotoLoader(false);
            }
        });

        imageView.setOnSystemUiVisibilityChangeListener(new View.OnSystemUiVisibilityChangeListener() {
            public void onSystemUiVisibilityChange(int visibility) {
                if ((visibility & View.SYSTEM_UI_FLAG_FULLSCREEN) == 0) {
                    actionBar.show();
                } else {
                    actionBar.hide();
                }

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

        dbh = new PictureHistoryDb(getApplicationContext());

        screenOn();
    }

    private void goFullScreen() {
        imageView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE |
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_FULLSCREEN );
        actionBar.hide();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
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

            Picture picture = null;
            for (int i = 0; i < 50; i++) {
                final int index = Math.abs(random.nextInt()) % pictures.size();
                picture = pictures.get(index);
                if (!displayedRecently(picture) && oldEnough(picture)) {
                    break;
                }
            }

            final String picturePath = picture.file.getAbsolutePath();
            Log.d(TAG, picturePath);
            dbh.insertPicture(picture);
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
    private boolean oldEnough(Picture picture) {
        final Calendar cutoff = Calendar.getInstance();
        cutoff.add(Calendar.YEAR, -1);

        final Calendar cal = Calendar.getInstance();
        cal.setTime(picture.dateTaken);
        return cal.before(cutoff);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        boolean fooPref = sharedPrefs.getBoolean("check_box_preference_1", false);
        // Handle item selection
        switch (item.getItemId()) {
            case R.id.settings:
                Intent intent = new Intent(this, SettingsActivity.class);
                startActivity(intent);
                return true;
            default:
                return super.onOptionsItemSelected(item);
        }
    }
}
