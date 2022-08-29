package eu.siacs.conversations.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.widget.Toolbar;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ActivityChooseCountryBinding;
import eu.siacs.conversations.ui.adapter.CountryAdapter;
import eu.siacs.conversations.utils.PhoneNumberUtilWrapper;
import eu.siacs.conversations.utils.ThemeHelper;

public class ChooseCountryActivity extends ActionBarActivity implements CountryAdapter.OnCountryClicked {

    private ActivityChooseCountryBinding binding;

    private List<PhoneNumberUtilWrapper.Country> countries = new ArrayList<>();
    private CountryAdapter countryAdapter = new CountryAdapter(countries);
    private final TextWatcher mSearchTextWatcher = new TextWatcher() {

        @Override
        public void afterTextChanged(final Editable editable) {
            filterCountries(editable.toString());
        }

        @Override
        public void beforeTextChanged(final CharSequence s, final int start, final int count, final int after) {
        }

        @Override
        public void onTextChanged(final CharSequence s, final int start, final int before, final int count) {
        }
    };
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
            filterCountries(null);
            return true;
        }
    };
    private TextView.OnEditorActionListener mSearchDone = (v, actionId, event) -> {
        if (countries.size() == 1) {
            onCountryClicked(countries.get(0));
        }
        return true;
    };

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTheme(ThemeHelper.find(this));
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_choose_country);
        setSupportActionBar((Toolbar) this.binding.toolbar);
        configureActionBar(getSupportActionBar());
        this.countries.addAll(PhoneNumberUtilWrapper.getCountries(this));
        Collections.sort(this.countries);
        this.binding.countries.setAdapter(countryAdapter);
        this.binding.countries.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.VERTICAL, false));
        countryAdapter.setOnCountryClicked(this);
        countryAdapter.notifyDataSetChanged();
    }

    @Override
    public void onCountryClicked(PhoneNumberUtilWrapper.Country country) {
        Intent data = new Intent();
        data.putExtra("region", country.getRegion());
        setResult(RESULT_OK, data);
        finish();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        getMenuInflater().inflate(R.menu.choose_country, menu);
        final MenuItem menuSearchView = menu.findItem(R.id.action_search);
        final View mSearchView = menuSearchView.getActionView();
        mSearchEditText = mSearchView.findViewById(R.id.search_field);
        mSearchEditText.addTextChangedListener(mSearchTextWatcher);
        mSearchEditText.setHint(R.string.search_countries);
        mSearchEditText.setOnEditorActionListener(mSearchDone);
        menuSearchView.setOnActionExpandListener(mOnActionExpandListener);
        return true;
    }

    private void filterCountries(String needle) {
        List<PhoneNumberUtilWrapper.Country> countries = PhoneNumberUtilWrapper.getCountries(this);
        Iterator<PhoneNumberUtilWrapper.Country> iterator = countries.iterator();
        while (iterator.hasNext()) {
            final PhoneNumberUtilWrapper.Country country = iterator.next();
            if (needle != null && !country.getName().toLowerCase(Locale.getDefault()).contains(needle.toLowerCase(Locale.getDefault()))) {
                iterator.remove();
            }
        }
        this.countries.clear();
        this.countries.addAll(countries);
        this.countryAdapter.notifyDataSetChanged();
    }

}
