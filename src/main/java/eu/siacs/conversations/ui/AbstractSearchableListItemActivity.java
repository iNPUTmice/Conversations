package eu.siacs.conversations.ui;

import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.databinding.DataBindingUtil;

import java.util.ArrayList;
import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityChooseContactBinding;
import eu.siacs.conversations.entities.ListItem;
import eu.siacs.conversations.ui.adapter.ListItemAdapter;

public abstract class AbstractSearchableListItemActivity extends XmppActivity implements TextView.OnEditorActionListener {
	protected ActivityChooseContactBinding binding;
	private final List<ListItem> listItems = new ArrayList<>();
	private ArrayAdapter<ListItem> mListItemsAdapter;

	private EditText mSearchEditText;

	private final MenuItem.OnActionExpandListener mOnActionExpandListener = new MenuItem.OnActionExpandListener() {

		@Override
		public boolean onMenuItemActionExpand(final MenuItem item) {
			mSearchEditText.post(() -> {
				mSearchEditText.requestFocus();
				final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
				imm.showSoftInput(mSearchEditText, InputMethodManager.SHOW_IMPLICIT);
			});

			return true;
		}

		@Override
		public boolean onMenuItemActionCollapse(final MenuItem item) {
			final InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
			imm.hideSoftInputFromWindow(mSearchEditText.getWindowToken(), InputMethodManager.HIDE_IMPLICIT_ONLY);
			mSearchEditText.setText("");
			filterContacts();
			return true;
		}
	};

	private final TextWatcher mSearchTextWatcher = new TextWatcher() {

		@Override
		public void afterTextChanged(final Editable editable) {
			filterContacts(editable.toString());
		}

		@Override
		public void beforeTextChanged(final CharSequence s, final int start, final int count,
				final int after) {
		}

		@Override
		public void onTextChanged(final CharSequence s, final int start, final int before,
				final int count) {
		}
	};

	public ListView getListView() {
		return binding.chooseContactList;
	}

	public List<ListItem> getListItems() {
		return listItems;
	}

	public EditText getSearchEditText() {
		return mSearchEditText;
	}

	public ArrayAdapter<ListItem> getListItemAdapter() {
		return mListItemsAdapter;
	}

	@Override
	public void onCreate(final Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		this.binding = DataBindingUtil.setContentView(this,R.layout.activity_choose_contact);
		setSupportActionBar(binding.toolbar);
		configureActionBar(getSupportActionBar());
		this.binding.chooseContactList.setFastScrollEnabled(true);
		mListItemsAdapter = new ListItemAdapter(this, listItems);
		this.binding.chooseContactList.setAdapter(mListItemsAdapter);
	}

	@Override
	public boolean onCreateOptionsMenu(final Menu menu) {
		getMenuInflater().inflate(R.menu.choose_contact, menu);
		final MenuItem menuSearchView = menu.findItem(R.id.action_search);
		final View mSearchView = menuSearchView.getActionView();
		mSearchEditText = mSearchView.findViewById(R.id.search_field);
		mSearchEditText.addTextChangedListener(mSearchTextWatcher);
		mSearchEditText.setHint(R.string.search_contacts);
		mSearchEditText.setOnEditorActionListener(this);
		menuSearchView.setOnActionExpandListener(mOnActionExpandListener);
		return true;
	}

	protected void filterContacts() {
		final String needle = mSearchEditText != null ? mSearchEditText.getText().toString() : null;
		if (needle != null && !needle.isEmpty()) {
			filterContacts(needle);
		} else {
			filterContacts(null);
		}
	}

	protected abstract void filterContacts(final String needle);

	@Override
	void onBackendConnected() {
		filterContacts();
	}

	@Override
	public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
		return false;
	}
}
