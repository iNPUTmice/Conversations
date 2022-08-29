/*
 * Copyright 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.siacs.conversations.ui.widget;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;

import androidx.fragment.app.ListFragment;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.util.StyledAttributes;

/**
 * Subclass of {@link androidx.fragment.app.ListFragment} which provides automatic support for
 * providing the 'swipe-to-refresh' UX gesture by wrapping the the content view in a
 * {@link androidx.swiperefreshlayout.widget.SwipeRefreshLayout}.
 */
public class SwipeRefreshListFragment extends ListFragment {

    private boolean enabled = false;
    private boolean refreshing = false;

    private SwipeRefreshLayout.OnRefreshListener onRefreshListener;

    private SwipeRefreshLayout mSwipeRefreshLayout;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        // Create the list fragment's content view by calling the super method
        final View listFragmentView = super.onCreateView(inflater, container, savedInstanceState);

        // Now create a SwipeRefreshLayout to wrap the fragment's content view
        mSwipeRefreshLayout = new ListFragmentSwipeRefreshLayout(container.getContext());
        mSwipeRefreshLayout.setEnabled(enabled);
        mSwipeRefreshLayout.setRefreshing(refreshing);

        final Context context = getActivity();
        if (context != null) {
            mSwipeRefreshLayout.setColorSchemeColors(StyledAttributes.getColor(context, R.attr.colorAccent));
        }

        if (onRefreshListener != null) {
            mSwipeRefreshLayout.setOnRefreshListener(onRefreshListener);
        }

        // Add the list fragment's content view to the SwipeRefreshLayout, making sure that it fills
        // the SwipeRefreshLayout
        mSwipeRefreshLayout.addView(listFragmentView,
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);

        // Make sure that the SwipeRefreshLayout will fill the fragment
        mSwipeRefreshLayout.setLayoutParams(
                new ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT));

        // Now return the SwipeRefreshLayout as this fragment's content view
        return mSwipeRefreshLayout;
    }

    /**
     * Set the {@link androidx.core.widget.SwipeRefreshLayout.OnRefreshListener} to listen for
     * initiated refreshes.
     *
     * @see androidx.core.widget.SwipeRefreshLayout#setOnRefreshListener(androidx.core.widget.SwipeRefreshLayout.OnRefreshListener)
     */
    public void setOnRefreshListener(SwipeRefreshLayout.OnRefreshListener listener) {
        onRefreshListener = listener;
        enabled = true;
        if (mSwipeRefreshLayout != null) {
            mSwipeRefreshLayout.setEnabled(true);
            mSwipeRefreshLayout.setOnRefreshListener(listener);
        }
    }

    /**
     * Set whether the {@link androidx.core.widget.SwipeRefreshLayout} should be displaying
     * that it is refreshing or not.
     *
     * @see androidx.core.widget.SwipeRefreshLayout#setRefreshing(boolean)
     */
    public void setRefreshing(boolean refreshing) {
        this.refreshing = refreshing;
        if (mSwipeRefreshLayout != null) {
            mSwipeRefreshLayout.setRefreshing(refreshing);
        }
    }


    /**
     * Sub-class of {@link androidx.core.widget.SwipeRefreshLayout} for use in this
     * {@link androidx.core.app.ListFragment}. The reason that this is needed is because
     * {@link androidx.core.widget.SwipeRefreshLayout} only supports a single child, which it
     * expects to be the one which triggers refreshes. In our case the layout's child is the content
     * view returned from
     * {@link androidx.core.app.ListFragment#onCreateView(android.view.LayoutInflater, android.view.ViewGroup, android.os.Bundle)}
     * which is a {@link android.view.ViewGroup}.
     *
     * <p>To enable 'swipe-to-refresh' support via the {@link android.widget.ListView} we need to
     * override the default behavior and properly signal when a gesture is possible. This is done by
     * overriding {@link #canChildScrollUp()}.
     */
    private class ListFragmentSwipeRefreshLayout extends SwipeRefreshLayout {

        public ListFragmentSwipeRefreshLayout(Context context) {
            super(context);
        }

        /**
         * As mentioned above, we need to override this method to properly signal when a
         * 'swipe-to-refresh' is possible.
         *
         * @return true if the {@link android.widget.ListView} is visible and can scroll up.
         */
        @Override
        public boolean canChildScrollUp() {
            final ListView listView = getListView();
            if (listView.getVisibility() == View.VISIBLE) {
                return listView.canScrollVertically(-1);
            } else {
                return false;
            }
        }

    }

}
