package info.amsa.slideshow;

import static android.content.Context.MODE_APPEND;
import static java.time.Instant.EPOCH;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.PowerManager;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.ImageView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.exifinterface.media.ExifInterface;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

public class MainFragment extends Fragment {
    private static final String TAG = "SlideShow";
    private static final ZoneId SYSTEM_TZ = ZoneId.systemDefault();
    private PictureHistoryDb dbh;
    private PrintStream logStream;

    public static class Picture {
        Picture(File file, Instant dateTaken) {
            this.file = file;
            this.dateTaken = dateTaken;
        }
        File file;
        Instant dateTaken;
    }

    private ImageView imageView;
    private final Random random = new Random();
    private final Handler handler = new Handler();
    private AsyncTask<Boolean, Void, Bitmap> photoLoaderTask = null;
    private PowerManager.WakeLock wakeLock;
    private final List<Picture> pictures = new ArrayList<>();

    public MainFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.fragment_main, container, false);

        Context context = requireContext();
        try {
            FileOutputStream logFile = context.openFileOutput(TAG + ".log", MODE_APPEND);
            logStream = new PrintStream(logFile);
        } catch (FileNotFoundException e) {
            throw new RuntimeException("Can't open log file", e);
        }

        logStream.format("%Tc Application starting\n", new Date());

        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK |
                        PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG + ":lock");

        imageView = view.findViewById(R.id.imageView);
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
            return view;
        }

        final File[] fileList = filesDir.listFiles();
        if (fileList == null) {
            Log.e(TAG, "Can't get list of pictures");
            return view;
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

        dbh = new PictureHistoryDb(context);

        screenOn();

        return view;


    }


    private void goFullScreen() {
        FragmentActivity activity = getActivity();
        activity.getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        ActionBar actionBar = ((AppCompatActivity) activity).getSupportActionBar();
        if (actionBar != null) {
            actionBar.hide();
        }
    }

    private synchronized void screenOn() {
        Log.d(TAG, "Screen on");
        assert photoLoaderTask == null;

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
        /*
        getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );

         */
        stopPhotoLoader();
        runAtNextHourMinute(6, 30, this::screenOn);
    }

    private void runAtNextHourMinute(int hour, int minute, Runnable runnable) {
        final ZonedDateTime now = ZonedDateTime.now(SYSTEM_TZ);
        final ZonedDateTime targetTime = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        final ZonedDateTime adjTargetTime = now.isAfter(targetTime) ? targetTime.plusDays(1) : targetTime;
        final long postTime = adjTargetTime.toInstant().toEpochMilli();
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
            ZonedDateTime zonedDateTaken = picture.dateTaken.atZone(SYSTEM_TZ);
            return addTextToBitmap(bitmap, String.valueOf(zonedDateTaken.getYear()));
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

    private Instant getDateTaken(File imageFile) {
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
        Date date;
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
        try {
            date = dateFormat.parse(dateString);
        } catch (ParseException e) {
            return EPOCH;
        }
        return date.toInstant();
    }
    private boolean oldEnough(Picture picture) {
        return Duration.between(Instant.now(), picture.dateTaken).toDays() > 365;
    }
}