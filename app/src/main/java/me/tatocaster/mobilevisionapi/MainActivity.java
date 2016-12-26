package me.tatocaster.mobilevisionapi;

import android.Manifest;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.SparseArray;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.google.android.gms.vision.Frame;
import com.google.android.gms.vision.face.Face;
import com.google.android.gms.vision.face.FaceDetector;
import com.google.android.gms.vision.face.Landmark;
import com.mlsdev.rximagepicker.RxImageConverters;
import com.mlsdev.rximagepicker.RxImagePicker;
import com.mlsdev.rximagepicker.Sources;
import com.tbruyelle.rxpermissions.RxPermissions;

import java.util.List;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.OnClick;
import butterknife.Unbinder;
import rx.Observable;
import rx.Subscription;
import rx.android.schedulers.AndroidSchedulers;
import rx.functions.Func1;
import rx.schedulers.Schedulers;
import rx.subscriptions.CompositeSubscription;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";

    @BindView(R.id.activity_main_selected_image)
    ImageView mSelectedImageView;

    private CompositeSubscription mSubscriptions;
    private Unbinder mUnbinder;

    private Bitmap mSourceBitmap;
    private Paint mRectanglePaint;
    private Paint mCirclePaint;
    private Canvas mCanvas;
    private Bitmap mHornBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mUnbinder = ButterKnife.bind(this);

        Subscription rxPermission = new RxPermissions(this)
                .request(Manifest.permission.READ_EXTERNAL_STORAGE)
                .subscribe(granted -> {
                    if (!granted)
                        Snackbar.make(findViewById(android.R.id.content), "Please give the permission", Snackbar.LENGTH_SHORT).show();
                });


        if (RxImagePicker.with(this).getActiveSubscription() != null) {
            RxImagePicker.with(this).getActiveSubscription().subscribe(this::onImagePicked);
        }

        mSubscriptions = new CompositeSubscription(rxPermission);

        mHornBitmap = BitmapFactory.decodeResource(getResources(), R.drawable.horn);
    }

    private void pickFromGallery() {
        Subscription rxImagePicker = RxImagePicker.with(this).requestImage(Sources.GALLERY)
                .flatMap(new Func1<Uri, Observable<Bitmap>>() {
                    @Override
                    public Observable<Bitmap> call(Uri uri) {
                        return RxImageConverters.uriToBitmap(MainActivity.this, uri);
                    }
                })
                .subscribe(bitmap -> {
                    mSourceBitmap = bitmap;
                    onImagePicked(bitmap);
                });
        mSubscriptions.add(rxImagePicker);
    }

    @OnClick(R.id.activity_main_action_button)
    public void onActionButtonClick(View view) {
        if (mSourceBitmap == null) {
            Snackbar.make(findViewById(android.R.id.content), "Choose image", Snackbar.LENGTH_SHORT).show();
            return;
        }

        Button b = (Button) view;

        disableButton(b);

        Subscription faceRecognitionSub = createBitmapBackground()
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(temporaryBitmap -> {
                    mCanvas = new Canvas(temporaryBitmap);
                    mCanvas.drawBitmap(mSourceBitmap, 0, 0, null);

                    faceRecognition()
                            .observeOn(AndroidSchedulers.mainThread())
                            .subscribe(faceSparseArray -> {
                                mSelectedImageView.setImageBitmap(temporaryBitmap);
                                enableButton(b);
                            }, throwable -> new AlertDialog.Builder(MainActivity.this)
                                    .setMessage(throwable.getMessage())
                                    .show());
                });
        mSubscriptions.add(faceRecognitionSub);
    }

    private Observable<Bitmap> createBitmapBackground() {
        return Observable.defer(() -> {
            Bitmap temporaryBitmap = Bitmap.createBitmap(mSourceBitmap.getWidth(), mSourceBitmap.getHeight(), Bitmap.Config.RGB_565);

            createRectangle();
            createCircle();

            return Observable.just(temporaryBitmap);
        }).subscribeOn(Schedulers.io());
    }

    private Observable<String> faceRecognition() {
        return Observable.defer(() -> {
            FaceDetector faceDetector = new FaceDetector.Builder(MainActivity.this)
                    .setTrackingEnabled(false)
                    .setLandmarkType(FaceDetector.ALL_LANDMARKS)
                    .build();

            if (faceDetector.isOperational())
                Observable.error(new Exception("Face Detector Not Ready"));

            Frame frame = new Frame.Builder().setBitmap(mSourceBitmap).build();
            SparseArray<Face> faceSparseArray = faceDetector.detect(frame);

            drawFaces(faceSparseArray);
            faceDetector.release();
            return Observable.just("Started");
        }).subscribeOn(Schedulers.io());
    }

    private void enableButton(Button b) {
        b.setText("ACTION");
        b.setEnabled(true);
        b.setClickable(true);
    }

    private void disableButton(Button b) {
        b.setText("Processing");
        b.setEnabled(false);
        b.setClickable(false);
    }

    private void drawFaces(SparseArray<Face> faceSparseArray) {
        for (int i = 0; i < faceSparseArray.size(); i++) {

            Face face = faceSparseArray.valueAt(i);

            float left = face.getPosition().x;
            float top = face.getPosition().y;
            float right = left + face.getWidth();
            float bottom = top + face.getHeight();
            float cornerRadius = 2.0f;

            RectF rectF = new RectF(left, top, right, bottom);
            mCanvas.drawRoundRect(rectF, cornerRadius, cornerRadius, mRectanglePaint);

            drawLandmarksOnFace(face.getLandmarks());
        }
    }

    private void drawLandmarksOnFace(List<Landmark> landmarks) {
        float hornLevelY = 0;
        float radius = 10.0f;
        for (Landmark landmark : landmarks) {
            if (landmark.getType() == Landmark.LEFT_EYE || landmark.getType() == Landmark.RIGHT_EYE) {
                float x = landmark.getPosition().x;
                float y = hornLevelY = landmark.getPosition().y;
                mCanvas.drawCircle(x, y, radius, mCirclePaint);
            }
            if (landmark.getType() == Landmark.NOSE_BASE) {
                mCanvas.drawBitmap(mHornBitmap, landmark.getPosition().x - mHornBitmap.getWidth() / 3,
                        hornLevelY - mHornBitmap.getHeight() - 100, null);
            }
        }
    }

    private void createRectangle() {
        mRectanglePaint = new Paint();
        mRectanglePaint.setStrokeWidth(8);
        mRectanglePaint.setColor(Color.MAGENTA);
        mRectanglePaint.setStyle(Paint.Style.STROKE);
    }

    private void createCircle() {
        mCirclePaint = new Paint();
        mCirclePaint.setStrokeWidth(3);
        mCirclePaint.setColor(Color.RED);
    }

    @OnClick(R.id.activity_main_choose_image)
    public void onChooseImageClicked(View view) {
        pickFromGallery();
    }

    private void onImagePicked(Object result) {
        if (result instanceof Bitmap) {
            mSelectedImageView.setImageBitmap((Bitmap) result);
        } else {
            Glide.with(MainActivity.this)
                    .load(result)
                    .crossFade()
                    .into(mSelectedImageView);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mUnbinder.unbind();
        mSubscriptions.clear();
    }
}
