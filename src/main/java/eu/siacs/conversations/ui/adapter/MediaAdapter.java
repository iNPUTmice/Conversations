package eu.siacs.conversations.ui.adapter;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.AttrRes;
import androidx.annotation.DimenRes;
import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.MediaBinding;
import eu.siacs.conversations.services.ExportBackupService;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.StyledAttributes;
import eu.siacs.conversations.ui.util.ViewUtil;

public class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.MediaViewHolder> {

    public static final List<String> DOCUMENT_MIMES = Arrays.asList(
            "application/pdf",
            "application/vnd.oasis.opendocument.text",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "text/x-tex",
            "text/plain"
    );

    private final ArrayList<Attachment> attachments = new ArrayList<>();

    private final XmppActivity activity;

    private int mediaSize = 0;

    public MediaAdapter(XmppActivity activity, @DimenRes int mediaSize) {
        this.activity = activity;
        this.mediaSize = Math.round(activity.getResources().getDimension(mediaSize));
    }

    @SuppressWarnings("rawtypes")
    public static void setMediaSize(RecyclerView recyclerView, int mediaSize) {
        final RecyclerView.Adapter adapter = recyclerView.getAdapter();
        if (adapter instanceof MediaAdapter) {
            ((MediaAdapter) adapter).setMediaSize(mediaSize);
        }
    }

    private static @AttrRes
    int getImageAttr(Attachment attachment) {
        final @AttrRes int attr;
        if (attachment.getType() == Attachment.Type.LOCATION) {
            attr = R.attr.media_preview_location;
        } else if (attachment.getType() == Attachment.Type.RECORDING) {
            attr = R.attr.media_preview_recording;
        } else {
            final String mime = attachment.getMime();
            Log.d(Config.LOGTAG, "mime=" + mime);
            if (mime == null) {
                attr = R.attr.media_preview_unknown;
            } else if (mime.startsWith("audio/")) {
                attr = R.attr.media_preview_audio;
            } else if (mime.equals("text/calendar") || (mime.equals("text/x-vcalendar"))) {
                attr = R.attr.media_preview_calendar;
            } else if (mime.equals("text/x-vcard")) {
                attr = R.attr.media_preview_contact;
            } else if (mime.equals("application/vnd.android.package-archive")) {
                attr = R.attr.media_preview_app;
            } else if (mime.equals("application/zip") || mime.equals("application/rar")) {
                attr = R.attr.media_preview_archive;
            } else if (mime.equals("application/epub+zip") || mime.equals("application/vnd.amazon.mobi8-ebook")) {
                attr = R.attr.media_preview_ebook;
            } else if (mime.equals(ExportBackupService.MIME_TYPE)) {
                attr = R.attr.media_preview_backup;
            } else if (DOCUMENT_MIMES.contains(mime)) {
                attr = R.attr.media_preview_document;
            } else if (mime.equals("application/gpx+xml")) {
                attr = R.attr.media_preview_tour;
            } else if (mime.startsWith("image/")) {
                attr = R.attr.media_preview_image;
            } else {
                attr = R.attr.media_preview_unknown;
            }
        }
        return attr;
    }

    static void renderPreview(Context context, Attachment attachment, ImageView imageView) {
        imageView.setBackgroundColor(StyledAttributes.getColor(context, R.attr.color_background_tertiary));
        imageView.setImageAlpha(Math.round(StyledAttributes.getFloat(context, R.attr.icon_alpha) * 255));
        imageView.setImageDrawable(StyledAttributes.getDrawable(context, getImageAttr(attachment)));
    }

    private static boolean cancelPotentialWork(Attachment attachment, ImageView imageView) {
        final BitmapWorkerTask bitmapWorkerTask = getBitmapWorkerTask(imageView);

        if (bitmapWorkerTask != null) {
            final Attachment oldAttachment = bitmapWorkerTask.attachment;
            if (oldAttachment == null || !oldAttachment.equals(attachment)) {
                bitmapWorkerTask.cancel(true);
            } else {
                return false;
            }
        }
        return true;
    }

