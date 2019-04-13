package bonjourbrowser.de.wellenvogel.bonjourbrowser;

import android.content.Context;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ListView list;
    private TargetAdapter adapter;
    private static String PRFX="BonjourBrowser";

    static class Target{
        public String name;
        public String host;
        public URI uri;
    }
    static class TargetAdapter extends ArrayAdapter<Target>{

        public TargetAdapter(@NonNull Context context) {
            super(context,-1);
        }

        public void setItems(List<Target> items){
            super.clear();
            super.addAll(items);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            LayoutInflater inflater = (LayoutInflater) getContext()
                    .getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            View rowView = inflater.inflate(android.R.layout.simple_list_item_2, parent, false);
            TextView title=rowView.findViewById(android.R.id.text1);
            title.setText("title");
            TextView sub=rowView.findViewById(android.R.id.text2);
            sub.setText("sub");
            return rowView;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Button scanButton=(Button)findViewById(R.id.scan);
        scanButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                scan();
            }
        });
        list=(ListView)findViewById(R.id.list);
        adapter=new TargetAdapter(this);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                Log.i(PRFX,"Clicked: "+Integer.toString(i));
            }
        });
    }

    private void scan(){
        ArrayList<Target> items=new ArrayList<>();
        for (int i=0;i<100;i++) {
            items.add(new Target());
        }
        adapter.setItems(items);
    }

}
