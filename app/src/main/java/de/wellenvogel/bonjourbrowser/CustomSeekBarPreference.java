package de.wellenvogel.bonjourbrowser;

import android.content.Context;
import android.support.v7.preference.PreferenceViewHolder;
import android.support.v7.preference.SeekBarPreference;
import android.util.AttributeSet;
import android.view.ViewGroup;
import android.widget.TextView;

public class CustomSeekBarPreference extends SeekBarPreference {
    private TextView mSeekBarValueTextView;

    public CustomSeekBarPreference(Context context,
                                   AttributeSet attributeSet,
                                   int i, int i1) {
        super(context, attributeSet, i, i1);
    }

    public CustomSeekBarPreference(Context context, AttributeSet attributeSet, int i) {
        super(context, attributeSet, i);
    }

    public CustomSeekBarPreference(Context context, AttributeSet attributeSet) {
        super(context, attributeSet);
    }

    public CustomSeekBarPreference(Context context) {
        super(context);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder preferenceViewHolder) {
        super.onBindViewHolder(preferenceViewHolder);
        mSeekBarValueTextView = (TextView) preferenceViewHolder.findViewById(android.support.v7.preference.R.id.seekbar_value);
        if (mSeekBarValueTextView != null) {
            ViewGroup.LayoutParams layoutParams = mSeekBarValueTextView.getLayoutParams();
            layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            mSeekBarValueTextView.setLayoutParams(layoutParams);
        }
    }
}