    private static BitmapWorkerTask getBitmapWorkerTask(ImageView imageView) {
        if (imageView != null) {
            final Drawable drawable = imageView.getDrawable();
            if (drawable instanceof AsyncDrawable) {
                final AsyncDrawable asyncDrawable = (AsyncDrawable) drawable;
                return asyncDrawable.getBitmapWorkerTask();
            }
        }
        return null;
    }

    @NonNull
    @Override
    public MediaViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater layoutInflater = LayoutInflater.from(parent.getContext());
        MediaBinding binding = DataBindingUtil.inflate(layoutInflater, R.layout.media, parent, false);
        return new MediaViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull MediaViewHolder holder, int position) {
        final Attachment attachment = attachments.get(position);
        if (attachment.renderThumbnail()) {
            holder.binding.media.setImageAlpha(255);
            loadPreview(attachment, holder.binding.media);
        } else {
            cancelPotentialWork(attachment, holder.binding.media);
            renderPreview(activity, attachment, holder.binding.media);
        }
        holder.binding.getRoot().setOnClickListener(v -> ViewUtil.view(activity, attachment));
    }

    public void setAttachments(List<Attachment> attachments) {
        this.attachments.clear();
        this.attachments.addAll(attachments);
        notifyDataSetChanged();
    }

    private void setMediaSize(int mediaSize) {
        this.mediaSize = mediaSize;
    }

    private void loadPreview(Attachment attachment, ImageView imageView) {
        if (cancelPotentialWork(attachment, imageView)) {
            final Bitmap bm = activity.xmppConnectionService.getFileBackend().getPreviewForUri(attachment, mediaSize, true);
            if (bm != null) {
                cancelPotentialWork(attachment, imageView);
                imageView.setImageBitmap(bm);
                imageView.setBackgroundColor(0x00000000);
            } else {
                imageView.setBackgroundColor(0xff333333);
                imageView.setImageDrawable(null);
                final BitmapWorkerTask task = new BitmapWorkerTask(mediaSize, imageView);
                final AsyncDrawable asyncDrawable = new AsyncDrawable(activity.getResources(), null, task);
                imageView.setImageDrawable(asyncDrawable);
                try {
                    task.execute(attachment);
                } catch (final RejectedExecutionException ignored) {
                }
            }
        }
    }

    @Override
    public int getItemCount() {
        return attachments.size();
    }

    static class AsyncDrawable extends BitmapDrawable {
        private final WeakReference<BitmapWorkerTask> bitmapWorkerTaskReference;

        AsyncDrawable(Resources res, Bitmap bitmap, BitmapWorkerTask bitmapWorkerTask) {
            super(res, bitmap);
            bitmapWorkerTaskReference = new WeakReference<>(bitmapWorkerTask);
        }

        BitmapWorkerTask getBitmapWorkerTask() {
            return bitmapWorkerTaskReference.get();
        }
    }

    class MediaViewHolder extends RecyclerView.ViewHolder {

        private final MediaBinding binding;

        MediaViewHolder(MediaBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    private static class BitmapWorkerTask extends AsyncTask<Attachment, Void, Bitmap> {
        private final WeakReference<ImageView> imageViewReference;
        private Attachment attachment = null;
        private final int mediaSize;

        BitmapWorkerTask(int mediaSize, ImageView imageView) {
            this.mediaSize = mediaSize;
            imageViewReference = new WeakReference<>(imageView);
        }

        @Override
        protected Bitmap doInBackground(Attachment... params) {
            this.attachment = params[0];
            final XmppActivity activity = XmppActivity.find(imageViewReference);
            if (activity == null) {
                return null;
            }
            return activity.xmppConnectionService.getFileBackend().getPreviewForUri(this.attachment, mediaSize, false);
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (bitmap != null && !isCancelled()) {
                final ImageView imageView = imageViewReference.get();
                if (imageView != null) {
                    imageView.setImageBitmap(bitmap);
                    imageView.setBackgroundColor(0x00000000);
                }
            }
        }
    }
}
