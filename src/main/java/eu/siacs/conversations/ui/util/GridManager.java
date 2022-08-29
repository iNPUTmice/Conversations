package eu.siacs.conversations.ui.util;

import android.content.Context;
import android.util.Log;
import android.view.ViewTreeObserver;

import androidx.annotation.DimenRes;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.ui.adapter.MediaAdapter;

public class GridManager {

    public static void setupLayoutManager(final Context context, RecyclerView recyclerView, @DimenRes int desiredSize) {
        int maxWidth = context.getResources().getDisplayMetrics().widthPixels;
        ColumnInfo columnInfo = calculateColumnCount(context, maxWidth, desiredSize);
        Log.d(Config.LOGTAG, "preliminary count=" + columnInfo.count);
        MediaAdapter.setMediaSize(recyclerView, columnInfo.width);
        recyclerView.setLayoutManager(new GridLayoutManager(context, columnInfo.count));
        recyclerView.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                recyclerView.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                final int availableWidth = recyclerView.getMeasuredWidth();
                if (availableWidth == 0) {
                    Log.e(Config.LOGTAG,"GridManager: available width was 0; probably because layout was hidden");
                    return;
                }
                final ColumnInfo columnInfo = calculateColumnCount(context, recyclerView.getMeasuredWidth(), desiredSize);
                Log.d(Config.LOGTAG, "final count " + columnInfo.count);
                final RecyclerView.Adapter adapter = recyclerView.getAdapter();
                if (adapter != null && adapter.getItemCount() != 0) {
                    Log.e(Config.LOGTAG, "adapter already has items; just go with it now");
                    return;
                }
                setupLayoutManagerInternal(recyclerView, columnInfo);
                MediaAdapter.setMediaSize(recyclerView, columnInfo.width);
            }
        });
    }

    private static void setupLayoutManagerInternal(RecyclerView recyclerView, final ColumnInfo columnInfo) {
        RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
        if (layoutManager instanceof GridLayoutManager) {
            ((GridLayoutManager) layoutManager).setSpanCount(columnInfo.count);
        }
    }

    private static ColumnInfo calculateColumnCount(Context context, int availableWidth, @DimenRes int desiredSize) {
        final float desiredWidth = context.getResources().getDimension(desiredSize);
        final int columns = Math.round(availableWidth / desiredWidth);
        final int realWidth = availableWidth / columns;
        Log.d(Config.LOGTAG, "desired=" + desiredWidth + " real=" + realWidth);
        return new ColumnInfo(columns, realWidth);
    }

    public static int getCurrentColumnCount(RecyclerView recyclerView) {
        RecyclerView.LayoutManager layoutManager = recyclerView.getLayoutManager();
        if (layoutManager instanceof GridLayoutManager) {
            return ((GridLayoutManager) layoutManager).getSpanCount();
        } else {
            return 0;
        }
    }

    public static class ColumnInfo {
        private final int count;
        private final int width;

        private ColumnInfo(int count, int width) {
            this.count = count;
            this.width = width;
        }
    }

}
