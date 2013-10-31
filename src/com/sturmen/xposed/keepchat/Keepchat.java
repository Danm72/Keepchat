package com.sturmen.xposed.keepchat;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.lang.reflect.InvocationTargetException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static de.robv.android.xposed.XposedHelpers.callMethod;
import static de.robv.android.xposed.XposedHelpers.findAndHookMethod;

public class Keepchat implements IXposedHookLoadPackage {
    /**
     * The getVideoUri() hook unfortunately doesn't provide a context for
     * displaying a toast or calling the media scanner to show up the newly
     * added media in the gallery. So after saving the video, we store the file
     * path into the private videoPath variable which we can in turn access in
     * the showVideo() hook (that gives us a context).
     */
    private static final String PACKAGE_NAME = Keepchat.class.getPackage().getName();

    // We cannot access the xml resources for the keepchat package, so we define
    // the preference codes here...
    private static final int SAVE_NEVER = 0;
    private static final int SAVE_AUTO = 1;
    private static final int SAVE_ASK = 2;

    /** indicator for the type of snap (image/video) */
    private boolean isImageSnap;

    /**
     * This string helps passing the path to the image or video saved in the
     * getImageBitmap() or getVideoUri() hooks to the corresponding showImage()
     * and showVideo() hooks.
     */
    private String mediaPath;

    /**
     * This boolean is used to check if the image or video is already saved or
     * not. It is used for Stories as they will be viewed again and again.
     */
    private boolean isSaved;

    /**
     * Member to pass a context to hooks where a context is needed, but non
     * available from within the hooked method
     */
    private Context context;

    // Load the preferences for Keepchat
    XSharedPreferences savePrefs = new XSharedPreferences(PACKAGE_NAME);
    final String saveLocation = savePrefs.getString("pref_saveLocation", "");
    final int imageSavingMode = Integer.parseInt(savePrefs.getString("pref_imageSaving", Integer.toString(SAVE_AUTO)));
    final int videoSavingMode = Integer.parseInt(savePrefs.getString("pref_videoSaving", Integer.toString(SAVE_AUTO)));
    final int toastMode = Integer.parseInt(savePrefs.getString("pref_toast", "-1"));

    public void handleLoadPackage(LoadPackageParam lpparam) throws Throwable {
        if (!lpparam.packageName.equals("com.snapchat.android"))
            return;
        else
            XposedBridge.log("Keepchat: Snapchat load detected.");

        XposedBridge.log(
                "Loaded saving preferences: "
                        + "Images -> " + imageSavingMode + ", "
                        + "Videos -> " + videoSavingMode + ", "
                        + "Toast -> " + toastMode
        );

        if (imageSavingMode != SAVE_NEVER) {

            /**
             * Image Saving class, this is used for images in both Snaps and Stories
             */
            final class imageSaver extends XC_MethodHook {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    File file = constructFileObject(param.thisObject, "jpg");

                    // Check if the image was already saved.
                    if (!file.exists()) {
                        isSaved = false;
                        try {
                            Bitmap myImage = (Bitmap) param.getResult();
                            XposedBridge.log("Bitmap loaded.");
                            // open a new outputstream for writing
                            FileOutputStream out = new FileOutputStream(file);
                            // use built-in bitmap function to turn it
                            // into a jpeg and write it to the stream
                            myImage.compress(Bitmap.CompressFormat.JPEG, 90, out);
                            out.flush();
                            out.close();

                            mediaPath = file.getCanonicalPath();
                            XposedBridge.log("Saved image to " + mediaPath + "!");
                            // set media type indicator
                            isImageSnap = true;
                        } catch (Exception e) {
                            // reset mediaPath so that error Toast is
                            // shown and media scanner not run
                            mediaPath = null;
                            // if any exceptions are found, write to log
                            XposedBridge
                                    .log("Error occurred while saving the image.");
                            e.printStackTrace();
                        }
                    } else {
                        isSaved = true;
                        XposedBridge.log("Image already saved, doing nothing.");
                    }
                    // return the image to the original caller so the app can continue
                }
            }

			/*
			 * getImageBitmap() hook The ReceivedSnap class has a method to load
			 * a Bitmap in preparation for viewing. This method returns said
			 * bitmap back so the application can display it. We hook this
			 * method to intercept the result and write it to the SD card. The
			 * file path is stored in the mediaPath member for later use in the
			 * showImage() hook.
			 */
            findAndHookMethod("com.snapchat.android.model.ReceivedSnap",
                    lpparam.classLoader, "getImageBitmap", Context.class,
                    new imageSaver());

			/*
			 * getImageBitmap() hook The Story class has a method to load a
			 * Bitmap in preparation for viewing a Image in Story. This method
			 * returns said bitmap back so the application can display it. We
			 * hook this method to intercept the result and write it to the SD
			 * card. The file path is stored in the mediaPath member for later
			 * use in the showImage() hook.
			 */
            findAndHookMethod("com.snapchat.android.model.Story",
                    lpparam.classLoader, "getImageBitmap", Context.class,
                    new imageSaver());

        }

