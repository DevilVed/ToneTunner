package com.devilved.tonetunner.utils;

import android.content.Context;
import android.util.Pair;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import com.devilved.tonetunner.R;
import com.devilved.tonetunner.voice_translation.neural_networks.voice.Recognizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class LanguagePairAdapter extends ArrayAdapter<Pair<String, String>> {
    private List<Pair<String, String>> languagePairs;

    public LanguagePairAdapter(Context context, int resource, List<Pair<String, String>> languagePairs) {
        super(context, resource, languagePairs);
        this.languagePairs = languagePairs;
    }

    @Override
    public View getView(int position, View convertView, ViewGroup parent) {
        TextView view = (TextView) super.getView(position, convertView, parent);
        if (position < languagePairs.size()) {
            view.setText(languagePairs.get(position).second); // Display name
        }
        return view;
    }

    @Override
    public View getDropDownView(int position, View convertView, ViewGroup parent) {
        TextView view = (TextView) super.getDropDownView(position, convertView, parent);
        if (position < languagePairs.size()) {
            view.setText(languagePairs.get(position).second); // Display name
        }
        return view;
    }

    public static List<Pair<String, String>> getLanguagePairs(Context context){
        // Pre-allocate initial capacity (LANGUAGES count + 1 for "auto") to avoid list resizing allocations
        List<Pair<String, String>> languagePairs = new ArrayList<>(Recognizer.LANGUAGES.length + 1);
        // Iterate directly over LANGUAGES array to avoid unnecessary array cloning
        for (String code : Recognizer.LANGUAGES) {
            Locale locale = new Locale(code);
            languagePairs.add(new Pair<>(code, locale.getDisplayLanguage()));
        }

        // Sort by display name
        languagePairs.sort((pair1, pair2) -> pair1.second.compareToIgnoreCase(pair2.second));

        // Add auto at first position
        languagePairs.add(0, new Pair<>("auto", context.getString(R.string.auto_lang)));
        return languagePairs;
    }

    // Helper method to find index by language code
    public int getIndexByCode(String langCode) {
        if (langCode == null) {
            return 0;
        }
        // Cache list size to avoid repeated method invocations in loop condition
        for (int i = 0, size = languagePairs.size(); i < size; i++) {
            if (langCode.equals(languagePairs.get(i).first)) {
                return i;
            }
        }
        return 0; // Default to first item
    }
}
