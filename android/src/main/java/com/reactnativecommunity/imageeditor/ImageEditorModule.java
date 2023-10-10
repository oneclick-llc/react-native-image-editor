/**
 * Copyright (c) Facebook, Inc. and its affiliates.
 *
 * This source code is licensed under the MIT license found in the
 * LICENSE file in the root directory of this source tree.
 */

package com.reactnativecommunity.imageeditor;

import javax.annotation.Nullable;

import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;

import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.Log;

import com.facebook.common.logging.FLog;
import com.facebook.react.bridge.GuardedAsyncTask;
import com.facebook.react.bridge.Promise;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.JSApplicationIllegalArgumentException;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableMap;
import com.facebook.infer.annotation.Assertions;
import com.facebook.react.common.ReactConstants;

/**
 * Native module that provides image cropping and dimensions-getting functionality.
 */
public class ImageEditorModule extends ReactContextBaseJavaModule {

  protected static final String NAME = "RNCImageEditor";

  private static final List<String> LOCAL_URI_PREFIXES = Arrays.asList(
          ContentResolver.SCHEME_FILE,
          ContentResolver.SCHEME_CONTENT,
          ContentResolver.SCHEME_ANDROID_RESOURCE
  );

  private static final String TEMP_FILE_PREFIX = "ReactNative_cropped_image_";

  /** Compress quality of the output file. 100 is ignored */
  private static final int COMPRESS_QUALITY = 100;

  @SuppressLint("InlinedApi") private static final String[] EXIF_ATTRIBUTES = new String[] {
    ExifInterface.TAG_APERTURE,
    ExifInterface.TAG_DATETIME,
    ExifInterface.TAG_DATETIME_DIGITIZED,
    ExifInterface.TAG_EXPOSURE_TIME,
    ExifInterface.TAG_FLASH,
    ExifInterface.TAG_FOCAL_LENGTH,
    ExifInterface.TAG_GPS_ALTITUDE,
    ExifInterface.TAG_GPS_ALTITUDE_REF,
    ExifInterface.TAG_GPS_DATESTAMP,
    ExifInterface.TAG_GPS_LATITUDE,
    ExifInterface.TAG_GPS_LATITUDE_REF,
    ExifInterface.TAG_GPS_LONGITUDE,
    ExifInterface.TAG_GPS_LONGITUDE_REF,
    ExifInterface.TAG_GPS_PROCESSING_METHOD,
    ExifInterface.TAG_GPS_TIMESTAMP,
    ExifInterface.TAG_IMAGE_LENGTH,
    ExifInterface.TAG_IMAGE_WIDTH,
    ExifInterface.TAG_ISO,
    ExifInterface.TAG_MAKE,
    ExifInterface.TAG_MODEL,
    ExifInterface.TAG_ORIENTATION,
    ExifInterface.TAG_SUBSEC_TIME,
    ExifInterface.TAG_SUBSEC_TIME_DIG,
    ExifInterface.TAG_SUBSEC_TIME_ORIG,
    ExifInterface.TAG_WHITE_BALANCE
  };