        if (videoSavingMode != SAVE_NEVER) {

			/*
			 * Created a common class for both the video hooks as they are the
			 * same for saving both the received snaps and stories.
			 */
            final class videoSaver extends XC_MethodHook {
                @Override
                protected void afterHookedMethod(MethodHookParam param)
                        throws Throwable {
                    String videoUri = (String) param.getResult();
                    XposedBridge.log("Video is at " + videoUri);
                    File file = constructFileObject(param.thisObject, "mp4");
                    if (!file.exists()) {
                        isSaved = false;
                        mediaPath = file.getCanonicalPath();
                        try {
                            // make a new input stream from the video
                            // URI
                            FileInputStream in = new FileInputStream(new File(
                                    videoUri));
                            // make a new output stream to write to
                            FileOutputStream out = new FileOutputStream(file);
                            // make a buffer we use for copying
                            byte[] buf = new byte[1024];
                            int len;
                            // copy the file over using a while loop
                            while ((len = in.read(buf)) > 0) {
                                out.write(buf, 0, len);
                            }
                            // close the input stream
                            in.close();
                            // flush the output stream so we know it's
                            // finished
                            out.flush();
                            // and then close it
                            out.close();
                            mediaPath = file.getCanonicalPath();
                            XposedBridge.log("Saved video to " + mediaPath
                                    + " ! ");
                            // set media type indicator
                            isImageSnap = false;
                        } catch (Exception e) {
                            // reset mediaPath so that error Toast is
                            // shown
                            // and media scanner not run
                            mediaPath = null;
                            // if any exceptions are found, write to log
                            XposedBridge
                                    .log("Error occurred while saving the video.");
                            e.printStackTrace();
                        }
                    } else {
                        isSaved = true;
                        XposedBridge.log("Video already saved, doing nothing.");
                    }
                }
            }

			/*
			 * getVideoUri() hook The ReceivedSnap class treats videos a little
			 * differently. Videos are not their own object, so they can't be
			 * passed around. The Android system basically provides a VideoView
			 * for viewing videos, which you just provide it the location of the
			 * video and it does the rest.
			 * 
			 * Unsurprisingly, Snapchat makes use of this View. This method in
			 * the ReceivedSnap class gets the URI of the video in preparation
			 * for one of these VideoViews. We hook in, intercept the result (a
			 * String), then copy the bytes from that location to our SD
			 * directory. This results in a bit of a slowdown for the user, but
			 * luckily this takes place before they actually view it.
			 * 
			 * The file path is stored in the mediaPath member for later use in
			 * the showVideo() hook.
			 */
            findAndHookMethod("com.snapchat.android.model.ReceivedSnap",
                    lpparam.classLoader, "getVideoUri", new videoSaver());

			/*
			 * getVideoUri() hook The Story class because the stories use the
			 * Story class and not the RecievedSnap class to prepare the video.
			 */
            findAndHookMethod("com.snapchat.android.model.Story",
                    lpparam.classLoader, "getVideoUri", new videoSaver());

        }

