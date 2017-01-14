package info.amsa.slideshow;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.support.v4.graphics.BitmapCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.widget.ImageView;

import java.io.File;

public class MainActivity extends AppCompatActivity {
    ImageView imageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        imageView = (ImageView) findViewById(R.id.imageView);

        // File imageFile = new File("/storage/emulated/0/Geoff & Eric.jpg");
        File filesDir = getFilesDir();
        if (filesDir.exists()) {
            for (File imageFile : filesDir.listFiles()) {
                Bitmap bm = BitmapFactory.decodeFile(imageFile.getAbsolutePath());

                imageView.setImageBitmap(bm);
                try {
                    Thread.sleep(1500);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }


    }
}
