package com.powerdrinkdetection.kosin.powerdrinkdetection;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.renderscript.ScriptGroup;
import android.support.v4.content.FileProvider;
import android.support.v7.app.ActionBar;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.os.Handler;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.w3c.dom.Text;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private final int CAMERA_REQUEST = 1;
    private final int PHOTO_REQUEST = 2;
    private final float MINIMUM_CONFIDENCE = 0.1f;
    private TextView outputText;
    private ImageView imageView;
    private Button takePhotoButton;
    private Button choosePhotoButton;
    private ProgressBar progressBar;
    private Canvas canvas;
    private Uri photoURI;

    private static final int INPUT_SIZE = 1200;

    private static final String MODEL_FILE = "file:///android_asset/frozen_inference_graph.pb";
    private static final String LABEL_FILE = "file:///android_asset/power_drink.txt";

    private Classifier classifier;
    private Executor executor = Executors.newSingleThreadExecutor();

    private final View.OnClickListener takePhotoListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            try {
                dispatchTakePictureIntent();
                outputText.setText("");
            }catch (Exception e){
                outputText.setText(e.toString());
            }
        }
    };

    private void dispatchTakePictureIntent() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        // Ensure that there's a camera activity to handle the intent
        if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
            // Create the File where the photo should go
            File photoFile = createImageFile();
            // Continue only if the File was successfully created
            if (photoFile != null) {
                photoURI = FileProvider.getUriForFile(this,
                        "com.powerdrinkdetection.android.fileprovider",
                        photoFile);
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoURI);
                startActivityForResult(takePictureIntent, CAMERA_REQUEST);
            }
        }
    }

    private final View.OnClickListener choosePhotoListener = new View.OnClickListener() {
        @Override
        public void onClick(View view) {
            Intent choosePhotointent = new Intent();
            choosePhotointent.setType("image/*");
            choosePhotointent.setAction(Intent.ACTION_GET_CONTENT);
            startActivityForResult(choosePhotointent, PHOTO_REQUEST);
        }
    };

    private File createImageFile(){
        // Create an image file name
        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String imageFileName = "JPEG_" + timeStamp + "_";
        File storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);

        File image = null;
        try {
            image = File.createTempFile(imageFileName,".jpg",storageDir);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return image;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_camera);
        imageView = (ImageView)findViewById(R.id.imageView);
        takePhotoButton = (Button)findViewById(R.id.take_picture);
        choosePhotoButton = (Button)findViewById(R.id.choose_picture);
        progressBar = (ProgressBar)findViewById(R.id.progressBar);
        takePhotoButton.setOnClickListener(takePhotoListener);
        choosePhotoButton.setOnClickListener(choosePhotoListener);
        outputText = (TextView)findViewById(R.id.outputText);
        initTensorFlowAndLoadModel();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data){
        switch(requestCode){
            case CAMERA_REQUEST:
                if (resultCode == Activity.RESULT_OK) {
                    try {
                        Bitmap photo = MediaStore.Images.Media.getBitmap(getContentResolver(),photoURI);
                        try {
                            photo = rotateImageIfRequired(this,photo,photoURI);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        ProcessImage(photo);
                    } catch (Exception e) {
                        outputText.setText(e.toString());
                        e.printStackTrace();
                    }
                }
                break;
            case PHOTO_REQUEST:
                if (resultCode == Activity.RESULT_OK) {
                    Uri pickedImage = data.getData();
                    try {
                        Bitmap photo = MediaStore.Images.Media.getBitmap(this.getContentResolver(), pickedImage);
                        ProcessImage(photo);
                    } catch (Exception e) {
                        outputText.setText(e.toString());
                        e.printStackTrace();
                    }
                }
                break;
        }
    }


    void ProcessImage(Bitmap picture){
        Bitmap workingBitmap =  picture.copy(Bitmap.Config.ARGB_8888, true);
        imageView.setImageBitmap(workingBitmap);
        canvas = new Canvas(workingBitmap);
        new ProcessImage().execute(workingBitmap);
    }

    private static Bitmap rotateImageIfRequired(Context context, Bitmap img, Uri selectedImage) throws IOException {

        InputStream input = context.getContentResolver().openInputStream(selectedImage);
        ExifInterface ei;
        if (Build.VERSION.SDK_INT > 23)
            ei = new ExifInterface(input);
        else
            ei = new ExifInterface(selectedImage.getPath());

        int orientation = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);

        switch (orientation) {
            case ExifInterface.ORIENTATION_ROTATE_90:
                return rotateImage(img, 90);
            case ExifInterface.ORIENTATION_ROTATE_180:
                return rotateImage(img, 180);
            case ExifInterface.ORIENTATION_ROTATE_270:
                return rotateImage(img, 270);
            default:
                return img;
        }
    }

    private static Bitmap rotateImage(Bitmap img, int degree) {
        Matrix matrix = new Matrix();
        matrix.postRotate(degree);
        Bitmap rotatedImg = Bitmap.createBitmap(img, 0, 0, img.getWidth(), img.getHeight(), matrix, true);
        img.recycle();
        return rotatedImg;
    }

    private class ProcessImage extends AsyncTask<Bitmap,Void,List<Classifier.Recognition>>{

        @Override
        protected void onPreExecute(){
            progressBar.setVisibility(View.VISIBLE);
        }

        @Override
        protected List<Classifier.Recognition> doInBackground(Bitmap... bitmaps) {
            return classifier.recognizeImage(bitmaps[0]);
        }

        @Override
        protected void onPostExecute(List<Classifier.Recognition> results){
            final Paint borderPaint = new Paint();
            borderPaint.setColor(Color.RED);
            borderPaint.setStyle(Paint.Style.STROKE);
            borderPaint.setStrokeWidth(2.0f);

            final Paint textPaint = new Paint();
            textPaint.setColor(Color.RED);
            textPaint.setStyle(Paint.Style.FILL);
            textPaint.setStrokeWidth(1.0f);
            textPaint.setTextSize(30);

            for(final Classifier.Recognition result:results){
                if(result.getConfidence()>=MINIMUM_CONFIDENCE) {
                    canvas.drawRect(result.getLocation(), borderPaint);
                    canvas.drawText(result.getTitle() + " " + (int)(result.getConfidence()*100) +"%",result.getLocation().left,result.getLocation().top,textPaint);
                    //canvas.drawText(result.getTitle(),result.getLocation().left,result.getLocation().top,textPaint);
                }
            }

            progressBar.setVisibility(View.INVISIBLE);

        }
    }

    private void initTensorFlowAndLoadModel() {
        executor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    classifier = TensorFlowImageClassifier.create(
                            getAssets(),
                            MODEL_FILE,
                            LABEL_FILE,
                            INPUT_SIZE
                    );
                    makeButtonVisible();
                } catch (final Exception e) {
                    throw new RuntimeException("Error initializing TensorFlow!", e);
                }
            }
        });
    }

    private void makeButtonVisible() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                takePhotoButton.setVisibility(View.VISIBLE);
            }
        });
    }
}