		/*
		 * showVideo() and showImage() hooks Because getVideoUri() and
		 * getImageBitmap() do not handily provide a context, nor do their
		 * parent classes (ReceivedSnap), we are unable to get the context
		 * necessary in order to display a notification and call the media
		 * scanner.
		 * 
		 * But these getters are called from the corresponding showVideo() and
		 * showImage() methods of com.snapchat.android.ui.SnapView, which
		 * deliver the needed context. So the work that needs a context is done
		 * here, while the file saving work is done in the getters. The getters
		 * also save the file paths in the mediaPath member, which we use here.
		 */
        if (imageSavingMode != SAVE_NEVER)
            findAndHookMethod("com.snapchat.android.ui.SnapView", lpparam.classLoader, "showImage", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    /**
                     *  At this point the context is put in the private member so that the dialog
                     *  can be initiated from the markViewed() hook
                     */
                    context = (Context) callSuperMethod(param.thisObject, "getContext");
                    if (imageSavingMode == SAVE_AUTO)
                        runMediaScanAndToast(context, mediaPath, "image");
                }
            });

        if (videoSavingMode != SAVE_NEVER)
            findAndHookMethod("com.snapchat.android.ui.SnapView", lpparam.classLoader, "showVideo", Context.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    /**
                     * At this point the context is put in the private member so that the dialog
                     * can be initiated from the markViewed() hook
                     */
                    context = (Context) param.args[0];
                    if (videoSavingMode == SAVE_AUTO)
                        runMediaScanAndToast(context, mediaPath, "video");
                }
            });


		/*
		 * Created a common class for both of the markViewed hooks as they are the
		 * same for saving both the received snaps and stories.
		 */

        final class askSave extends XC_MethodHook {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                // If auto saving is enabled, the media was already
                // saved and the media scanner called
                // So only call the dialog is asking is enabled
                // Only ask when the image has not been previously saved
                if ((isImageSnap && imageSavingMode == SAVE_ASK && isSaved == false)
                        || (!isImageSnap && videoSavingMode == SAVE_ASK && isSaved == false)) {
                    showDialog(context);
                    XposedBridge.log("Show dialog in markViewed hook.");
                }
            }
        }

        findAndHookMethod("com.snapchat.android.model.ReceivedSnap", lpparam.classLoader, "markViewed", new askSave());
        findAndHookMethod("com.snapchat.android.model.Story", lpparam.classLoader, "markViewed", new askSave());

		/*
		 * hooking the debug method so that snapchat writes to logcat to help
		 * figure out what snapchat is doing
		 */
		/*findAndHookMethod("com.snapchat.android.Timber", lpparam.classLoader,
				"debugMode", new XC_MethodReplacement() {
					@Override
					protected Object replaceHookedMethod(MethodHookParam param)
							throws Throwable {
						XposedBridge.log("Debug on");
						// the line
						return true;
					}
				});*/

		/*
		 * wasScreenshotted() hook This method is called to see if the Snap was
		 * screenshotted. We hook it to always return false, meaning that it was
		 * not screenshotted.
		 */
        findAndHookMethod("com.snapchat.android.model.ReceivedSnap", lpparam.classLoader, "wasScreenshotted", new XC_MethodReplacement() {
            @Override
            protected Object replaceHookedMethod(MethodHookParam param) throws Throwable {
                XposedBridge.log("Not reporting screenshotted. :");
                return false;
            }
        });
    } // END handleLoadPackage

    /*
     * Tells the media scanner to scan the newly added image or video so that it
     * shows up in the gallery without a reboot. And shows a Toast message where
     * the media was saved.
     *
     * @param context Current context
     *
     * @param filePath File to be scanned by the media scanner
     */
    private void runMediaScanAndToast(Context context, String filePath,
                                      String type) {
        String toastText;
        // If the filePath is not null, show a toast with the path and call the
        // media scanner.
        // Otherwise, show an error toast message.
        if (filePath != null) {
            // so video saved successfully.
            toastText = "Saved " + type + " to " + filePath;
            try {
                XposedBridge.log("MediaScanner running: " + filePath);
                // Run MediaScanner on file, so it shows up in Gallery instantly
                MediaScannerConnection.scanFile(context,
                        new String[] { filePath }, null,
                        new MediaScannerConnection.OnScanCompletedListener() {
                            public void onScanCompleted(String path, Uri uri) {
                                if (uri != null) {
                                    XposedBridge
                                            .log("MediaScanner ran successfully: "
                                                    + uri.toString());
                                } else {
                                    XposedBridge
                                            .log("Unknown error occurred while trying to run MediaScanner");
                                }
                            }
                        });
            } catch (Exception e) {
                XposedBridge
                        .log("Error occurred while trying to run MediaScanner");
                e.printStackTrace();
            }
        } else {
            toastText = type + " could not be saved! file null.";
        }
        // construct the toast notification
        if ((toastMode >= 0) && (isSaved == false)) {
            Toast.makeText(context, toastText, toastMode).show();
        } else if ((toastMode >= 0) && (isSaved == true)) {
            // display is saved when image/video already exists.
            Toast.makeText(context, type + " is already saved.", toastMode)
                    .show();
        }
    }

    /**
     * Return a File object to safe the image/video. The filename will be in the
     * format <sender>_yyyy-MM-dd_HH-mm-ss.<suffix> and it resides in the
     * <i>keepchat/</i> subfolder on the SD card. Along the way, the
     * <i>keepchat/</i> subfolder is created if not existent.
     *
     * @param snapObject
     *            The ReceivedSnap Object, which contains the necessary
     *            information, i.e., the senser's name and the snap's timestamp.
     *            It should be passed to the method via 'param.thisObject'
     *            inside a hooked method.
     *
     * @param suffix
     *            The file suffix, either "jpg" or "mp4"
     */
    private File constructFileObject(Object snapObject, String suffix) throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        // we construct a path for us to write to
        String root = Environment.getExternalStorageDirectory().toString();

        if (saveLocation != "" ) root = saveLocation;

        // and add our own directory
        File myDir = new File(root + "/keepchat");
        XposedBridge.log("Saving to directory " + myDir.toString());
        // we make the directory if it doesn't exist.
        if (myDir.mkdirs())
            XposedBridge.log("Directory " + myDir.toString() + " was created.");
        // construct the filename. It shall start with the sender's name...
        String sender = (String) callMethod(snapObject, "getSender");
        // ...continue with the current date and time, lexicographically...
        SimpleDateFormat fnameDateFormat = new SimpleDateFormat(
                "yyyy-MM-dd_HH-mm-ss", Locale.US);
        // ReceivedSnap extends the Snap class. getTimestamp() is a member of
        // the Snap class,
        // so we cannot access it via XposedHelpers.callMethod() and have to use
        // our own reflection for that
        Date timestamp = new Date((Long) callSuperMethod(snapObject,
                "getTimestamp"));
        // ...and end in the suffix provided ("jpg" or "mp4")
        String fname = sender + "_" + (fnameDateFormat.format(timestamp)) + "."
                + suffix;
        XposedBridge.log("Saving with filename " + fname);
        // construct a File object
        return new File(myDir, fname);
    }

    private void showDialog(final Context dContext) {
        // 1. Instantiate an AlertDialog.Builder with its constructor
        AlertDialog.Builder builder = new AlertDialog.Builder(dContext);

        // 2. Chain together various setter methods to set the dialog
        // characteristics
        final String mediaTypeStr = isImageSnap ? "image" : "video";
        builder.setMessage(	"The " + mediaTypeStr + " will be saved at\n" + mediaPath).setTitle("Save " + mediaTypeStr + "?");

        builder.setPositiveButton("Save",new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                XposedBridge.log("User choose to save. Keep saved file.");
                runMediaScanAndToast(dContext, mediaPath, mediaTypeStr);
            }
        });

        builder.setNeutralButton("Settings",new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                XposedBridge.log("User choose to show settings. Keep saved file.");
                runMediaScanAndToast(dContext, mediaPath, mediaTypeStr);
                Intent settingsIntent = new Intent(Intent.ACTION_MAIN,	null);
                settingsIntent.addCategory(Intent.CATEGORY_LAUNCHER);
                settingsIntent.setComponent(ComponentName.unflattenFromString("com.sturmen.xposed.keepchat/.SettingsActivity"));
                dContext.startActivity(settingsIntent);
            }
        });
        builder.setNegativeButton("Discard",
                new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int id) {
                        XposedBridge.log("User choose not to save.");
                        dialog.cancel();
                    }
                });

        builder.setOnCancelListener(new DialogInterface.OnCancelListener() {
            @Override
            public void onCancel(DialogInterface dialog) {
                if ((new File(mediaPath)).delete())
                    XposedBridge.log("File " + mediaPath
                            + " deleted successfully");
                else
                    XposedBridge.log("Could not delete file " + mediaPath);
            }
        });
        // 3. Get the AlertDialog from create()
        AlertDialog dialog = builder.create();
        dialog.show();
    }

    /**
     * {@code XposedHelpers.callMethod()} cannot call methods of the super class
     * of an object, because it uses {@code getDeclaredMethods()}. So we have to
     * implement this little helper, which should work similar to {@code }
     * callMethod()}. Furthermore, the exceptions from getMethod() are passed
     * on.
     * <p>
     * At the moment, only argument-free methods supported (only case needed
     * here). After a discussion with the Xposed author it looks as if the
     * functionality to call super methods will be implemented in
     * {@code XposedHelpers.callMethod()} in a future release.
     *
     * @param obj
     *            Object whose method should be called
     * @param methodName
     *            String representing the name of the argument-free method to be
     *            called
     * @return The object that the method call returns
     * @see <a href=
     *      "http://forum.xda-developers.com/showpost.php?p=42598280&postcount=1753"
     *      > Discussion about calls to super methods in Xposed's XDA thread</a>
     */
    private Object callSuperMethod(Object obj, String methodName)
            throws NoSuchMethodException, InvocationTargetException,
            IllegalAccessException {
        return obj.getClass().getMethod(methodName).invoke(obj);
    }

}