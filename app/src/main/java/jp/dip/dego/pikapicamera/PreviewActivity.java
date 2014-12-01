package jp.dip.dego.pikapicamera;

import android.app.ActionBar;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.hardware.Camera;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.support.v7.app.ActionBarActivity;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;


public class PreviewActivity extends ActionBarActivity {

    // ログ出力用
    private static final String TAG = "PikaPiCamera";

    // カメラ用
    private Camera mCamera;
    private byte[] mPreviewBuffer;

    // 描画用
    private boolean isPlaying = false;
    private boolean finished = false;
    private SurfaceView mPreviewSurface;
    private SurfaceView mResultSurface;
    private byte[] mResultPreview;
    private int[] mResultPixels;
    private Bitmap mResultBitmap;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ActionBarを非表示
        ActionBar actionBar = getActionBar();
        actionBar.hide();

        // FullScreen
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);

        // スリープさせない
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // レイアウトを設定
        setContentView(R.layout.activity_preview);

        // カメラ設定
        mCamera = null;

        // SurfaceViewの準備
        mPreviewSurface = (SurfaceView) findViewById(R.id.previewSurfaceView);
        mResultSurface = (SurfaceView) findViewById(R.id.resultSurfaceView);
        SurfaceHolder holder = mPreviewSurface.getHolder();
        holder.addCallback(mPreviewSurfaceListener);

    }

    @Override
    protected void onResume() {
        super.onResume();

        // Camera Open
        mCamera = Camera.open(0);
        mCamera.setPreviewCallbackWithBuffer(mPreviewCallback);

        // Bufferサイズを計算
        int width = mCamera.getParameters().getPreviewSize().width;
        int height = mCamera.getParameters().getPreviewSize().height;
        int format = mCamera.getParameters().getPreviewFormat();
        int bpp = ImageFormat.getBitsPerPixel(format);

        // 配列の領域を確保
        mResultPreview = new byte[width * height * bpp / 8];
        mPreviewBuffer = new byte[width * height * bpp / 8];
        mResultPixels = new int[width * height * bpp / 8];

        // Bitmap の作成
        mResultBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);

        // Camera にバッファを登録
        mCamera.addCallbackBuffer(mPreviewBuffer);
    }

    protected void onPause() {
        super.onPause();

        if (null != mCamera) {
            mCamera.setPreviewCallback(null);
            mCamera.stopPreview();
            mCamera.release();
            mCamera = null;
        }
    }

    /**
     * PreviewSurfaceListener
     */
    private SurfaceHolder.Callback mPreviewSurfaceListener = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            Log.d(TAG, "surfaceCreated()");
            try {
                if (null != mCamera) {
                    mCamera.setPreviewDisplay(holder);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            Log.d(TAG, "surfaceChanged()");
            if (null != mCamera) {
                mCamera.startPreview();
            }
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            Log.d(TAG, "surfaceDestroyed()");
        }
    };

    /**
     * ResultPreviewSurfaceListener
     */
    private SurfaceHolder.Callback mResultSurfaceListener = new SurfaceHolder.Callback() {
        @Override
        public void surfaceCreated(SurfaceHolder holder) {
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
        }
    };

    /**
     * PreviewCallback
     */
    private Camera.PreviewCallback mPreviewCallback = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(byte[] data, Camera camera) {
            // フォーマットが NV21 の時に処理を実施
            if (ImageFormat.NV21 == camera.getParameters().getPreviewFormat()) {
                int width = camera.getParameters().getPreviewSize().width;
                int height = camera.getParameters().getPreviewSize().height;

                if (isPlaying) {
                    // 勝ち取り処理
                    int offset_u = height * width;
                    int offset_v = offset_u + (height * width) / 4;
                    for (int j = 0; j < height; j = j + 2) {
                        for (int i = 0; i < width; i = i + 2) {
                            int p = j * width + i;
                            int y1 = (int) (0xff & data[p]);
                            int y2 = (int) (0xff & mResultPreview[p]);
                            if (y1 > y2) {
                                // y
                                mResultPreview[p] = data[p];
                                mResultPreview[p + 1] = data[p + 1];
                                mResultPreview[p + width] = data[p + width];
                                mResultPreview[p + width + 1] = data[p + width + 1];
                                // u
                                int p2 = (j / 2) * width / 2 + (i / 2);
                                mResultPreview[offset_u + p2] = data[offset_u + p2];
                                // v
                                mResultPreview[offset_v + p2] = data[offset_v + p2];
                            }
                        }
                    }

                    // NV21 to ARGB
                    decodeYUV420SP(mResultPixels, mResultPreview, width, height);
                } else if (finished) {
                    decodeYUV420SP(mResultPixels, mResultPreview, width, height);
                } else {
                    decodeYUV420SP(mResultPixels, data, width, height);
                }

                // Bitmap にピクセルデータをセット
                mResultBitmap.setPixels(mResultPixels, 0, width, 0, 0, width, height);

                // 描画
                int sWidth = mResultSurface.getWidth();
                int sHeight = mResultSurface.getHeight();
                Matrix matrix = new Matrix();
                float sx = (float) sWidth / width;
                float sy = (float) sHeight / height;
                matrix.postScale(sx, sy);
                Canvas canvas = mResultSurface.getHolder().lockCanvas();
                canvas.drawBitmap(mResultBitmap, matrix, new Paint());
                mResultSurface.getHolder().unlockCanvasAndPost(canvas);
            }

            camera.addCallbackBuffer(mPreviewBuffer);
        }

    };

    /**
     * decode NV21 Format to ARGB Format
     *
     * @param rgb
     * @param yuv420sp
     * @param width
     * @param height
     */
    static public void decodeYUV420SP(int[] rgb, byte[] yuv420sp, int width, int height) {
        final int frameSize = width * height;

        for (int j = 0, yp = 0; j < height; j++) {
            int uvp = frameSize + (j >> 1) * width, u = 0, v = 0;
            for (int i = 0; i < width; i++, yp++) {
                int y = (0xff & ((int) yuv420sp[yp])) - 16;
                if (y < 0) y = 0;
                if ((i & 1) == 0) {
                    v = (0xff & yuv420sp[uvp++]) - 128;
                    u = (0xff & yuv420sp[uvp++]) - 128;
                }

                int y1192 = 1192 * y;
                int r = (y1192 + 1634 * v);
                int g = (y1192 - 833 * v - 400 * u);
                int b = (y1192 + 2066 * u);

                if (r < 0) r = 0;
                else if (r > 262143) r = 262143;
                if (g < 0) g = 0;
                else if (g > 262143) g = 262143;
                if (b < 0) b = 0;
                else if (b > 262143) b = 262143;

                rgb[yp] = 0xff000000 | ((r << 6) & 0xff0000) | ((g >> 2) & 0xff00) | ((b >> 10) & 0xff);
            }
        }
    }

    /**
     * onStartButtonClicked
     */
    public void onStartButtonClicked(View view) {
        // プレビュー画面→PikaPika画面
        if (!isPlaying && !finished) {
            isPlaying = true;
            finished = false;

            // ボタンの表記を変更（撮影開始→完了）
            Button btn = (Button) findViewById(R.id.buttonStart);
            btn.setText(R.string.textButtonFinish);
        }
        // PikaPika画面→撮影画像画面
        else if (isPlaying && !finished) {
            isPlaying = false;
            finished = true;

            // ボタンの表記を変更（完了→もう一度）
            Button btn = (Button) findViewById(R.id.buttonStart);
            btn.setText(R.string.textButtonRestart);

            // 画像保存処理
            saveBitmapToSD();
        }
        // 撮影画像画面→プレビュー画面
        else if (!isPlaying && finished) {
            isPlaying = false;
            finished = false;

            // ボタンの表記を変更（もう一度→撮影開始）
            Button btn = (Button) findViewById(R.id.buttonStart);
            btn.setText(R.string.textButtonStart);

            // フレームを初期化
            for (int i = 0; i < mResultPreview.length * 2 / 3; i++) {
                mResultPreview[i] = 0;
            }
            for (int i = mResultPreview.length * 2 / 3; i < mResultPreview.length; i++) {
                mResultPreview[i] = 127;
            }
        }
    }

    /**
     * saveBitmapToSD
     */
    private boolean saveBitmapToSD() {
        try {
            // sdcardフォルダを指定
            File root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES);

            // 日付でファイル名を作成
            Date date = new Date();
            SimpleDateFormat fileName = new SimpleDateFormat("yyyyMMdd_HHmmss");

            // 保存処理開始
            FileOutputStream fos = null;
            File file = new File(root, fileName.format(date) + ".jpg");
            fos = new FileOutputStream(file);

            // jpegで保存
            mResultBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos);

            // 保存処理終了
            fos.close();

            // 保存したことをユーザに通知
            Toast.makeText(this, "画像を保存しました(" + file.getName() + ")", Toast.LENGTH_SHORT).show();

            // 他のアプリでも保存した画像が使えるように通知
            MediaScannerConnection.scanFile(this,
                    new String[]{file.toString()}, null,
                    new MediaScannerConnection.OnScanCompletedListener() {
                        public void onScanCompleted(String path, Uri uri) {
                            Log.i("ExternalStorage", "Scanned " + path + ":");
                            Log.i("ExternalStorage", "-> uri=" + uri);
                        }
                    }
            );

            return true;
        } catch (Exception e) {
            e.printStackTrace();
        }
        Toast.makeText(this, "画像の保存に失敗しました", Toast.LENGTH_SHORT).show();
        return false;
    }
}
