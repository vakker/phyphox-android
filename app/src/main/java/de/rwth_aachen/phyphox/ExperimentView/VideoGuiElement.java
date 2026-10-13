package de.rwth_aachen.phyphox.ExperimentView;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.os.Build;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import java.io.Serializable;

import de.rwth_aachen.phyphox.ExpViewFragment;
import de.rwth_aachen.phyphox.PhyphoxExperiment;
import de.rwth_aachen.phyphox.camera.video.VideoInput;

//video-gui (only in this build): what the video input (VideoInput) records, on the screen. The
//preview joins the camera when a recording starts while it is shown; it does not change the video.
public class VideoGuiElement extends ExpViewElement implements Serializable {
    private final double maxHeight; //Of the screen's height

    public VideoGuiElement(String label, String visibility, Resources res, double maxHeight) {
        super(label, visibility, (String) null, null, res);
        this.maxHeight = maxHeight;
    }

    @Override
    public String getUpdateMode() {
        return "none";
    }

    @Override
    protected String createViewHTML() {
        return "<div style=\"font-size: 105%;\" class=\"graphElement\" id=\"element" + htmlID + "\">" + (hasLabel() ? "<span class=\"label\">" + this.label + "</span>" : "") + "</div>";
    }

    @Override
    public void createView(LinearLayout ll, Context c, Resources res, ExpViewFragment parent, PhyphoxExperiment experiment) {
        super.createView(ll, c, res, parent, experiment);
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || experiment.videoInput == null)
            return;
        Preview preview = new Preview(c, experiment.videoInput, maxHeight);
        FrameLayout frame = new FrameLayout(c);
        frame.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        frame.addView(preview, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL));
        rootView = frame;
        ll.addView(rootView);
    }

    //A TextureView in the shape of the video as the screen shows it: the frames come in the sensor's
    //orientation and are turned to the screen's, as in the Camera2 samples
    static class Preview extends TextureView implements TextureView.SurfaceTextureListener {
        private final VideoInput video;
        private final double maxHeight;

        Preview(Context c, VideoInput video, double maxHeight) {
            super(c);
            this.video = video;
            this.maxHeight = maxHeight;
            setSurfaceTextureListener(this);
            //The texture a recording kept from the view before (another tab was shown), so the camera goes on into it
            SurfaceTexture kept = video.keptPreview();
            if (kept != null) {
                setSurfaceTexture(kept);
                video.previewShown(kept);
            }
        }

        private int displayRotation() {
            WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            return wm == null ? Surface.ROTATION_0 : wm.getDefaultDisplay().getRotation();
        }

        //The video's aspect ratio on the screen, width over height
        private double aspect() {
            double aspect = (double) video.settings().width / video.settings().height;
            int rotation = displayRotation();
            boolean sideways = (video.sensorOrientation() % 180 == 90) == (rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180);
            return sideways ? 1 / aspect : aspect;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int width = MeasureSpec.getSize(widthMeasureSpec);
            double aspect = aspect();
            int height = (int) Math.round(width / aspect);
            int limit = (int) Math.round(getResources().getDisplayMetrics().heightPixels * maxHeight);
            if (height > limit) {
                height = limit;
                width = (int) Math.round(height * aspect);
            }
            setMeasuredDimension(width, height);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            transform(w, h);
        }

        //Turns the buffer for a screen in landscape; in portrait the buffer comes turned already
        private void transform(int w, int h) {
            int rotation = displayRotation();
            Matrix matrix = new Matrix();
            if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
                RectF view = new RectF(0, 0, w, h);
                RectF buffer = new RectF(0, 0, h, w);
                buffer.offset(view.centerX() - buffer.centerX(), view.centerY() - buffer.centerY());
                matrix.setRectToRect(view, buffer, Matrix.ScaleToFit.FILL);
                matrix.postRotate(90 * (rotation - 2), view.centerX(), view.centerY());
            } else if (rotation == Surface.ROTATION_180)
                matrix.postRotate(180, w / 2f, h / 2f);
            setTransform(matrix);
        }

        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture texture, int width, int height) {
            video.previewShown(texture);
            transform(width, height);
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture texture, int width, int height) {
            transform(width, height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture texture) {
            return !video.previewGone(texture); //A recording keeps the texture for the next view
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture texture) {
        }
    }
}
