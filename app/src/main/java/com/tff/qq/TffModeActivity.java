package com.tff.qq;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class TffModeActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);

        TextView title = new TextView(this);
        title.setText("TFF QQ 模式切换");
        title.setTextSize(22);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(16);
        status.setPadding(0, 24, 0, 24);
        root.addView(status);
        refreshStatus();

        Button btn1 = new Button(this);
        btn1.setText("普通模式（自动回复）");
        btn1.setOnClickListener(v -> switchMode(TffLogger.MODE_1));
        root.addView(btn1);

        Button btn2 = new Button(this);
        btn2.setText("探测模式（仅观察记录）");
        btn2.setOnClickListener(v -> switchMode(TffLogger.MODE_2));
        root.addView(btn2);

        setContentView(root);
    }

    private void refreshStatus() {
        int m = TffLogger.readMode();
        status.setText("当前模式: " + (m == TffLogger.MODE_2 ? "模式2 探测模式" : "模式1 普通模式"));
    }

    private void switchMode(int mode) {
        boolean ok = TffLogger.writeMode(mode);
        refreshStatus();
        String name = mode == TffLogger.MODE_2 ? "模式2（探测模式）" : "模式1（普通模式）";
        Toast.makeText(this,
                (ok ? "已切换到 " + name : "切换失败") + "，重启 QQ 后生效",
                Toast.LENGTH_LONG).show();
    }
}