  public ImageEditorModule(ReactApplicationContext reactContext) {
    super(reactContext);
    new CleanTask(getReactApplicationContext()).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public Map<String, Object> getConstants() {
    return Collections.emptyMap();
  }

  @Override
  public void onCatalystInstanceDestroy() {
    new CleanTask(getReactApplicationContext()).executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  /**
   * Asynchronous task that cleans up cache dirs (internal and, if available, external) of cropped
   * image files. This is run when the catalyst instance is being destroyed (i.e. app is shutting
   * down) and when the module is instantiated, to handle the case where the app crashed.
   */
  private static class CleanTask extends GuardedAsyncTask<Void, Void> {
    private final Context mContext;

    private CleanTask(ReactContext context) {
      super(context);
      mContext = context;
    }

    @Override
    protected void doInBackgroundGuarded(Void... params) {
      cleanDirectory(mContext.getCacheDir());
      File externalCacheDir = mContext.getExternalCacheDir();
      if (externalCacheDir != null) {
        cleanDirectory(externalCacheDir);
      }
    }

    private void cleanDirectory(File directory) {
      File[] toDelete = directory.listFiles(
          new FilenameFilter() {
            @Override
            public boolean accept(File dir, String filename) {
              return filename.startsWith(TEMP_FILE_PREFIX);
            }
          });
      if (toDelete != null) {
        for (File file: toDelete) {
          file.delete();
        }
      }
    }
  }

  @ReactMethod
  public void getImageDimensions(String uri, Promise jsPromise) throws IOException {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;

    try (InputStream imageInputStream = CropTask.openBitmapInputStream(
            uri,
            getReactApplicationContext()
    )) {
      // what if encode could not retrieve data?
      BitmapFactory.decodeStream(imageInputStream, null, options);

      WritableMap result = Arguments.createMap();
      result.putInt("height", options.outHeight);
      result.putInt("width", options.outWidth);
      jsPromise.resolve(result);
    } catch (java.io.IOException error) {
      jsPromise.reject("ImageEditor.getImageDimensions Bitmap decode error: ", error);
    }
  }

  /**
   * Crop an image. If all goes well, the promise will be resolved with the file:// URI of
   * the new image as the only argument. This is a temporary file - consider using
   * CameraRollManager.saveImageWithTag to save it in the gallery.
   *
   * @param uri the URI of the image to crop
   * @param options crop parameters specified as {@code {offset: {x, y}, size: {width, height}}}.
   *        All units are in pixels (not DPs).
   * @param promise Promise to be resolved when the image has been cropped; the only argument that
   *        is passed to this is the file:// URI of the new image
   */
  @ReactMethod
  public void cropImage(
      String uri,
      ReadableMap options,
      Promise promise) {

    ReadableMap offset = options.hasKey("offset") ? options.getMap("offset") : null;
    ReadableMap size = options.hasKey("size") ? options.getMap("size") : null;
    if (offset == null || size == null ||
        !offset.hasKey("x") || !offset.hasKey("y") ||
        !size.hasKey("width") || !size.hasKey("height")) {
      throw new JSApplicationIllegalArgumentException("Please specify offset and size");
    }
    if (uri == null || uri.isEmpty()) {
      throw new JSApplicationIllegalArgumentException("Please specify a URI");
    }

    CropTask cropTask = new CropTask(
        getReactApplicationContext(),
        uri,
        (int) offset.getDouble("x"),
        (int) offset.getDouble("y"),
        (int) size.getDouble("width"),
        (int) size.getDouble("height"),
        promise);

    cropTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
  }

  private static class CropTask extends GuardedAsyncTask<Void, Void> {
    final Context mContext;
    final String mUri;
    final int mX;
    final int mY;
    final int mWidth;
    final int mHeight;
    final Promise mPromise;

    private CropTask(
        ReactContext context,
        String uri,
        int x,
        int y,
        int width,
        int height,
        Promise promise) {
      super(context);
      Log.d("ImageEditor", "CropTask CONSTRUCTOR");
      if (x < 0) {
        Log.d("ImageEditor", String.format("Invalid crop rectangle x (%d), replacing with 0", x));
        x = 0;
      }
      if (y < 0) {
        Log.d("ImageEditor", String.format("Invalid crop rectangle y (%d), replacing with 0", y));
        y = 0;
      }

      if (x < 0 || y < 0 || width <= 0 || height <= 0) {
        throw new JSApplicationIllegalArgumentException(String.format(
            "Invalid crop rectangle: [%d, %d, %d, %d]", x, y, width, height));
      }
      mContext = context;
      mUri = uri;
      mX = x;
      mY = y;
      mWidth = width;
      mHeight = height;
      mPromise = promise;
      Log.d("ImageEditor", "constructed CropTask: { mUri: " + mUri
              + ", mX: " + mX + ", mY: " + mY
              + ", mWidth: " + mWidth + ", mHeight: " + mHeight + " } ");
    }

    private static InputStream openBitmapInputStream(
            String uri,
            Context reactContext
    ) throws IOException {
      InputStream stream;
      if (isLocalUri(uri)) {
        stream = reactContext.getContentResolver().openInputStream(Uri.parse(uri));
      } else {
        URLConnection connection = new URL(uri).openConnection();
        stream = connection.getInputStream();
      }
      if (stream == null) {
        throw new IOException("Cannot open bitmap: " + uri);
      }
      return stream;
    }

    private static class ExifTransformationInfo {
      boolean hasTransformations, isRotationMultipleOf90;
      int rotationDegree, scaleX, scaleY;
      public ExifTransformationInfo(int _rotationDegree, int _scaleX, int _scaleY) {
        hasTransformations = _rotationDegree != 0 || _scaleX != 1 || _scaleY != 1;
        isRotationMultipleOf90 = _rotationDegree > 0 && _rotationDegree % 90 == 0;
        rotationDegree = _rotationDegree;
        scaleX = _scaleX;
        scaleY = _scaleY;
        Log.d("ImageEditor", "Constructed ExifTransformationInfo: " + this.getString());
      }

      // can't simply override toString (requires annotation import)
      private String getString() {
        return "{ hasTransformations: " + hasTransformations
                + ", isRotationMultipleOf90: " + isRotationMultipleOf90
                + ", rotationDegree: " + rotationDegree
                + ", scaleX: " + scaleX + ", scaleY: " + scaleY + " }";
      }
    }

    private ExifTransformationInfo getFileExifTransformationInfo(String filePath) throws IOException {
      try {
        InputStream inputStream = mContext.getContentResolver().openInputStream(Uri.parse(filePath));

        ExifInterface exifInterface = null;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
          exifInterface = new ExifInterface(inputStream);
        }
        assert exifInterface != null : "ImageEditor.getFileExifTransformationInfo exifInterface is null";
        int exifOrientation = exifInterface.getAttributeInt(
          ExifInterface.TAG_ORIENTATION,
          ExifInterface.ORIENTATION_UNDEFINED
        );
        Log.d("ImageEditor", ".getFileExifTransformationInfo, exifOrientation:" + exifOrientation);
        switch (exifOrientation) {
          case ExifInterface.ORIENTATION_ROTATE_90: {
            return new ExifTransformationInfo(90, 1, 1);
          }
          case ExifInterface.ORIENTATION_ROTATE_270: {
            return new ExifTransformationInfo(270, 1, 1);
          }
          case ExifInterface.ORIENTATION_TRANSPOSE:
          case ExifInterface.ORIENTATION_TRANSVERSE: {
            return new ExifTransformationInfo(270, -1, -1);
          }
          case ExifInterface.ORIENTATION_ROTATE_180: {
            return new ExifTransformationInfo(180, 1, 1);
          }
          case ExifInterface.ORIENTATION_FLIP_VERTICAL: {
            return new ExifTransformationInfo(0, 1, -1);
          }
          default: {
            return new ExifTransformationInfo(0, 1, 1);
          }
        }
      } catch (IOException error) {
        Log.e("ImageEditor", ".getRotateDegreeFromExif error:" + error);
        error.printStackTrace();
        return null;
      }
    }

    @Override
    protected void doInBackgroundGuarded(Void... params) {
      try {
        BitmapFactory.Options outOptions = new BitmapFactory.Options();

        Bitmap cropped = crop(outOptions);

        String mimeType = outOptions.outMimeType;
        if (mimeType == null || mimeType.isEmpty()) {
          throw new IOException("Could not determine MIME type");
        }

        File tempFile = createTempFile(mContext, mimeType);
        writeCompressedBitmapToFile(cropped, mimeType, tempFile);
        cropped.recycle();
        cropped = null;

        if (mimeType.equals("image/jpeg")) {
          copyExif(mContext, Uri.parse(mUri), tempFile);
        }

        mPromise.resolve(Uri.fromFile(tempFile).toString());
      } catch (Exception e) {
        mPromise.reject(e);
      }
    }

    /**
     * Crop the rectangle given by {@code mX, mY, mWidth, mHeight} within the source bitmap
     * @param outOptions Bitmap options, useful to determine {@code outMimeType}.
     */
    private Bitmap crop(
        BitmapFactory.Options outOptions)
        throws IOException {
      Assertions.assertNotNull(outOptions);

      // Loading large bitmaps efficiently:
      // http://developer.android.com/training/displaying-bitmaps/load-bitmap.html
      // This can use significantly less memory than decoding the full-resolution bitmap
      final int downscaleRatio = getDownscaleRatio(mWidth, mHeight);
      outOptions.inSampleSize = downscaleRatio;

      InputStream inputStream = openBitmapInputStream(mUri, mContext);

      Bitmap bitmap;
      try {
        bitmap = BitmapFactory.decodeStream(inputStream, null, outOptions);
        if (bitmap == null) {
          throw new IOException("Cannot decode bitmap: " + mUri);
        }
      } finally {
        inputStream.close();
      }

      ExifTransformationInfo fileExifTransformationInfo = getFileExifTransformationInfo(mUri);
      /* Rotation  (which may be caused be Exif Rotation data) Matrix */
      Matrix rotationMatrix = null;
      if (fileExifTransformationInfo != null && fileExifTransformationInfo.hasTransformations) {
        rotationMatrix = new Matrix();
        rotationMatrix.postRotate(fileExifTransformationInfo.rotationDegree);
        rotationMatrix.postScale(
                fileExifTransformationInfo.scaleX,
                fileExifTransformationInfo.scaleY
        );
      }

      if (rotationMatrix != null) {
        Log.d("ImageEditor", "rotating bitmap");
        bitmap = Bitmap.createBitmap(
                bitmap,
                0,
                0,
                bitmap.getWidth(),
                bitmap.getHeight(),
                rotationMatrix,
                true
        );
      } else {
        Log.d("ImageEditor", "there is no need to rotate bitmap");
      }

      int cropX = mX / downscaleRatio;
      int cropY = mY / downscaleRatio;
      int cropWidth = mWidth / downscaleRatio;
      int cropHeight = mHeight / downscaleRatio;

      Log.d("ImageEditor",
            "crop data: { cropX: " + cropX
                    + ", cropY: " + cropY
                    + ", cropWidth: " + cropWidth
                    + ", cropHeight: " + cropHeight
                    + ", bitmapHeight: " + bitmap.getHeight()
                    + ", bitmapWidth: " + bitmap.getWidth() + " }"
      );

      if (
          cropX == 0 &&
          cropY == 0 &&
          cropWidth == bitmap.getWidth() &&
          cropHeight == bitmap.getHeight()
      ) {
        Log.d("ImageEditor", "bitmap crop is not required, RETURNING not cropped bitmap");
        return bitmap;
      }

      bitmap = Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight);
      Log.d("ImageEditor", "CROPPED bitmap, returning");
      return bitmap;
    }
  }
  // Utils

