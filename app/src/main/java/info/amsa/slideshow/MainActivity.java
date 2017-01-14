package info.amsa.slideshow;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.AsyncTask;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.ImageView;

import com.google.android.gms.appindexing.Action;
import com.google.android.gms.appindexing.AppIndex;
import com.google.android.gms.appindexing.Thing;
import com.google.android.gms.common.api.GoogleApiClient;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MainActivity extends AppCompatActivity {
    private ImageView imageView;
    private File filesDir;
    private Random random = new Random();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        imageView = (ImageView) findViewById(R.id.imageView);

        filesDir = getFilesDir();
        if (!filesDir.exists()) {
            return;
        }

        new PhotoLoaderTask().execute();

    }

    private class PhotoLoaderTask extends AsyncTask<Void, Void, Bitmap> {


        @Override
        protected Bitmap doInBackground(Void[] params) {
            List<File> imageFiles = new ArrayList<>();
            for (File imageFile : filesDir.listFiles()) {
                if (!imageFile.isFile() || !imageFile.getPath().toLowerCase().endsWith(".jpg")) {
                    continue;
                }
                imageFiles.add(imageFile);
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
            imageView.setImageBitmap(bm);
            new PhotoLoaderTask().execute();
        }
    }
}
