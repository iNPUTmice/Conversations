package eu.siacs.conversations.ui.adapter;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Filter;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.regex.Pattern;

import eu.siacs.conversations.Config;

public class KnownHostsAdapter extends ArrayAdapter<String> {

    private static final Pattern E164_PATTERN = Pattern.compile("^\\+[1-9]\\d{1,14}$");

    private ArrayList<String> domains;
    private final Filter domainFilter = new Filter() {

        @Override
        protected FilterResults performFiltering(CharSequence constraint) {
            final ArrayList<String> suggestions = new ArrayList<>();
            final String[] split = constraint == null ? new String[0] : constraint.toString().split("@");
            if (split.length == 1) {
                final String local = split[0].toLowerCase(Locale.ENGLISH);
                if (Config.QUICKSY_DOMAIN != null && E164_PATTERN.matcher(local).matches()) {
                    suggestions.add(local + '@' + Config.QUICKSY_DOMAIN.toEscapedString());
                } else {
                    for (String domain : domains) {
                        suggestions.add(local + '@' + domain);
                    }
                }
            } else if (split.length == 2) {
                final String localPart = split[0].toLowerCase(Locale.ENGLISH);
                final String domainPart = split[1].toLowerCase(Locale.ENGLISH);
                if (domains.contains(domainPart)) {
                    return new FilterResults();
                }
                for (String domain : domains) {
                    if (domain.contains(domainPart)) {
                        suggestions.add(localPart + "@" + domain);
                    }
                }
            } else {
                return new FilterResults();
            }
            FilterResults filterResults = new FilterResults();
            filterResults.values = suggestions;
            filterResults.count = suggestions.size();
            return filterResults;
        }

        @Override
        protected void publishResults(CharSequence constraint, FilterResults results) {
            ArrayList filteredList = (ArrayList) results.values;
            if (results.count > 0) {
                clear();
                addAll(filteredList);
                notifyDataSetChanged();
            }
        }
    };

    public KnownHostsAdapter(Context context, int viewResourceId, Collection<String> mKnownHosts) {
        super(context, viewResourceId, new ArrayList<>());
        domains = new ArrayList<>(mKnownHosts);
        Collections.sort(domains);
    }

    public KnownHostsAdapter(Context context, int viewResourceId) {
        super(context, viewResourceId, new ArrayList<>());
        domains = new ArrayList<>();
    }

    public void refresh(Collection<String> knownHosts) {
        domains = new ArrayList<>(knownHosts);
        Collections.sort(domains);
        notifyDataSetChanged();
    }

    @Override
    @NonNull
    public Filter getFilter() {
        return domainFilter;
    }
}