  private static void copyExif(Context context, Uri oldImage, File newFile) throws IOException {
    File oldFile = getFileFromUri(context, oldImage);
    if (oldFile == null) {
      FLog.w(ReactConstants.TAG, "Couldn't get real path for uri: " + oldImage);
      return;
    }

    ExifInterface oldExif = new ExifInterface(oldFile.getAbsolutePath());
    ExifInterface newExif = new ExifInterface(newFile.getAbsolutePath());
    for (String attribute : EXIF_ATTRIBUTES) {
      String value = oldExif.getAttribute(attribute);
      if (value != null) {
        newExif.setAttribute(attribute, value);
      }
    }
    newExif.setAttribute(ExifInterface.TAG_ORIENTATION, "1");
    newExif.saveAttributes();
  }

  private static @Nullable File getFileFromUri(Context context, Uri uri) {
    if (uri.getScheme().equals("file")) {
      return new File(uri.getPath());
    } else if (uri.getScheme().equals("content")) {
      Cursor cursor = context.getContentResolver()
        .query(uri, new String[] { MediaStore.MediaColumns.DATA }, null, null, null);
      if (cursor != null) {
        try {
          if (cursor.moveToFirst()) {
            String path = cursor.getString(0);
            if (!TextUtils.isEmpty(path)) {
              return new File(path);
            }
          }
        } finally {
          cursor.close();
        }
      }
    }

    return null;
  }

