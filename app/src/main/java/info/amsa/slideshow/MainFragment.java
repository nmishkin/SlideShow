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
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ImageView;

import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.exifinterface.media.ExifInterface;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;

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
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class MainFragment extends Fragment {
    private static final String TAG = "SlideShow";
    private static final ZoneId SYSTEM_TZ = ZoneId.systemDefault();
    private PictureHistoryDb dbh;
    private PrintStream logStream;


    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.options_menu, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case R.id.options_menu_item_settings:
                FragmentTransaction transaction = getParentFragmentManager().beginTransaction();
                transaction.replace(R.id.activity_main, new SettingsFragment());
                transaction.addToBackStack(null); // Optional: Adds the transaction to the back stack
                transaction.commit();
                return true;
        }

        return super.onOptionsItemSelected(item); // important line
    }
    public static class Picture {
        Picture(final File file, final Instant dateTaken) {
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
    private List<Picture> pictures;

    public MainFragment() {
        // Required empty public constructor
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    @Override
    public View onCreateView(final LayoutInflater inflater, final ViewGroup container,
                             final Bundle savedInstanceState) {

        final View view = inflater.inflate(R.layout.fragment_main, container, false);

        final Context context = requireContext();
        try {
            final FileOutputStream logFile = context.openFileOutput(TAG + ".log", MODE_APPEND);
            logStream = new PrintStream(logFile);
        } catch (final FileNotFoundException e) {
            throw new RuntimeException("Can't open log file", e);
        }

        logStream.format("%Tc Application starting\n", new Date());

        final PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(
                PowerManager.FULL_WAKE_LOCK |
                        PowerManager.ACQUIRE_CAUSES_WAKEUP, TAG + ":lock");

        imageView = view.findViewById(R.id.imageView);
        goFullScreen();

        setHasOptionsMenu(true);

        imageView.setOnClickListener(v -> {
            goFullScreen();
            stopPhotoLoader();
            startPhotoLoader(false);
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

        pictures = Stream.of(fileList)
                .filter(file -> file.isFile() && file.getPath().toLowerCase().endsWith(".jpg"))
                .map(file -> new Picture(file, getDateTaken(file)))
                .collect(Collectors.toList());

        if (pictures.isEmpty()) {
            Log.e(TAG, "No pictures found");
        }
        logStream.format("%Tc %d pictures found\n", new Date(), pictures.size());

        dbh = new PictureHistoryDb(context);

        screenOn();

        return view;
    }

    private void goFullScreen() {
        final FragmentActivity activity = getActivity();
        activity.getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        final ActionBar actionBar = ((AppCompatActivity) activity).getSupportActionBar();
        if (actionBar != null) {
//            actionBar.hide();
        }
    }

    private synchronized void screenOn() {
        Log.d(TAG, "Screen on");
        assert photoLoaderTask == null;

        wakeLock.acquire();
        startPhotoLoader(false);

        runAtNextHourMinute(23, 30, this::screenOff);
    }

    private synchronized void startPhotoLoader(final boolean pauseFirst) {
        photoLoaderTask = new PhotoLoaderTask().execute(pauseFirst);
    }

    private synchronized void stopPhotoLoader() {
        photoLoaderTask.cancel(true);
        photoLoaderTask = null;
    }

    private synchronized void screenOff() {
        Log.d(TAG, "Screen off");
        wakeLock.release();

        getActivity().getWindow().clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON |
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        );
        stopPhotoLoader();
        runAtNextHourMinute(6, 30, this::screenOn);
    }

    private void runAtNextHourMinute(final int hour, final int minute, final Runnable runnable) {
        final ZonedDateTime now = ZonedDateTime.now(SYSTEM_TZ);
        final ZonedDateTime targetTime = now.withHour(hour).withMinute(minute).withSecond(0).withNano(0);
        final ZonedDateTime adjTargetTime = now.isAfter(targetTime) ? targetTime.plusDays(1) : targetTime;
        final long postTime = adjTargetTime.toInstant().toEpochMilli();
        handler.postAtTime(runnable, postTime);
    }

    private class PhotoLoaderTask extends AsyncTask<Boolean, Void, Bitmap> {
        @Override
        protected Bitmap doInBackground(final Boolean... params) {
            assert params.length == 1;
            final boolean pauseFirst = params[0];

            if (pauseFirst) {
                try {
                    Thread.sleep(TimeUnit.DAYS.toMillis(1));
                } catch (final InterruptedException e) {
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

            final Bitmap bitmap = BitmapFactory.decodeFile(picturePath);
            final ZonedDateTime zonedDateTaken = picture.dateTaken.atZone(SYSTEM_TZ);
            return addTextToBitmap(bitmap, String.valueOf(zonedDateTaken.getYear()));
        }

        private Bitmap addTextToBitmap(final Bitmap bitmap, final String textToAdd) {
            // Create a mutable copy of the original bitmap
            final Bitmap bitmapWithText = bitmap.copy(Bitmap.Config.ARGB_8888, true);

            // Create a canvas to draw on the bitmap
            final Canvas canvas = new Canvas(bitmapWithText);

            // Create a Paint object for styling the text
            final Paint paint = new Paint();
            paint.setColor(Color.RED);  // Set the text color
            paint.setTextSize(20);      // Set the text size
            paint.setAntiAlias(true);   // Enable anti-aliasing for smoother text
            paint.setFakeBoldText(true);

            // Calculate the position to center the text on the bitmap
            final float x = bitmapWithText.getWidth() - paint.measureText(textToAdd) - 10;
            final float y = bitmapWithText.getHeight() - paint.getTextSize() + 15;

            // Draw the text on the bitmap
            canvas.drawText(textToAdd, x, y, paint);

            return bitmapWithText;
        }

        @Override
        protected void onPostExecute(final Bitmap bitmap) {
            if (bitmap != null) {
                imageView.setBackgroundColor(Color.BLACK);
                imageView.setImageBitmap(bitmap);
            }
            startPhotoLoader(true);
        }
    }

    private boolean displayedRecently(final Picture picture) {
        final long t = dbh.lookupPicture(picture);
        return System.currentTimeMillis() - t < TimeUnit.DAYS.toMillis(180);
    }

    private Instant getDateTaken(final File imageFile) {
        final ExifInterface exif;
        try {
            exif = new ExifInterface(imageFile.getAbsolutePath());
        } catch (final IOException e) {
            Log.i(TAG, "Exception while getting EXIF time", e);
            return EPOCH;
        }


        final String dateString = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL);
        if (dateString == null) {
            return EPOCH;
        }
        final Date date;
        final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy:MM:dd HH:mm:ss");
        try {
            date = dateFormat.parse(dateString);
        } catch (final ParseException e) {
            return EPOCH;
        }
        return date.toInstant();
    }
    private boolean oldEnough(final Picture picture) {
        return Duration.between(Instant.now(), picture.dateTaken).toDays() > 365;
    }
}