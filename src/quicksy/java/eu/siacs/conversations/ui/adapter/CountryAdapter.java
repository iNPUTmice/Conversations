package eu.siacs.conversations.ui.adapter;

import androidx.databinding.DataBindingUtil;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.CountryItemBinding;
import eu.siacs.conversations.utils.PhoneNumberUtilWrapper;

public class CountryAdapter extends RecyclerView.Adapter<CountryAdapter.CountryViewHolder> {

    private final List<PhoneNumberUtilWrapper.Country> countries;

    private OnCountryClicked onCountryClicked;

    public CountryAdapter(List<PhoneNumberUtilWrapper.Country> countries) {
        this.countries = countries;
    }

    @NonNull
    @Override
    public CountryViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater layoutInflater = LayoutInflater.from(parent.getContext());
        CountryItemBinding binding = DataBindingUtil.inflate(layoutInflater, R.layout.country_item, parent, false);
        return new CountryViewHolder(binding);
    }

    @Override
    public void onBindViewHolder(@NonNull CountryViewHolder holder, int position) {
        final PhoneNumberUtilWrapper.Country county = countries.get(position);
        holder.binding.country.setText(county.getName());
        holder.binding.countryCode.setText(county.getCode());
        holder.itemView.setOnClickListener(v -> {
            if (onCountryClicked != null) {
                onCountryClicked.onCountryClicked(county);
            }
        });
    }

    public void setOnCountryClicked(OnCountryClicked listener) {
        this.onCountryClicked = listener;
    }


    @Override
    public int getItemCount() {
        return countries.size();
    }


    class CountryViewHolder extends RecyclerView.ViewHolder {

        private final CountryItemBinding binding;

        CountryViewHolder(CountryItemBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    public interface OnCountryClicked {
        void onCountryClicked(PhoneNumberUtilWrapper.Country country);
    }

}