  private static boolean isLocalUri(String uri) {
    for (String localPrefix : LOCAL_URI_PREFIXES) {
      if (uri.startsWith(localPrefix)) {
        return true;
      }
    }
    return false;
  }

  private static String getFileExtensionForType(@Nullable String mimeType) {
    if ("image/png".equals(mimeType)) {
      return ".png";
    }
    if ("image/webp".equals(mimeType)) {
      return ".webp";
    }
    return ".jpg";
  }

  private static Bitmap.CompressFormat getCompressFormatForType(String type) {
    if ("image/png".equals(type)) {
      return Bitmap.CompressFormat.PNG;
    }
    if ("image/webp".equals(type)) {
      return Bitmap.CompressFormat.WEBP;
    }
    return Bitmap.CompressFormat.JPEG;
  }

  private static void writeCompressedBitmapToFile(Bitmap cropped, String mimeType, File tempFile)
      throws IOException {
    OutputStream out = new FileOutputStream(tempFile);
    try {
      cropped.compress(getCompressFormatForType(mimeType), COMPRESS_QUALITY, out);
    } finally {
      out.close();
    }
  }

  /**
   * Create a temporary file in the cache directory on either internal or external storage,
   * whichever is available and has more free space.
   *
   * @param mimeType the MIME type of the file to create (image/*)
   */
  private static File createTempFile(Context context, @Nullable String mimeType)
      throws IOException {
    File externalCacheDir = context.getExternalCacheDir();
    File internalCacheDir = context.getCacheDir();
    File cacheDir;
    if (externalCacheDir == null && internalCacheDir == null) {
      throw new IOException("No cache directory available");
    }
    if (externalCacheDir == null) {
      cacheDir = internalCacheDir;
    }
    else if (internalCacheDir == null) {
      cacheDir = externalCacheDir;
    } else {
      cacheDir = externalCacheDir.getFreeSpace() > internalCacheDir.getFreeSpace() ?
          externalCacheDir : internalCacheDir;
    }
    return File.createTempFile(TEMP_FILE_PREFIX, getFileExtensionForType(mimeType), cacheDir);
  }

  // in pixels
  private static final int IMAGE_GREATEST_SIZE_MAX_LENGTH = 1280;
  // https://developer.android.com/topic/performance/graphics/load-bitmap#java
  private static int getDownscaleRatio(int width, int height) {
    Log.d("ImageEditor", "getDownscaleRatio(width: " + width + ", height: " + height);
    final String greatestSide = height >= width ? "height" : "width";
    int downscaleRatio = 1;

    // Calculate the largest downscaleRatio value that is a power of 2 and keeps both
    // height and width larger than the IMAGE_GREATEST_SIZE_MAX_LENGTH.
    if (greatestSide.equals("height")) {
      final int halfHeight = height / 2;
      while ((halfHeight / downscaleRatio) >= IMAGE_GREATEST_SIZE_MAX_LENGTH) {
        downscaleRatio *= 2;
      }
    }
    if (greatestSide.equals("width")) {
      final int halfWidth = width / 2;
      while ((halfWidth / downscaleRatio) >= IMAGE_GREATEST_SIZE_MAX_LENGTH) {
        downscaleRatio *= 2;
      }
    }

    Log.d("ImageEditor", "getDownscaleRatio result: " + downscaleRatio);
    return downscaleRatio;
  }
}
