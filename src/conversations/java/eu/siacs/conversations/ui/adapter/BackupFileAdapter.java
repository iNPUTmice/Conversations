package eu.siacs.conversations.ui.adapter;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.text.format.DateUtils;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.AccountRowBinding;
import eu.siacs.conversations.services.AvatarService;
import eu.siacs.conversations.services.ImportBackupService;
import eu.siacs.conversations.utils.BackupFileHeader;
import eu.siacs.conversations.utils.UIHelper;
import eu.siacs.conversations.xmpp.Jid;

public class BackupFileAdapter extends RecyclerView.Adapter<BackupFileAdapter.BackupFileViewHolder> {

    private OnItemClickedListener listener;

    private final List<ImportBackupService.BackupFile> files = new ArrayList<>();


    @NonNull
    @Override
    public BackupFileViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int i) {
        return new BackupFileViewHolder(DataBindingUtil.inflate(LayoutInflater.from(viewGroup.getContext()), R.layout.account_row, viewGroup, false));
    }

    @Override
    public void onBindViewHolder(@NonNull BackupFileViewHolder backupFileViewHolder, int position) {
        final ImportBackupService.BackupFile backupFile = files.get(position);
        final BackupFileHeader header = backupFile.getHeader();
        backupFileViewHolder.binding.accountJid.setText(header.getJid().asBareJid().toString());
        backupFileViewHolder.binding.accountStatus.setText(String.format("%s · %s",header.getApp(), DateUtils.formatDateTime(backupFileViewHolder.binding.getRoot().getContext(), header.getTimestamp(), DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR)));
        backupFileViewHolder.binding.tglAccountStatus.setVisibility(View.GONE);
        backupFileViewHolder.binding.getRoot().setOnClickListener(v -> {
            if (listener != null) {
                listener.onClick(backupFile);
            }
        });
        loadAvatar(header.getJid(), backupFileViewHolder.binding.accountImage);
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    public void setFiles(List<ImportBackupService.BackupFile> files) {
        this.files.clear();
        this.files.addAll(files);
        notifyDataSetChanged();
    }

    public void setOnItemClickedListener(OnItemClickedListener listener) {
        this.listener = listener;
    }

    static class BackupFileViewHolder extends RecyclerView.ViewHolder {
        private final AccountRowBinding binding;

        BackupFileViewHolder(AccountRowBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }

    }

    public interface OnItemClickedListener {
        void onClick(ImportBackupService.BackupFile backupFile);
    }

    static class BitmapWorkerTask extends AsyncTask<Jid, Void, Bitmap> {
        private final WeakReference<ImageView> imageViewReference;
        private Jid jid  = null;
        private final int size;

        BitmapWorkerTask(ImageView imageView) {
            imageViewReference = new WeakReference<>(imageView);
            DisplayMetrics metrics = imageView.getContext().getResources().getDisplayMetrics();
		this.size = ((int) (48 * metrics.density));
        }

        @Override
        protected Bitmap doInBackground(Jid... params) {
            this.jid = params[0];
            return AvatarService.get(this.jid, size);
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

    private void loadAvatar(Jid jid, ImageView imageView) {
        if (cancelPotentialWork(jid, imageView)) {
            imageView.setBackgroundColor(UIHelper.getColorForName(jid.asBareJid().toString()));
            imageView.setImageDrawable(null);
            final BitmapWorkerTask task = new BitmapWorkerTask(imageView);
            final AsyncDrawable asyncDrawable = new AsyncDrawable(imageView.getContext().getResources(), null, task);
            imageView.setImageDrawable(asyncDrawable);
            try {
                task.execute(jid);
            } catch (final RejectedExecutionException ignored) {
            }
        }
    }

    private static boolean cancelPotentialWork(Jid jid, ImageView imageView) {
        final BitmapWorkerTask bitmapWorkerTask = getBitmapWorkerTask(imageView);

        if (bitmapWorkerTask != null) {
            final Jid oldJid = bitmapWorkerTask.jid;
            if (oldJid == null || jid != oldJid) {
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

}